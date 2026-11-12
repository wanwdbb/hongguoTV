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
    }.build(),playbackSession=playbackFixture())
    private fun variant(height: Int,codec: String="h264")=JSONObject().put("main_url","https://example.invalid/$height.mp4")
        .put("video_meta",JSONObject().put("definition","${height}p").put("vheight",height).put("codec_type",codec).put("vtype","mp4"))
    private fun model(list: JSONArray)=JSONObject().put("code",0).put("data",JSONObject().put("123",JSONObject()
        .put("video_model",JSONObject().put("status",10).put("video_list",list).toString()))).toString()
    @Test fun har4ArrayModelPlaysWithoutFallbackRequest() {
        var calls=0
        val repo=repository { request ->
            calls++
            assertEquals("/reading/bookapi/video_tab/multi_video_model/v1/",request.url.encodedPath)
            assertEquals("73932",request.url.queryParameter("version_code"))
            assertEquals("GET",request.method)
            assertNull(request.header("Content-Encoding"))
            assertNull(request.url.queryParameter("dr_scene"))
            assertEquals("123",request.url.queryParameter("video_id"))
            model(JSONArray().put(variant(720,"bytevc2")).put(variant(1080,"bytevc1")))
        }
        assertEquals("1080P（兼容资源）",repo.stream("123",720).quality)
        assertEquals(1,calls)
    }
    @Test fun plainOrBase64UrlWithSeedDoesNotUseSpadeDecryption() {
        for(url in listOf("https://example.invalid/a.mp4",java.util.Base64.getEncoder().encodeToString("https://example.invalid/a.mp4".toByteArray()))) {
            val row=JSONObject().put("main_url",url).put("height",720)
            val reply=JSONObject(model(JSONArray().put(row)))
            val entry=reply.getJSONObject("data").getJSONObject("123")
            val model=JSONObject(entry.getString("video_model")).put("key_seed","c2VlZA==")
            entry.put("video_model",model)
            assertEquals("https://example.invalid/a.mp4",repository { reply.toString() }.stream("123").url)
        }
    }
    @Test fun fullHdTopLevelDimensionsAndBackupUrlAreSelected() {
        val full=JSONObject().put("vwidth",1920).put("vheight",1080).put("main_url","broken")
            .put("backup_url_1","https://example.invalid/full.mp4").put("video_meta",JSONObject().put("codec_type","h264"))
        val reply=model(JSONArray().put(variant(720)).put(full))
        assertEquals("1080P",repository { reply }.stream("123",1080).quality)
        assertEquals("https://example.invalid/full.mp4",repository { reply }.stream("123",1080).url)
        assertEquals("720P",repository { reply }.stream("123",720).quality)
    }
    @Test fun invalidFullHdVariantDoesNotDiscardValidFullHd() {
        val bad=variant(1080).put("encrypt_info",JSONObject().put("encrypt",true).put("spade_a","invalid"))
        val reply=model(JSONArray().put(bad).put(variant(1080)).put(variant(720)))
        assertEquals("1080P",repository { reply }.stream("123").quality)
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
    @Test fun emptyModelAllowsOnlyOneExtraRecoveryProbe() {
        val paths=mutableListOf<String>()
        val repo=repository { request -> paths+=request.url.encodedPath; "" }
        assertThrows(IOException::class.java) { repo.stream("123") }
        assertEquals(5,paths.count { it.contains("multi_video_model") })
        assertTrue(paths.size>5) // Foreground compatibility registration has its own bounded budget.
        assertEquals("/reading/bookapi/video_tab/multi_video_model/v1/",paths.first())
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
