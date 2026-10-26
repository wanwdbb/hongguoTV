package com.hongguotv.core

import org.junit.Assert.*
import org.junit.Test
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody

class CloudHistoryMergeTest {
    private val series=Series("123","fixture",bookType=2)
    private fun local()=BackupProgress(series,"456",0,1000,60000,false,1000)
    private fun index()=LibraryIndex(BackupData(emptyList(),listOf(local()),emptySet(),emptyList(),BackupSettings()))
    @Test fun newerCloudMovesLocalAndResumeForwardWithoutRestamping() {
        val state=index()
        val changed=state.mergeRemote(listOf(AccountHistoryItem(series,"789",12,126576,180000,2000)))
        assertEquals(1,changed.size)
        val current=state.progress("123")!!
        assertEquals(2000L,current.updatedAt);assertEquals(126576L,current.position)
        assertEquals(ResumeTarget(1,126576),ResumePlayback.target(listOf("456","789"),current.episodeId,current.position,current.completed))
        assertEquals(current,LibraryDisk.decode(mapOf("progress" to LibraryDisk.history(state.history()))).progress("123"))
    }
    @Test fun newerCloudRewatchCannotOverwriteFartherLocal() {
        val state=index()
        state.save(local().copy(episodeId="789",episodeIndex=12,updatedAt=3000))
        assertTrue(state.mergeRemote(listOf(AccountHistoryItem(series,"456",0,900,0,2000))).isEmpty())
        assertEquals("789",state.progress("123")!!.episodeId)
        state.mergeRemote(listOf(AccountHistoryItem(series,"456",0,500,0,4000)))
        assertEquals("789",state.progress("123")!!.episodeId)
        assertEquals(1000L,state.progress("123")!!.position)
    }
    @Test fun identicalPositionStillImportsCloudTimestampAndRepeatedPullDoesNothing() {
        val state=index(); val row=AccountHistoryItem(series,"456",0,1000,60000,2000)
        assertEquals(1,state.mergeRemote(listOf(row)).size)
        assertEquals(2000L,state.progress("123")!!.updatedAt)
        assertTrue(state.mergeRemote(listOf(row)).isEmpty())
    }
    @Test fun independentResumeStoreWinsWhenNewerThanHistoryAndKeepsKnownMetadata() {
        val repo=ContentRepository(OkHttpClient.Builder().addInterceptor { chain ->
            val body=if(chain.request().url.encodedPath.contains("read_progress"))
                "{\"code\":0,\"video_data\":[{\"series_id\":\"123\",\"vid\":\"789\",\"vid_index\":13,\"current_play_time\":126576,\"last_watch_time\":2000}]}"
                else "{\"code\":0,\"data\":{\"data_list\":[]}}"
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK").body(body.toResponseBody()).build()
        }.build())
        repo.restoreSession(AuthSession(mapOf("sessionid" to "fixture"),"fixture",AuthUser("a","fixture")))
        val row=repo.accountResumeHistory(known=listOf(series)).history.single()
        assertEquals("fixture",row.series.title);assertEquals("789",row.episodeId);assertEquals(12,row.episodeIndex)
        assertEquals(126576L,row.position)
    }
}
