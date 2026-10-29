package com.hongguotv.core

import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import java.security.MessageDigest

class LegacyPlaybackContractTest {
    private val session=AuthSession(mapOf("sessionid" to "private-account"),"private-token",AuthUser("u","fixture"))
    private val direct="""{"code":0,"data":{"123":{"video_model":{"video_list":[{"main_url":"https://example.invalid/video.mp4","height":720,"codec_type":"h264"}]}}}}"""
    private fun repository(reply: (Request)->String)=ContentRepository(OkHttpClient.Builder().addInterceptor { chain ->
        Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
            .body(reply(chain.request()).toResponseBody()).build()
    }.build())

    @Test fun loggedInPlaybackRetainsOriginalWireContractAcrossRepeatedRequests() {
        var calls=0
        val identity=DeviceIdentity.deviceId
        val repo=repository { request ->
            calls++
            assertEquals("/novel/player/multi_video_model/preload/v1/",request.url.encodedPath)
            assertEquals("71332",request.url.queryParameter("version_code"))
            assertEquals(VendorConstants.playbackDevice.getValue("device_id"),request.url.queryParameter("device_id"))
            assertNull(request.header("Content-Encoding"))
            assertNull(request.header("Cookie"));assertNull(request.header("X-Tt-Token"))
            val bytes=okio.Buffer().also {request.body!!.writeTo(it)}.readByteArray()
            val original="""{"biz_param":{"detail_page_version":0,"device_level":3,"disable_digg_stat":false,"need_all_video_definition":true,"need_mp4_align":false,"use_os_player":false,"use_server_dns":false,"video_platform":1024},"mixed_video_id_map":{"1004":["123"]}}"""
            assertArrayEquals(original.toByteArray(),bytes)
            val digest=MessageDigest.getInstance("MD5").digest(bytes).joinToString("") {"%02x".format(it)}
            assertEquals(digest,request.header("x-ss-stub")!!.lowercase())
            direct
        }
        repo.restoreSession(session)
        repeat(8){assertEquals("https://example.invalid/video.mp4",repo.stream("123").url)}
        assertEquals(8,calls);assertEquals(session,repo.currentSession());assertEquals(identity,DeviceIdentity.deviceId)
    }

    @Test fun resolverAlsoUsesAnonymousPlaybackWithoutClearingAccount() {
        var calls=0
        val repo=repository { request ->
            calls++
            assertNull(request.header("Cookie"));assertNull(request.header("X-Tt-Token"))
            if(request.url.encodedPath=="/resolve") {
                """{"video_info":{"data":{"video_list":{"v720":{"main_url":"https://example.invalid/720.mp4","height":720}}}}}"""
            }else {
                """{"code":0,"data":{"123":{"video_model":{"fallback_api":"https://api5-normal-sinfonlineb.fqnovel.com/resolve"}}}}"""
            }
        }
        repo.restoreSession(session)
        assertEquals("https://example.invalid/720.mp4",repo.stream("123").url)
        assertEquals(2,calls);assertEquals(session,repo.currentSession())
    }
}
