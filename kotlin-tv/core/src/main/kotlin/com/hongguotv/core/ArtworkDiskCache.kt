// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import java.io.File
import java.security.MessageDigest

class ArtworkDiskCache(private val directory: File,private val clock: () -> Long = System::currentTimeMillis) {
    private val expiry=7*24*60*60*1000L
    private var lastTrim=Long.MIN_VALUE
    private var writes=0
    private fun file(url: String)=File(directory,MessageDigest.getInstance("SHA-256").digest(url.toByteArray()).joinToString("") { "%02x".format(it) })
    @Synchronized fun get(url: String): ByteArray? = runCatching {
        val f=file(url); val age=clock()-f.lastModified()
        if(!f.isFile) null
        else if(f.length() !in 1..4*1024*1024 || age !in 0..expiry) { f.delete(); null }
        else f.readBytes().also { if(age>=60_000) f.setLastModified(clock()) }
    }.getOrNull()
    @Synchronized fun remove(url: String) { file(url).delete() }
    @Synchronized fun put(url: String,bytes: ByteArray) {
        if(bytes.isEmpty() || bytes.size>4*1024*1024) return
        runCatching {
            directory.mkdirs(); val dest=file(url); val temp=File(directory,dest.name+".tmp")
            try { temp.writeBytes(bytes); java.nio.file.Files.move(temp.toPath(),dest.toPath(),java.nio.file.StandardCopyOption.REPLACE_EXISTING); dest.setLastModified(clock()) }
            finally { temp.delete() }
            writes++
            // Bounded slack: at most seven new files between full directory scans.
            if(lastTrim==Long.MIN_VALUE || writes>=8 || clock()-lastTrim>=60_000) trim()
        }
    }
    private fun trim() {
        var size=0L; var count=0
        directory.listFiles().orEmpty().sortedByDescending { it.lastModified() }.forEach { f ->
            if(f.name.endsWith(".tmp") || clock()-f.lastModified() !in 0..expiry) f.delete()
            else { size+=f.length(); count++; if(size>24*1024*1024 || count>100) f.delete() }
        }
        lastTrim=clock(); writes=0
    }
}
