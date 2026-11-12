// SPDX-License-Identifier: GPL-3.0-only
// 匿名身份、完整播放签名及 GET/POST 换路参考 tsukiyo-zero/hongguoTV 70edafff。
package com.hongguotv.core

import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit
import okhttp3.CookieJar
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

class PlaybackModelException(val diagnostic: String, message: String, val compatibilityEligible: Boolean = false) : IOException(message)
class PlaybackRegistrationException : IOException("播放设备注册失败，请稍后重试")

/** 路径状态绑定匿名身份，前台与预加载共享，身份替换时自然失效。 */
internal class PlaybackRoutes {
    data class Route(val host: String, val get: Boolean)
    private data class Health(var success: Long=Long.MIN_VALUE, var until: Long=0, var empty: Boolean=false)
    val routes=listOf(Route("api5-normal-sinfonlineb.fqnovel.com",true)) +
        listOf("api5-normal-sinfonlineb.fqnovel.com","api5-normal-hl.fqnovel.com","api5-normal-lf.fqnovel.com").map { Route(it,false) }
    private val health=routes.associateWith { Health() }
    @Synchronized fun plan(now: Long, speculative: Boolean): List<Route> {
        val available=routes.filter { health.getValue(it).until<=now }.sortedByDescending { health.getValue(it).success }
        if(speculative) return available.take(1)
        if(available.isEmpty()) return listOf(routes.filter { !it.get }.minBy { health.getValue(it).until })
        // 只剩 GET 可用时仍探测一条 POST，不能让旧冷却状态阻断内容特定的备用路径。
        return if(available.none { !it.get }) available + routes.filter { !it.get }.minBy { health.getValue(it).until } else available
    }
    @Synchronized fun exhausted(now: Long) = health.values.all { it.empty && it.until>now }
    @Synchronized fun mark(route: Route, now: Long, success: Boolean, empty: Boolean=false) {
        health.getValue(route).apply {
            this.empty=!success && empty
            if(success) { this.success=now; until=0 } else until=now+if(empty) 30_000 else 5_000
        }
    }
}

/** 匿名播放身份仅存内存，不读取或修改账号、搜索身份。 */
class PlaybackSession internal constructor(
    private val registration: (OkHttpClient, Map<String,String>) -> DeviceRegister.Registered? = { http, values ->
        val bytes=DeviceRegister.payload(values)
        val request=Request.Builder().url(DeviceRegister.url())
        DeviceRegister.headers(bytes,values).forEach { (k,v) -> request.header(k,v) }
        request.post(bytes.toRequestBody("application/octet-stream;tt-data=a".toMediaType()))
        RequestScope.execute(http.newCall(request.build())) { response ->
            if(!response.isSuccessful) throw PlaybackRegistrationException()
            val body=response.body ?: throw PlaybackRegistrationException()
            val source=body.source(); val buffer=okio.Buffer()
            while(buffer.size<=1024*1024) { if(source.read(buffer,8192)<0) break }
            if(buffer.size>1024*1024) throw PlaybackRegistrationException()
            val bytes=buffer.readByteArray()
            DeviceRegister.parse(String(bytes,Charsets.UTF_8),response.headers("Set-Cookie"))
        }
    }
) {
    internal class Profile(val values: Map<String,String>, val cookie: String) {
        val routes=PlaybackRoutes()
        private var signer: PlaybackSigner?=null
        @Synchronized fun signer(clock: () -> Long): PlaybackSigner = signer ?: PlaybackSigner(clock,values).also { signer=it }
    }
    private var ready: Profile?=null
    private var rotationAfter=Long.MIN_VALUE
    @Synchronized internal fun profile(http: OkHttpClient): Profile {
        ready?.let { return it }
        return register(http).also { ready=it }
    }
    /** 前台全空响应才更新；成功后冷却三十秒，注册失败五秒；失败保留原身份。 */
    @Synchronized internal fun recover(http: OkHttpClient, expected: Profile, now: Long): Profile {
        RequestScope.ensureActive()
        if(ready !== expected) return ready ?: expected
        if(now<rotationAfter) return expected
        rotationAfter=now+30_000
        return try { register(http).also { ready=it } } catch(_: IOException) {
            RequestScope.ensureActive(); rotationAfter=now+5_000; expected
        }
    }
    private fun register(http: OkHttpClient): Profile {
        RequestScope.ensureActive()
        val values=VendorConstants.device + mapOf("cdid" to UUID.randomUUID().toString(),
            "version_code" to "73932", "manifest_version_code" to "73932", "update_version_code" to "73932",
            "pv_player" to "73932", "version_name" to "7.3.9.32")
        val result=try { registration(http,values) ?: throw PlaybackRegistrationException() }
        catch(error: IOException) { RequestScope.ensureActive(); throw PlaybackRegistrationException() }
        if(!Regex("[1-9][0-9]{0,29}").matches(result.deviceId) || !Regex("[1-9][0-9]{0,29}").matches(result.installId))
            throw PlaybackRegistrationException()
        RequestScope.ensureActive()
        val cookie=result.cookies.filterKeys { it in setOf("install_id","ttreq","store-region","store-region-src") }
            .entries.joinToString("; ") { "${it.key}=${it.value}" }
        return Profile(values+mapOf("device_id" to result.deviceId,"iid" to result.installId),cookie)
    }
    companion object { val shared=PlaybackSession() }
}

