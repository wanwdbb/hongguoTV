// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.os.Handler
import com.hongguotv.core.RequestScope
import java.util.concurrent.ExecutorService
import java.util.concurrent.Future
import java.util.concurrent.ThreadPoolExecutor

internal class PageWork(private val worker: ExecutorService,private val main: Handler,
                        private val active: () -> Boolean,private val foreground: () -> Boolean) {
    private data class Deferred(val apply: () -> Unit,val discard: () -> Unit={})
    private var revision=0
    private var scope: RequestScope?=null
    private var job: Future<*>?=null
    private var deferred: Deferred?=null
    fun cancel() {
        revision++; deferred?.discard?.invoke(); deferred=null
        scope?.cancel(); scope=null; job?.cancel(true); job=null
        (worker as? ThreadPoolExecutor)?.purge()
    }
    fun resume() { val next=deferred; deferred=null; next?.apply?.invoke() }
    fun <T> submit(action: () -> T,done: (T) -> Unit,failed: (Throwable) -> Unit,discard: (T) -> Unit) {
        cancel(); val ticket=revision; val owner=RequestScope(); scope=owner
        job=worker.submit {
            try {
                val result=owner.run(action)
                main.post {
                    if(!active() || ticket!=revision) discard(result)
                    else if(foreground()) done(result)
                    else { deferred?.discard?.invoke(); deferred=Deferred({ done(result) },{ discard(result) }) }
                }
            } catch(problem: Exception) {
                main.post {
                    if(active() && ticket==revision) {
                        if(foreground()) failed(problem) else deferred=Deferred({ failed(problem) })
                    }
                }
            }
        }
    }
}
