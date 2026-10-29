package com.hongguotv.core

import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class Har4CompatibilityTest {
    private fun repository(reply: (Request)->String)=ContentRepository(OkHttpClient.Builder().addInterceptor { chain ->
        Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
            .body(reply(chain.request()).toResponseBody()).build()
    }.build())
    private fun variant(height: Int,codec: String="h264")=JSONObject().put("main_url","https://example.invalid/$height.mp4")
        .put("video_meta",JSONObject().put("definition","${height}p").put("vheight",height).put("codec_type",codec).put("vtype","mp4"))
    private fun model(list: JSONArray)=JSONObject().put("code",0).put("data",JSONObject().put("123",JSONObject()
        .put("video_model",JSONObject().put("status",10).put("video_list",list).toString()))).toString()
    @Test fun har4ArrayModelPlaysWithoutFallbackRequest() {
        var calls=0
        val repo=repository { request ->
            calls++
            assertEquals("/novel/player/multi_video_model/preload/v1/",request.url.encodedPath)
            assertEquals("71332",request.url.queryParameter("version_code"))
            val buffer=okio.Buffer();request.body!!.writeTo(buffer);val body=JSONObject(buffer.readUtf8())
            assertNull(request.header("Content-Encoding"))
            assertFalse(body.has("dr_scene"))
            assertEquals("123",body.getJSONObject("mixed_video_id_map").getJSONArray("1004").getString(0))
            model(JSONArray().put(variant(720,"bytevc2")).put(variant(1080,"bytevc1")))
        }
        assertEquals("1080P（兼容资源）",repo.stream("123",720).quality)
        assertEquals(1,calls)
    }
    @Test fun nestedMetadataHonoursQualityCap() {
        val repo=repository { model(JSONArray().put(variant(1080)).put(variant(480)).put(variant(720))) }
        assertEquals("https://example.invalid/720.mp4",repo.stream("123",720).url)
    }
    @Test fun batchCannotSubstituteNeighbour() {
        val repo=repository { model(JSONArray().put(variant(720))).replace("\"123\"","\"456\"") }
        assertThrows(IOException::class.java) { repo.stream("123") }
    }
    @Test fun emptyPlayerResponseHasRecoverableIoError() {
        assertThrows(IOException::class.java) { repository { "" }.stream("123") }
    }
    @Test fun emptyPreloadDoesNotTryNewProtocolsOrRegisterAnotherIdentity() {
        val paths=mutableListOf<String>()
        val repo=repository { request -> paths+=request.url.encodedPath; "" }
        assertThrows(IOException::class.java) { repo.stream("123") }
        assertEquals(listOf("/novel/player/multi_video_model/preload/v1/"),paths)
    }
    @Test fun encryptedVariantWithoutKeyFailsBeforePlayback() {
        val item=variant(720).put("encrypt_info",JSONObject().put("encrypt",true).put("encryption_method","cenc-aes-ctr"))
        assertThrows(IOException::class.java) { repository { model(JSONArray().put(item)) }.stream("123") }
    }
    @Test fun officialDetailFallbackSortsOneBasedEpisodesAndChecksIdentity() {
        val paths=mutableListOf<String>()
        val repo=repository { request ->
            paths+=request.url.encodedPath
            if(request.url.host=="hongguoduanju.com") throw IOException("web unavailable")
            assertEquals("/novel/player/video_detail/v1/",request.url.encodedPath)
            """{"code":0,"data":{"video_data":{"series_id_str":"123","series_title":"fixture","episode_cnt":2,"video_list":[{"vid":"789","vid_index":2},{"vid":"456","vid_index":1}]}}}"""
        }
        assertEquals(listOf("456","789"),repo.detail("123").episodes)
        assertEquals(2,paths.size)
    }
    @Test fun wrongSeriesDetailIsRejected() {
        val repo=repository { request ->
            if(request.url.host=="hongguoduanju.com") throw IOException("web unavailable")
            """{"code":0,"data":{"video_data":{"series_id_str":"999","series_title":"fixture","video_list":[{"vid":"456","vid_index":1}]}}}"""
        }
        assertThrows(IOException::class.java) { repo.detail("123") }
    }
    @Test fun capturedModelShapesReplayWithSanitizedValues() {
        val resource=javaClass.getResourceAsStream("/har4-model-shapes.json")!!.bufferedReader().use { it.readText() }
        val models=JSONArray(resource)
        for(index in 0 until models.length()) {
            val data=JSONObject().put("code",0).put("data",JSONObject().put("123",JSONObject().put("video_model",models.getJSONObject(index))))
            val result=repository { data.toString() }.stream("123")
            assertTrue(result.url.startsWith("https://example.invalid/"))
        }
    }
    @Test fun accountShelfObjectWrapperDoesNotDiscardNestedRows() {
        val repo=repository {
            """{"code":0,"data":{"video_shelf_info":{"items":[{"series_id":"123","series_title":"fixture"}]}}}"""
        }
        repo.restoreSession(AuthSession(mapOf("sessionid" to "fixture"),"fixture",AuthUser("fixture","fixture",accountId="123")))
        assertEquals("123",repo.accountFavorites().items.single().id)
    }
}
