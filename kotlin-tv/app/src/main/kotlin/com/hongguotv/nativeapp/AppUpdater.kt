// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.Manifest
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.FileProvider
import com.hongguotv.core.AppUpdate
import com.hongguotv.core.AppUpdateRepository
import com.hongguotv.core.UpdateFiles
import java.io.File
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.Future

/** Foreground-only updater: no background service, and no networking or prompts during playback. */
class AppUpdater(private val activity: Activity, private val idle: () -> Boolean) {
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val repository = AppUpdateRepository()
    private val preferences = activity.getSharedPreferences("app_updates", Activity.MODE_PRIVATE)
    @Suppress("DEPRECATION")
    private val apk = File(Environment.getExternalStorageDirectory(), "Downloads/hongguotv-update.apk")
    private val installApk = File(activity.cacheDir, "updates/install.apk")
    private var task: Future<*>? = null
    private var generation = 0
    private var active = false
    private var busy = false
    private var available: AppUpdate? = null
    private var ready = false
    private var permissionRequested = false
    private var storagePermissionRequested = false
    private val storageRequestCode = 4102
    private fun storageAllowed() = if(Build.VERSION.SDK_INT >= 30) Environment.isExternalStorageManager()
        else activity.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)==PackageManager.PERMISSION_GRANTED
    fun onRequestPermissionsResult(requestCode: Int) {
        if(requestCode!=storageRequestCode) return
        if(active) {
            storagePermissionRequested=false
            continueAfterStoragePermission()
        }
    }
    private fun continueAfterStoragePermission() {
        if(storageAllowed()) download(true)
        else { updateStatus("未获得文件访问权限，无法保存更新到 ${apk.parent}；可按「下载新版」重新授权。"); show(anchor) }
    }
    private fun requestStoragePermission() {
        storagePermissionRequested=true
        try {
            if(Build.VERSION.SDK_INT >= 30) {
                val intent=Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,Uri.parse("package:${activity.packageName}"))
                try { activity.startActivity(intent) }
                catch(_: android.content.ActivityNotFoundException) { activity.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) }
            } else activity.requestPermissions(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE),storageRequestCode)
        } catch(_: Exception) {
            storagePermissionRequested=false
            updateStatus("电视未提供文件授权入口，无法保存到 ${apk.parent}。请在系统设置中开启文件访问权限。")
        }
    }
    private var dialog: AlertDialog? = null
    val showing get() = dialog?.isShowing == true
    private var statusView: TextView? = null
    private var anchor: View? = null
    private var status = "当前版本 ${BuildConfig.VERSION_NAME}"
    private var retryAfter = 0L
    private var promptedCode = 0L
    private val auto get() = preferences.getBoolean("automatic", true)
    private val wifiOnly get() = preferences.getBoolean("wifi_only", true)
    private val poll = object : Runnable {
        override fun run() {
            if (!active) return
            if (idle() && dialog == null && !busy && auto) {
                val now = System.currentTimeMillis()
                val last = preferences.getLong("last_attempt", 0)
                val update = available
                when {
                    ready && update != null && promptedCode != update.versionCode && !deferred(update) -> { promptedCode = update.versionCode; show(activity.currentFocus) }
                    update != null && !ready && now >= retryAfter && !skipped(update) && autoDownloadAllowed() -> download(false)
                    update != null && !ready && now >= retryAfter && !skipped(update) && promptedCode != update.versionCode -> { promptedCode = update.versionCode; show(activity.currentFocus) }
                    now < last || now - last >= 6 * 60 * 60 * 1000L -> check(false)
                }
            }
            main.postDelayed(this, 15000)
        }
    }
    fun resume() {
        active = true; main.removeCallbacks(poll); main.postDelayed(poll, 8000)
        if(storagePermissionRequested) {
            storagePermissionRequested=false
            continueAfterStoragePermission()
        }
        if (permissionRequested) {
            permissionRequested = false
            if (activity.packageManager.canRequestPackageInstalls()) install()
            else { status = "安装权限未开启，可在这里再次安装"; show(anchor) }
        }
    }
    fun pause() {
        active = false; main.removeCallbacks(poll); interrupt()
        dialog?.dismiss()
    }
    fun interrupt() {
        generation++; task?.cancel(true); task = null; repository.cancel(); busy = false
    }
    fun destroy() {
        pause(); worker.shutdownNow()
        // TLS socket close may send close_notify; it must not run on Android's main thread.
        Thread({ repository.close() }, "hongguotv-update-cleanup").start()
    }
    private fun updateStatus(text: String) { status = text; statusView?.text = text }
    private fun deferred(update: AppUpdate) = (preferences.getLong("deferred_code", 0) == update.versionCode && preferences.getLong("deferred_until", 0) > System.currentTimeMillis()) || skipped(update)
    /** 「跳过此版」只对当前 versionCode 生效，下个版本会重新提示。 */
    private fun skipped(update: AppUpdate) = preferences.getLong("skipped_code", 0) == update.versionCode
    private fun metered(): Boolean {
        val manager = activity.getSystemService(ConnectivityManager::class.java) ?: return false
        return runCatching { manager.isActiveNetworkMetered }.getOrDefault(false)
    }
    private fun autoDownloadAllowed() = !wifiOnly || !metered()
    /** README 生成的说明带 markdown 列表和加粗，电视上按纯文本排一下更好读。 */
    private fun prettyNotes(notes: String): String = notes.lineSequence().joinToString("\n") { line ->
        val text = line.trim()
        when {
            text.startsWith("## ") -> text.removePrefix("## ").trim()
            text.startsWith("### ") -> text.removePrefix("### ").trim()
            text.startsWith("#### ") -> text.removePrefix("#### ").trim()
            text.startsWith("- ") || text.startsWith("* ") -> "• " + text.drop(2).replace("**", "")
            else -> text.replace("**", "")
        }
    }.trim()
    private fun renderActions() {
        dialog?.getButton(AlertDialog.BUTTON_POSITIVE)?.apply {
            text = when { busy -> "处理中…"; ready -> "安装新版"; available != null -> "下载新版"; else -> "检查更新" }
            // Keep D-pad focus stable while the request runs; action methods reject duplicates.
            isEnabled = true
        }
        // 只有发现新版时才显示「跳过此版」；已经跳过时按钮改为「取消跳过」。
        val update=available
        dialog?.getButton(AlertDialog.BUTTON_NEUTRAL)?.apply {
            visibility = if (update != null) View.VISIBLE else View.GONE
            text = if (update != null && skipped(update)) "取消跳过" else "跳过此版"
        }
    }
    fun show(from: View?) {
        if (!active) return
        anchor = from
        dialog?.dismiss()
        val dp = activity.resources.displayMetrics.density
        val body = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; setPadding((24*dp).toInt(), (12*dp).toInt(), (24*dp).toInt(), 0) }
        statusView = TextView(activity).apply { text = status; textSize = 17f; setTextColor(TvStyle.text) }
        body.addView(statusView)
        body.addView(CheckBox(activity).apply {
            text = "自动检查并下载更新"; isChecked = auto
            setOnCheckedChangeListener { _, enabled ->
                preferences.edit().putBoolean("automatic", enabled).apply()
                if (!enabled && busy) { interrupt(); updateStatus("自动更新已关闭，可手动检查"); renderActions() }
            }
        })
        body.addView(CheckBox(activity).apply {
            text = "仅在 Wi-Fi / 非计费网络自动下载"; isChecked = wifiOnly
            setOnCheckedChangeListener { _, enabled -> preferences.edit().putBoolean("wifi_only", enabled).apply() }
        })
        body.addView(TextView(activity).apply {
            text = "只在应用前台浏览时检查，播放中不打扰。自动下载默认只在 Wi-Fi 等非计费网络进行；GitHub 直连超时会自动改用加速线路，下载后需确认系统安装，收藏与观看记录保留。"; textSize = 13f; setTextColor(TvStyle.muted)
            setPadding(0, (8*dp).toInt(), 0, (8*dp).toInt())
        })
        val next = AlertDialog.Builder(activity).setTitle("版本与更新 · ${BuildConfig.VERSION_NAME}").setView(ScrollView(activity).apply { addView(body) })
            .setNegativeButton("稍后", null).setNeutralButton("跳过此版", null).setPositiveButton("检查更新", null).create()
        dialog = next
        next.setOnDismissListener {
            if (dialog === next) { dialog = null; statusView = null }
            if (from?.isAttachedToWindow == true) from.requestFocus()
        }
        next.show()
        next.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { when { ready -> install(); available != null -> download(true); else -> check(true) } }
        next.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener {
            available?.let { preferences.edit().putLong("deferred_code", it.versionCode).putLong("deferred_until", System.currentTimeMillis()+24*60*60*1000L).apply() }
            next.dismiss()
        }
        next.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
            available?.let { update ->
                if (skipped(update)) {
                    preferences.edit().remove("skipped_code").apply()
                    updateStatus("已取消跳过 ${update.versionName}，可继续下载")
                } else {
                    preferences.edit().putLong("skipped_code", update.versionCode).apply()
                    updateStatus("已跳过 ${update.versionName}，下个版本会再提示；仍可在本页手动下载")
                }
            }
            next.dismiss()
        }
        renderActions()
        next.getButton(AlertDialog.BUTTON_POSITIVE).post { if (dialog === next) next.getButton(AlertDialog.BUTTON_POSITIVE).requestFocus() }
    }
    private fun <T> work(action: () -> T, done: (T) -> Unit) {
        busy = true; renderActions(); val ticket = ++generation
        task = worker.submit {
            try {
                val result = action()
                main.post { if (active && generation == ticket) { busy = false; done(result); renderActions() } }
            } catch (e: Exception) {
                main.post { if (active && generation == ticket) {
                    busy = false; ready = false; retryAfter = System.currentTimeMillis()+30*60*1000L
                    updateStatus(if (e is IOException) e.message ?: "更新失败，请重试" else "更新校验失败，请重试"); renderActions()
                } }
            }
        }
    }
    private fun check(manual: Boolean) {
        if (busy || !active || !idle()) return
        preferences.edit().putLong("last_attempt", System.currentTimeMillis()).apply()
        updateStatus("正在检查新版…")
        work({ repository.latest() }) { update ->
            available = update.takeIf { it.newerThan(BuildConfig.VERSION_CODE.toLong(), Build.VERSION.SDK_INT) }; ready = false
            if (available == null) {
                updateStatus(if (update.versionCode > BuildConfig.VERSION_CODE && update.minSdk > Build.VERSION.SDK_INT) "新版需要 Android API ${update.minSdk}，当前电视继续使用本版" else "已是最新版本 ${BuildConfig.VERSION_NAME}")
                worker.execute { installApk.delete() }
            } else {
                val notes = prettyNotes(update.notes)
                updateStatus("发现 ${update.versionName} · ${"%.1f".format(update.size/1048576.0)} MB\n$notes")
                when {
                    manual -> download(true)
                    skipped(update) -> updateStatus("已跳过 ${update.versionName}，下个版本会再提示；可在本页手动下载\n$notes")
                    !autoDownloadAllowed() -> updateStatus("发现 ${update.versionName} · ${"%.1f".format(update.size/1048576.0)} MB\n当前为计费网络，已暂停自动下载；按「下载新版」继续。\n$notes")
                    auto -> download(false)
                }
            }
        }
    }
    private fun download(manual: Boolean) {
        val update = available ?: return
        if (busy || !active || !idle()) return
        if(!storageAllowed()) {
            updateStatus("下载更新需要文件访问权限，安装包保存到 ${apk.path}。")
            if(manual) requestStoragePermission()
            else {
                retryAfter=System.currentTimeMillis()+30*60*1000L
                if(dialog==null && promptedCode!=update.versionCode) { promptedCode=update.versionCode; show(activity.currentFocus) }
            }
            return
        }
        updateStatus("正在下载 ${update.versionName}…")
        val ticket = generation + 1
        work({
            repository.download(update, apk) { percent -> main.post { if (generation == ticket && active) updateStatus("正在下载 ${update.versionName} · $percent%") } }
            validateApk(update)
        }) {
            ready = true; updateStatus("${update.versionName} 已下载并校验\n保存位置：${apk.path}\n${prettyNotes(update.notes)}\n\n确认后由系统覆盖安装，保留本机记录。")
            if (idle() && dialog == null && (manual || !deferred(update))) { promptedCode = update.versionCode; show(activity.currentFocus) }
        }
    }
    @Suppress("DEPRECATION")
    private fun validateApk(update: AppUpdate, apk: File = this.apk) {
        if (!UpdateFiles.verify(apk, update)) throw IOException("安装包校验未通过，请重新下载")
        val pm = activity.packageManager
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val candidate = pm.getPackageArchiveInfo(apk.path, flags) ?: throw IOException("安装包无法识别")
        val installed = pm.getPackageInfo(activity.packageName, flags)
        val version = if (Build.VERSION.SDK_INT >= 28) candidate.longVersionCode else candidate.versionCode.toLong()
        fun signers(info: android.content.pm.PackageInfo) =
            (if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners else info.signatures)?.map { it.toCharsString() }?.toSet().orEmpty()
        if (candidate.packageName != activity.packageName || version != update.versionCode || candidate.versionName != update.versionName || version <= BuildConfig.VERSION_CODE ||
            candidate.applicationInfo?.minSdkVersion != update.minSdk || signers(installed).isEmpty() || signers(installed) != signers(candidate))
            throw IOException("新版包名、版本或签名不匹配，已阻止安装")
    }
    private fun install() {
        val update = available ?: return
        if (busy || !active || !ready) return
        work({
            // Shared storage can change externally. Install only a freshly checked private copy.
            installApk.parentFile?.mkdirs()
            apk.copyTo(installApk,overwrite=true)
            validateApk(update,installApk)
        }) {
            try {
                if (!activity.packageManager.canRequestPackageInstalls()) {
                    permissionRequested = true
                    activity.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${activity.packageName}")))
                } else {
                    val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.updates", installApk)
                    activity.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION).apply { clipData = ClipData.newRawUri("更新安装包", uri) })
                }
            } catch (_: Exception) { permissionRequested = false; updateStatus("电视未提供安装或授权入口，请通过系统文件管理器安装新版") }
        }
    }
}
