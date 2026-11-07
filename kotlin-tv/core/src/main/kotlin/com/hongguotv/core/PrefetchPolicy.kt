// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

object PrefetchPolicy {
    /** 稳定播放后才投机准备；片尾不足五秒时不再要求不可能达到的五秒缓冲。 */
    fun buffered(position: Long,duration: Long,buffered: Long,stableMs: Long): Boolean =
        stableMs>=1500 && position>=0 && duration>position && buffered-position>=minOf(5000L,duration-position)
    /** Wall-clock time to the end matters: long episodes must not outlive the slot TTL. */
    fun nearEnd(position: Long, duration: Long, speed: Float): Boolean {
        if (duration <= 0 || position < 0 || position >= duration || !speed.isFinite() || speed <= 0) return false
        return (duration - position) / speed <= 30_000f
    }
}
