package com.hongguotv.core

import org.junit.Assert.*
import org.junit.Test

class BufferingWatchdogTest {
    @Test fun persistentBufferingExpiresAtThirtySeconds() {
        val monitor=BufferingWatchdog()
        for(now in 0L until 30_000L step 1_000) assertFalse(monitor.poll(now,true))
        assertTrue(monitor.poll(30_000,true))
        assertFalse(monitor.poll(31_000,true))
    }
    @Test fun readyOrPausedPlaybackClearsDeadline() {
        val monitor=BufferingWatchdog()
        for(now in 0L..25_000L step 1_000) assertFalse(monitor.poll(now,true))
        assertFalse(monitor.poll(26_000,false))
        for(now in 27_000L..56_000L step 1_000) assertFalse(monitor.poll(now,true))
        assertTrue(monitor.poll(57_000,true))
    }
    @Test fun backgroundGapDoesNotConsumeForegroundDeadline() {
        val monitor=BufferingWatchdog()
        for(now in 0L..25_000L step 1_000) assertFalse(monitor.poll(now,true))
        for(now in 100_000L..129_000L step 1_000) assertFalse(monitor.poll(now,true))
        assertTrue(monitor.poll(130_000,true))
    }
    @Test fun explicitEpisodeChangeResetsDeadline() {
        val monitor=BufferingWatchdog()
        for(now in 0L..25_000L step 1_000) assertFalse(monitor.poll(now,true))
        monitor.reset()
        for(now in 26_000L..55_000L step 1_000) assertFalse(monitor.poll(now,true))
        assertTrue(monitor.poll(56_000,true))
    }
}
