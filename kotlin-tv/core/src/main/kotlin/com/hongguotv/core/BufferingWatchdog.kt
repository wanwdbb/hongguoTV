package com.hongguotv.core

/** Monotonic foreground-only buffering deadline. Gaps represent background/suspended polling. */
class BufferingWatchdog(private val timeoutMs: Long=30_000) {
    private var since: Long?=null
    private var lastPoll: Long?=null
    fun reset() { since=null; lastPoll=null }
    fun poll(now: Long,buffering: Boolean): Boolean {
        if(!buffering) { reset(); return false }
        val previous=lastPoll
        if(previous==null || now<previous || now-previous>3_000) since=now
        lastPoll=now
        val start=since ?: now.also { since=it }
        if(now-start<timeoutMs) return false
        reset()
        return true
    }
}
