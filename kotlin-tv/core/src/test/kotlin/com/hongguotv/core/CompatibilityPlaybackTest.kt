package com.hongguotv.core

import org.junit.Test
import org.junit.Assert.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody

class CompatibilityPlaybackTest {
    private fun client(replies: List<String>, calls: MutableList<Request>): OkHttpClient = OkHttpClient.Builder().addInterceptor { chain ->
        calls+=chain.request()
        Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
            .body(replies[minOf(calls.lastIndex,replies.lastIndex)].toResponseBody("application/json".toMediaType())).build()
    }.build()
    private val device=PlayerDevice("123456","654321","test-cdid","test-openudid","")
    @Test fun emptyShortplayRetriesPhoenixWithSignedRequests() {
        val calls=mutableListOf<Request>()
        val raw="""{"code":0,"data":{"42":{"video_model":{"video_list":[{"main_url":"https://example.com/video.mp4"}]}}}}"""
        val data=CompatibilityPlayback(client(listOf("",raw),calls)) { device }.fetch("42")
        assertNotNull(data.getJSONObject("data").getJSONObject("42"))
        assertEquals(2,calls.size)
        assertTrue(calls[0].url.encodedPath.endsWith("/video_model/v1/"))
        assertTrue(calls[1].url.encodedPath.endsWith("/multi_video_model/v1/"))
        calls.forEach { assertNotNull(it.header("x-argus")); assertNotNull(it.header("x-ss-stub")) }
    }
    @Test fun lowerQualityFirstRouteDoesNotHideFullHdSecondRoute() {
        val calls=mutableListOf<Request>()
        fun reply(q: Int)="""{"code":0,"data":{"42":{"video_model":{"video_list":[{"main_url":"https://example.com/$q.mp4","height":$q}]}}}}"""
        val result=CompatibilityPlayback(client(listOf(reply(720),reply(1080)),calls)) { device }.fetch("42") { candidate ->
            val q=candidate.getJSONObject("data").getJSONObject("42").getJSONObject("video_model").getJSONArray("video_list").getJSONObject(0).getInt("height")
            if(q==1080) Int.MAX_VALUE else q
        }
        assertEquals(2,calls.size)
        assertEquals(1080,result.getJSONObject("data").getJSONObject("42").getJSONObject("video_model").getJSONArray("video_list").getJSONObject(0).getInt("height"))
    }
    @Test fun wrongEpisodeIsNeverAccepted() {
        val calls=mutableListOf<Request>()
        val raw="""{"code":0,"data":{"99":{"video_model":{"video_list":[]}}}}"""
        try { CompatibilityPlayback(client(listOf(raw),calls)) { device }.fetch("42"); fail("wrong episode") }
        catch(_: java.io.IOException) { assertEquals(2,calls.size) }
    }
}
