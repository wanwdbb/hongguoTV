package com.hongguotv.core

/** 所有网络单测使用独立的匿名身份，不能访问真实注册接口或污染共享会话。 */
internal fun playbackFixture() = PlaybackSession { _,_ ->
    DeviceRegister.Registered("333","444",cookies=mapOf("install_id" to "444","ttreq" to "fixture","sessionid" to "must-not-leak"))
}
