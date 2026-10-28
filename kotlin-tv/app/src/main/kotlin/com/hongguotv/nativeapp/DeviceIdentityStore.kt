// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.content.Context
import android.util.Log
import com.hongguotv.core.ContentRepository
import com.hongguotv.core.DeviceIdentity
import java.util.concurrent.Executor

/**
 * 设备身份的本地存档。
 *
 * 红果的搜索等接口只对「已注册设备」返回数据：随机 device_id 会被风控挡下（HTTP 200 空响应）。
 * 首次启动注册一次并长期复用，之后的启动直接读回，不再请求。注册失败不影响播放、分类等其它功能。
 */
internal object DeviceIdentityStore {
    private const val FILE="native-device-v1"
    private const val KEY_INSTALL="install_id"
    private const val KEY_DEVICE="device_id"

    /** 读回上次注册的身份；没有存档时保持内置默认值。 */
    fun restore(context: Context) {
        val prefs=context.applicationContext.getSharedPreferences(FILE,Context.MODE_PRIVATE)
        DeviceIdentity.apply(prefs.getString(KEY_INSTALL,"").orEmpty(),prefs.getString(KEY_DEVICE,"").orEmpty())
    }

    /** 后台注册一次设备身份。失败只记日志，下次启动再试。 */
    fun ensure(context: Context,repository: ContentRepository,worker: Executor) {
        val app=context.applicationContext
        fun save(registration: com.hongguotv.core.DeviceRegistration) {
            app.getSharedPreferences(FILE,Context.MODE_PRIVATE).edit()
                .putString(KEY_INSTALL,registration.installId).putString(KEY_DEVICE,registration.deviceId).apply()
        }
        repository.onDeviceRegistration=::save
        if(DeviceIdentity.registered) return
        worker.execute {
            runCatching { repository.registerDevice() }
                .onSuccess { registration ->
                    if(DeviceIdentity.apply(registration.installId,registration.deviceId)) {
                        save(registration)
                        Log.i("HongguoTV","Device registered")
                    }
                }
                .onFailure { Log.w("HongguoTV","Device registration failed; keeping fallback identity",it) }
        }
    }
}
