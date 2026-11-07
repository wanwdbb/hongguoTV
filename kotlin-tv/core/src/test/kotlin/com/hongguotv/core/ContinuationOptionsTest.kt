package com.hongguotv.core

import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject
import java.io.Closeable
import java.util.concurrent.FutureTask

class ContinuationOptionsTest {
    private class Resource: Closeable { var closed=false; override fun close() { closed=true } }
    @Test fun oldSettingsAndBackupsKeepContinuationDefaults() {
        val original=BackupData(emptyList(),emptyList(),emptySet(),emptyList(),BackupSettings())
        val json=JSONObject(LibraryBackup.encode(original))
        json.getJSONObject("settings").remove("autoSeason"); json.getJSONObject("settings").remove("prefetchNext")
        assertTrue(LibraryBackup.decode(json.toString()).settings.autoSeason)
        assertTrue(LibraryBackup.decode(json.toString()).settings.prefetchNext)
        assertTrue(LibraryDisk.decode(emptyMap<String,Any>()).settings.autoSeason)
    }
    @Test fun switchesRoundTripAndRespectRestoreSettingsChoice() {
        val local=BackupData(emptyList(),emptyList(),emptySet(),emptyList(),BackupSettings())
        val incoming=local.copy(settings=BackupSettings(autoNext=false,autoSeason=false,prefetchNext=false))
        val decoded=LibraryBackup.decode(LibraryBackup.encode(incoming))
        assertEquals(incoming,decoded)
        assertEquals(local.settings,LibraryBackup.merge(local,decoded,false).settings)
        assertEquals(incoming.settings,LibraryBackup.merge(local,decoded,true).settings)
        val loaded=LibraryDisk.decode(mapOf("autoNext" to false,"autoSeason" to false,"prefetchNext" to false))
        assertFalse(loaded.settings.autoNext); assertFalse(loaded.settings.autoSeason); assertFalse(loaded.settings.prefetchNext)
    }
    @Test fun malformedBackupSwitchRejected() {
        val local=BackupData(emptyList(),emptyList(),emptySet(),emptyList(),BackupSettings())
        for(key in listOf("autoSeason","prefetchNext")) {
            val json=JSONObject(LibraryBackup.encode(local)); json.getJSONObject("settings").put(key,"false")
            assertThrows(IllegalArgumentException::class.java) { LibraryBackup.decode(json.toString()) }
        }
    }
    @Test fun pendingPreparationIsTransferredWithoutDuplicateWork() {
        val slot=PreparedSlot<Resource>({0}); val ticket=slot.begin("next"); val result=Resource(); var disposed=0
        val job=FutureTask { slot.complete(ticket,"next",result) }
        val claim=PreparedClaim(slot,"next",job,RequestScope()) { disposed++ }
        assertTrue(slot.matches("next")); assertFalse(slot.isFresh("next")); job.run()
        assertSame(result,claim.await()); claim.close(); assertFalse(result.closed); assertEquals(0,disposed)
    }
    @Test fun cancelledClaimRejectsLateCompletionAndDisposesOnlyOnce() {
        val slot=PreparedSlot<Resource>({0}); val ticket=slot.begin("next"); val result=Resource(); var disposed=0
        val job=FutureTask { true }; val claim=PreparedClaim(slot,"next",job,RequestScope()) { disposed++ }
        claim.close(); claim.close()
        assertFalse(slot.complete(ticket,"next",result)); assertTrue(result.closed); assertEquals(1,disposed)
        assertNull(claim.await())
    }
    @Test fun timeoutClosesPendingTransportAndExpiredResultIsNotUsed() {
        var now=0L; val slot=PreparedSlot<Resource>({now}); val ticket=slot.begin("next"); var disposed=0
        val pending=FutureTask { true }; val claim=PreparedClaim(slot,"next",pending,RequestScope()) { disposed++ }
        assertNull(claim.await(1)); assertTrue(pending.isCancelled); assertEquals(1,disposed)
        val result=Resource(); val t=slot.begin("next"); slot.complete(t,"next",result); now=120001
        val done=FutureTask { true }; done.run()
        assertNull(PreparedClaim(slot,"next",done,RequestScope()) { disposed++ }.await())
        assertTrue(result.closed); assertEquals(2,disposed)
    }
    @Test fun prefetchStartsWithFullyBufferedShortTailButNotBeforeStablePlayback() {
        assertTrue(PrefetchPolicy.buffered(98000,100000,100000,1500))
        assertFalse(PrefetchPolicy.buffered(98000,100000,99999,1500))
        assertFalse(PrefetchPolicy.buffered(90000,100000,100000,1499))
        assertTrue(PrefetchPolicy.buffered(90000,100000,95000,1500))
    }
}
