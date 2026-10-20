// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

/**
 * 设备身份（install_id / device_id）。
 *
 * 红果的搜索等接口只对"已注册设备"返回数据：随机 device_id 会被风控挡下（HTTP 200 空响应）。
 * 应用启动时用一次设备注册换取身份并长期复用；注册前退回内置默认值，其它接口不受影响。
 * 单测与探针可用 -Dhongguotv.installId= / -Dhongguotv.deviceId= 固定身份。
 */
object DeviceIdentity {
    private const val DEFAULT_INSTALL_ID = "1905892595382586"
    private val numeric = Regex("[0-9]{1,30}")

    @Volatile private var installIdValue = property("hongguotv.installId") ?: DEFAULT_INSTALL_ID
    @Volatile private var deviceIdValue = property("hongguotv.deviceId") ?: randomDeviceId()
    @Volatile private var registeredValue = false

    val installId: String get() = installIdValue
    val deviceId: String get() = deviceIdValue
    /** 是否已经拿到注册身份；未注册时接口仍可用，只是搜索会被风控挡下。 */
    val registered: Boolean get() = registeredValue

    private fun property(name: String) = System.getProperty(name)?.takeIf { it.matches(numeric) }
    private fun randomDeviceId() = kotlin.random.Random.nextLong(1_000_000_000_000_000L,9_000_000_000_000_000L).toString()

    /** 注册成功后写入并立即对所有请求生效；格式不合法时保持原身份。 */
    fun apply(installId: String,deviceId: String): Boolean {
        val install=installId.trim(); val device=deviceId.trim()
        if(!install.matches(numeric) || !device.matches(numeric)) return false
        installIdValue=install; deviceIdValue=device; registeredValue=true
        return true
    }
}