/** 四条候选全空时，本轮剩余八秒内允许一次注册恢复及一次验证请求；后台不更新身份。 */
internal class PlaybackModels(private val http: OkHttpClient, private val session: PlaybackSession, private val clock: () -> Long) {
    fun fetch(id: String, speculative: Boolean=false): JSONObject {
        val deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(if(speculative) 2 else 8)
        fun transport(): OkHttpClient {
            val remaining=TimeUnit.NANOSECONDS.toMillis(deadline-System.nanoTime())
            if(remaining<=0) throw java.net.SocketTimeoutException("播放信息读取超时")
            return http.newBuilder().cookieJar(CookieJar.NO_COOKIES)
                .connectTimeout(minOf(remaining,4000),TimeUnit.MILLISECONDS)
                .readTimeout(minOf(remaining,4000),TimeUnit.MILLISECONDS).callTimeout(remaining,TimeUnit.MILLISECONDS).build()
        }
        return RequestScope.withDeadline(deadline) {
            val previous=session.profile(transport())
            var profile=if(!speculative && previous.routes.exhausted(clock())) session.recover(transport(),previous,clock()) else previous
            var recoveryTried=profile !== previous
            val trace=mutableListOf<String>()
            var last: IOException?=null
            val pending=java.util.ArrayDeque(profile.routes.plan(clock(),speculative))
            var attempts=0
            val limit=if(speculative) 1 else 5
            while(attempts<limit) {
                if(pending.isEmpty()) {
                    if(speculative || recoveryTried || !profile.routes.exhausted(clock())) break
                    recoveryTried=true
                    val replacement=session.recover(transport(),profile,clock())
                    if(replacement === profile) break
                    profile=replacement
                    trace+="session=refreshed"
                    pending.addAll(profile.routes.plan(clock(),false).take(limit-attempts))
                }
                if(pending.isEmpty()) break
                val route=pending.removeFirst(); attempts++
                RequestScope.ensureActive()
                val extra=if(route.get) linkedMapOf("video_id" to id,"video_id_type" to "0","device_level" to "1",
                    "need_all_video_definition" to "1","need_mp4_align" to "0","use_os_player" to "0",
                    "video_platform" to "1024","source" to "0","from_video_id" to "") else emptyMap()
                val payload=if(route.get) null else """{"biz_param":{"detail_page_version":0,"device_level":1,"disable_digg_stat":false,"need_all_video_definition":true,"need_mp4_align":false,"use_os_player":false,"use_server_dns":false,"video_platform":1024},"mixed_video_id_map":{"1004":["$id"]}}""".toByteArray()
                val path=if(route.get) "/reading/bookapi/video_tab/multi_video_model/v1/" else "/novel/player/multi_video_model/v1/"
                val signed=profile.signer(clock).signPlayback("https://${route.host}$path",profile.values+extra,payload,clock())
                val request=Request.Builder().url(signed.url)
                signed.headers.forEach { (k,v) -> request.header(k,v) }
                if(profile.cookie.isNotBlank()) request.header("Cookie",profile.cookie)
                payload?.let { request.post(it.toRequestBody("application/json; charset=utf-8".toMediaType())) }
                val tag=if(route.get) "GET" else "POST"
                var observed=false
                try {
                    val raw=RequestScope.execute(transport().newCall(request.build())) { response ->
                        val body=response.body ?: throw IOException("播放信息为空")
                        if(body.contentLength()>16*1024*1024) throw IOException("播放信息过大")
                        val source=body.source()
                        val buffer=okio.Buffer()
                        while(buffer.size<=16*1024*1024) { if(source.read(buffer,8192)<0) break }
                        if(buffer.size>16*1024*1024) throw IOException("播放信息过大")
                        val bytes=buffer.readByteArray()
                        observed=true
                        trace+="$tag:${profile.routes.routes.indexOf(route)}=${response.code}/${bytes.size}B"
                        if(!response.isSuccessful) throw IOException("播放请求失败（HTTP ${response.code}）")
                        if(bytes.isEmpty()) throw PlaybackModelException(trace.joinToString(" "),"播放接口返回空，请稍后重试")
                        String(bytes,Charsets.UTF_8)
                    }
                    val root=try { JSONObject(raw) } catch(_: Exception) { throw IOException("播放接口格式异常") }
                    val code=root.optInt("Code",root.optInt("code",0))
                    if(code!=0) throw IOException("播放接口暂不可用（错误码 $code）")
                    val data=root.optJSONObject("data") ?: throw IOException("播放信息缺少当前分集")
                    val rows=if(route.get) data.optJSONObject("video_model_data") ?: data else data
                    // 不复制参考项目的任取首条兜底，避免静默播放其他分集。
                    val entry=rows.optJSONObject(id) ?: throw IOException("播放信息缺少当前分集")
                    val model=entry.optJSONObject("video_model") ?: runCatching { JSONObject(entry.str("video_model")) }.getOrNull()
                        ?: throw IOException("播放模型格式异常")
                    val list=model.opt("video_list")
                    val hasVariants=when(list) { is org.json.JSONArray -> list.length()>0; is JSONObject -> list.length()>0; else -> false }
                    if(!hasVariants && !model.has("fallback_api")) throw IOException("播放模型格式异常")
                    RequestScope.ensureActive(); profile.routes.mark(route,clock(),true)
                    return@withDeadline JSONObject().put("code",0).put("data",JSONObject().put(id,entry))
                } catch(error: IOException) {
                    RequestScope.ensureActive()
                    if(!observed) trace+="$tag:${profile.routes.routes.indexOf(route)}=${if(error is java.net.SocketTimeoutException) "timeout" else "io"}"
                    last=error; profile.routes.mark(route,clock(),false,error is PlaybackModelException)
                }
            }
            val message=when(last) {
                is java.net.SocketTimeoutException -> "播放信息读取超时，请检查网络后重试"
                is java.net.UnknownHostException -> "播放域名解析失败，请检查网络后重试"
                is java.net.SocketException -> "播放连接失败，请稍后重试"
                else -> last?.message ?: "播放接口暂不可用"
            }
            throw PlaybackModelException(trace.joinToString(" "),message, trace.any { it.contains("/0B") || it.contains("=401/") || it.contains("=403/") })
        }
    }
}
