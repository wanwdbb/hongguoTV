// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import java.io.Closeable
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

/** 在后台接管同一集的在途准备；取消或超时清理资源，成功后传输归播放器所有。 */
class PreparedClaim<T: Closeable>(private val slot: PreparedSlot<T>,private val key: String,
    private val job: Future<*>,private val scope: RequestScope,private val dispose: ()->Unit): Closeable {
    private var closed=false
    private var transferred=false
    fun await(timeoutMs: Long=2000): T? {
        try { job.get(timeoutMs,TimeUnit.MILLISECONDS) }
        catch(error: InterruptedException) { close(); Thread.currentThread().interrupt(); throw java.io.InterruptedIOException("预加载接续已取消") }
        catch(_: Exception) { close(); return null }
        synchronized(this) {
            if(closed) return null
            val result=slot.take(key)
            if(result!=null) { transferred=true; return result }
        }
        close(); return null
    }
    @Synchronized override fun close() {
        if(closed || transferred) return
        closed=true; scope.cancel(); job.cancel(true); slot.clear(); dispose()
    }
}
