package com.hongguotv.core

import org.junit.Assert.*
import org.junit.Test

class FurthestProgressTest {
    private val series=Series("123","fixture",bookType=2)
    private fun state()=LibraryIndex(BackupData(emptyList(),listOf(
        BackupProgress(series,"local",4,30_000,60_000,false,5_000)
    ),emptySet(),emptyList(),BackupSettings()))
    @Test fun olderCloudFurtherEpisodeWins() {
        val state=state()
        assertEquals(1,state.mergeRemote(listOf(AccountHistoryItem(series,"remote",5,1000,60_000,1000))).size)
        assertEquals(5,state.progress("123")!!.episodeIndex)
    }
    @Test fun olderCloudFurtherPositionWinsWithinEpisode() {
        val state=state()
        assertEquals(1,state.mergeRemote(listOf(AccountHistoryItem(series,"remote",4,40_000,60_000,1000))).size)
        assertEquals(40_000L,state.progress("123")!!.position)
    }
    @Test fun newerCloudShorterPositionCannotRollBack() {
        val state=state()
        assertTrue(state.mergeRemote(listOf(AccountHistoryItem(series,"remote",4,10_000,60_000,10_000))).isEmpty())
        assertEquals(30_000L,state.progress("123")!!.position)
    }
    @Test fun episodeTakesPrecedenceOverPosition() {
        assertTrue(WatchProgressOrder.compare(5,1000,4,900_000)>0)
        assertTrue(WatchProgressOrder.compare(3,900_000,4,1000)<0)
    }
}
