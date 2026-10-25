package com.hongguotv.core

import java.nio.file.Files
import java.io.InterruptedIOException
import org.junit.Test
import org.junit.Assert.*

class OptimizationTest {
    @Test fun cancelledScopeDoesNotEnterFallbackWork() {
        val scope=RequestScope()
        assertThrows(InterruptedIOException::class.java) {
            scope.run { scope.cancel(); RequestScope.ensureActive() }
        }
    }
    @Test fun artworkReadsRefreshRecencyAndExpiredFilesAreRemoved() {
        val dir=Files.createTempDirectory("artwork-test").toFile()
        try {
            var now=1_000_000L
            val cache=ArtworkDiskCache(dir) { now }
            cache.put("a",byteArrayOf(1,2)); now+=60_001
            assertArrayEquals(byteArrayOf(1,2),cache.get("a"))
            assertEquals(now,dir.listFiles()!!.single().lastModified())
            now+=8*24*60*60*1000L
            assertNull(cache.get("a")); assertTrue(dir.listFiles()!!.isEmpty())
        } finally { dir.deleteRecursively() }
    }
    @Test fun artworkBatchCleanupBoundsStorageAndRemovalAllowsRefetch() {
        val dir=Files.createTempDirectory("artwork-limit").toFile()
        try {
            var now=1_000_000L; val cache=ArtworkDiskCache(dir) { now++ }
            repeat(33) { cache.put("image-$it",ByteArray(1024*1024)) }
            assertTrue(dir.listFiles()!!.sumOf { it.length() }<=24L*1024*1024)
            assertNotNull(cache.get("image-32")); cache.remove("image-32"); assertNull(cache.get("image-32"))
        } finally { dir.deleteRecursively() }
    }
}
