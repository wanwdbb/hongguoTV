package com.hongguotv.core
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test

class LegacyPlaybackContractTest {
    private val direct="""{"code":0,"data":{"123":{"video_model":{"video_list":[{"main_url":"https://example.invalid/video.mp4","height":720}]}}}}"""
    @Test fun registeredPlaybackCarriesOnlyAnonymousCookieAndKeepsAccount() {
        val account=AuthSession(mapOf("sessionid" to "private-account"),"private-token",AuthUser("u","fixture"))
        var calls=0
        val client=OkHttpClient.Builder().addInterceptor { chain ->
            val r=chain.request(); calls++
            assertEquals("73932",r.url.queryParameter("version_code"))
            assertEquals("333",r.url.queryParameter("device_id"))
            assertEquals("444",r.url.queryParameter("iid"))
            assertNull(r.url.queryParameter("ts"))
            assertNull(r.header("X-Tt-Token"))
            assertEquals("install_id=444; ttreq=fixture",r.header("Cookie"))
            assertNotNull(r.header("x-medusa")); assertNotNull(r.header("x-gorgon"))
            Response.Builder().request(r).protocol(Protocol.HTTP_1_1).code(200).message("OK").body(direct.toResponseBody()).build()
        }.build()
        val repo=ContentRepository(client,playbackSession=playbackFixture())
        repo.restoreSession(account)
        repeat(3) { assertEquals("https://example.invalid/video.mp4",repo.stream("123").url) }
        assertEquals(3,calls); assertEquals(account,repo.currentSession())
    }
    @Test fun emptyGetFallsBackToPostWithBodyDigest() {
        val methods=mutableListOf<String>()
        val client=OkHttpClient.Builder().addInterceptor { chain ->
            val r=chain.request(); methods+=r.method
            val reply=if(r.method=="GET") "" else {
                val bytes=okio.Buffer().also { r.body!!.writeTo(it) }.readByteArray()
                assertEquals(digest("MD5",bytes).hex().uppercase(),r.header("x-ss-stub"))
                assertEquals(1,org.json.JSONObject(String(bytes)).getJSONObject("biz_param").getInt("device_level"))
                direct
            }
            Response.Builder().request(r).protocol(Protocol.HTTP_1_1).code(200).message("OK").body(reply.toResponseBody()).build()
        }.build()
        val repo=ContentRepository(client,playbackSession=playbackFixture())
        repo.stream("123"); repo.stream("123")
        assertEquals(listOf("GET","POST","POST"),methods)
    }
}
