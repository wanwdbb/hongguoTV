package com.hongguotv.core

import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class PlaybackProgressTest {
    private val session=AuthSession(mapOf("sessionid" to "fixture"),"fixture-token",AuthUser("a","fixture"))
    private fun row()=HistoryCheckpoint(BackupProgress(Series("12345678901234567890","fixture",bookType=2),"456",12,126576,180000,false,1700000000000),2,20)
    @Test fun writesBothIndependentStoresAndUsesCapturedProgressSchema() {
        val paths=mutableListOf<String>()
        val repo=ContentRepository(OkHttpClient.Builder().addInterceptor { chain ->
            val request=chain.request(); paths+=request.url.encodedPath
            assertEquals("sessionid=fixture",request.header("Cookie"))
            val buffer=Buffer();request.body!!.writeTo(buffer);val body=JSONObject(buffer.readUtf8())
            if(request.url.encodedPath.contains("read_progress")) {
                val value=body.getJSONArray("books").getJSONObject(0)
                assertEquals("12345678901234567890",value.getString("book_id"))
                assertEquals("456",value.getString("item_id"))
                assertEquals(126576L,value.getLong("current_play_time"))
                assertEquals(0,value.getInt("vid_index")) // captured upload schema: server resolves item_id
            } else assertEquals(13,body.getJSONArray("update_datas").getJSONObject(0).getInt("vid_index"))
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK").body("{\"code\":0}".toResponseBody()).build()
        }.build());repo.restoreSession(session)
        repo.uploadPlaybackCheckpoint(session,listOf(row()))
        assertEquals(2,paths.size);assertTrue(paths[0].contains("read_history"));assertTrue(paths[1].contains("read_progress"))
    }
    @Test fun historySuccessDoesNotAcknowledgeFailedResumeWrite() {
        val repo=ContentRepository(OkHttpClient.Builder().addInterceptor { chain ->
            val body=if(chain.request().url.encodedPath.contains("read_progress")) "{}" else "{\"code\":0}"
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK").body(body.toResponseBody()).build()
        }.build());repo.restoreSession(session)
        assertThrows(IOException::class.java) { repo.uploadPlaybackCheckpoint(session,listOf(row())) }
    }
    @Test fun readsSeparateResumeStoreAndMilliseconds() {
        val repo=ContentRepository(OkHttpClient.Builder().addInterceptor { chain ->
            assertEquals("2,3,4",chain.request().url.queryParameter("book_types"))
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("{\"code\":0,\"video_data\":[{\"series_id\":\"123\",\"vid\":\"456\",\"vid_index\":13,\"current_play_time\":126576,\"last_watch_time\":1700000000000}]}".toResponseBody()).build()
        }.build());repo.restoreSession(session)
        val row=repo.accountVideoProgress().single()
        assertEquals(12,row.episodeIndex);assertEquals(126576L,row.position);assertEquals("456",row.episodeId)
    }
}
