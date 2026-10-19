// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import java.io.Closeable

/**
 * 少量预取结果的按键缓存：命中即移交所有权，过期、超出容量或清空时立即关闭。
 * 推荐连播用它同时留住前一部和后一部的详情与首段视频。
 */
class PreparedCache<T: Closeable>(private val capacity: Int,private val clock: ()->Long,private val ttl: Long=120_000) {
    private class Entry<T>(val value: T,val readyAt: Long)
    private val entries=linkedMapOf<String,Entry<T>>()
    init { require(capacity>0) { "预取缓存容量至少为 1" } }
    @Synchronized fun put(key: String,value: T) {
        entries.remove(key)?.value?.close()
        entries[key]=Entry(value,clock())
        val oldest=entries.entries.iterator()
        while(entries.size>capacity) { val entry=oldest.next(); entry.value.value.close(); oldest.remove() }
    }
    @Synchronized fun fresh(key: String)=entries[key]?.let { clock()-it.readyAt in 0..ttl }==true
    /** 取用即转移所有权；过期结果直接关闭，不交给播放器。 */
    @Synchronized fun take(key: String): T? {
        val entry=entries.remove(key) ?: return null
        if(clock()-entry.readyAt in 0..ttl) return entry.value
        entry.value.close(); return null
    }
    @Synchronized fun clear() { entries.values.forEach { it.value.close() }; entries.clear() }
}
