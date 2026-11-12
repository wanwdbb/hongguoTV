// SPDX-License-Identifier: GPL-3.0-only
// Adapted from N3urda/hongguoTV PlayerSource and ContentRepository.
package com.hongguotv.core

import java.io.IOException
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

internal class CompatibilityPlayback(private val http: OkHttpClient, private val device: (() -> PlayerDevice)? = null) {
    private val devices=PlayerDeviceSource(http)
    @Volatile private var preferredTemplate: String?=null
    fun preferFullHd() { preferredTemplate=PlayerDeviceSource.PHOENIX_URL }
    fun fetch(id: String, quality: (JSONObject) -> Int = { Int.MAX_VALUE }): JSONObject = RequestScope.withDeadline(System.nanoTime()+TimeUnit.SECONDS.toNanos(12)) {
        val device=try { device?.invoke() ?: devices.obtain() } catch(error: org.json.JSONException) { throw IOException("备用设备注册响应异常",error) }
        var failure: IOException?=null
        var best: JSONObject?=null
        var bestQuality=-1
        for((template,body,ua) in listOf(
            Triple(PlayerDeviceSource.SHORTPLAY_URL,PlayerDeviceSource.shortplayBody(id),"com.dragon.read"),
            Triple(PlayerDeviceSource.PHOENIX_URL,PlayerDeviceSource.phoenixBody(id),VendorConstants.VIDEO_UA)
        ).sortedBy { if(it.first==preferredTemplate) 0 else 1 }) {
            RequestScope.ensureActive()
            val url=PlayerDeviceSource.substituteDevice(template,device)
            val request=Request.Builder().url(url).header("User-Agent",ua).header("Accept-Language","zh-CN,zh;q=0.9")
                .header("Accept","application/json; charset=utf-8,application/x-protobuf").header("x-xs-from-web","0").header("sdk-version","2")
                .post(body.toRequestBody("application/json; charset=utf-8".toMediaType()))
            PlayerSigner.headers(url.substringAfter('?'),body).forEach { (k,v) -> request.header(k,v) }
            try {
                val raw=RequestScope.execute(http.newCall(request.build())) { response ->
                    if(response.code in setOf(401,403)) { devices.invalidate(); throw IOException("备用播放设备被拒绝") }
                    if(!response.isSuccessful) throw IOException("备用播放请求失败（HTTP ${response.code}）")
                    val source=response.body?.source() ?: throw IOException("备用播放响应为空")
                    val buffer=okio.Buffer()
                    while(buffer.size<=16*1024*1024) { if(source.read(buffer,8192)<0) break }
                    if(buffer.size>16*1024*1024) throw IOException("备用播放响应过大")
                    buffer.readUtf8()
                }
                val result=try { JSONObject(raw) } catch(_: org.json.JSONException) { throw IOException("备用播放格式异常") }
                val code=result.optInt("code",result.optInt("Code",0))
                if(code!=0) { if(code in setOf(10001,10002,110001)) devices.invalidate(); throw IOException("备用播放接口拒绝请求（错误码 $code）") }
                val data=result.optJSONObject("data") ?: throw IOException("备用播放信息为空")
                val entry=data.optJSONObject(id) ?: data.takeIf { it.has("video_model") } ?: throw IOException("备用播放缺少当前分集")
                val model=entry.optJSONObject("video_model") ?: runCatching { JSONObject(entry.str("video_model")) }.getOrNull()
                if(model==null || !model.has("video_list") && !model.has("fallback_api")) throw IOException("备用播放模型异常")
                val candidate=JSONObject().put("data",JSONObject().put(id,entry))
                val score=quality(candidate)
                if(score>bestQuality) { best=candidate; bestQuality=score }
                if(score==Int.MAX_VALUE) { preferredTemplate=template; return@withDeadline candidate }
            } catch(error: IOException) { RequestScope.ensureActive(); failure=error }
        }
        best ?: throw (failure ?: IOException("备用播放接口暂不可用"))
    }
}
