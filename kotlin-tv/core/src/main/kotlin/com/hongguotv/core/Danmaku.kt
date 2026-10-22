// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

/** 一条弹幕，[timeMs] 是相对本集起点的毫秒数。 */
data class DanmakuComment(val id: String, val text: String, val timeMs: Long)

/** 弹幕接口一次返回一个时间段 `[startMs, nextMs)`，[total] 是本集弹幕总数（接口没给时为 0）。 */
data class DanmakuPage(val items: List<DanmakuComment>, val startMs: Long, val nextMs: Long, val total: Int) {
    companion object {
        /** 3 秒内同一句话反复刷屏时合并成一条，并标出次数。 */
        const val MERGE_WINDOW = 3_000L
        private const val REPEAT = "  ♥ "
        fun merge(items: List<DanmakuComment>): List<DanmakuComment> {
            val sorted = items.distinctBy { it.id }.sortedWith(compareBy({ it.timeMs }, { it.id }))
            val merged = ArrayList<DanmakuComment>()
            val latest = HashMap<String, Int>()
            val counts = HashMap<String, Int>()
            for (item in sorted) {
                val text = item.text.trim()
                if (text.isEmpty()) continue
                val previous = latest[text]
                if (previous != null && item.timeMs - merged[previous].timeMs <= MERGE_WINDOW) {
                    val count = (counts[text] ?: 1) + 1
                    counts[text] = count
                    merged[previous] = merged[previous].copy(text = merged[previous].text.substringBefore(REPEAT) + REPEAT + count)
                    continue
                }
                latest[text] = merged.size
                counts[text] = 1
                merged += item.copy(text = text)
            }
            return merged
        }
    }
}

/**
 * 每集已取过的弹幕段缓存。按 [DanmakuPage.startMs] 去重，超过 [capacity] 段时丢最早的一段。
 * 只保存 30 秒窗口的少量评论，容量很小；换集时由调用方 [clear]。
 */
class DanmakuCache(private val capacity: Int = 20) {
    private val pages = LinkedHashMap<Long, DanmakuPage>()
    init { require(capacity > 0) { "缓存容量必须大于 0" } }

    @Synchronized fun get(startMs: Long): DanmakuPage? = pages[startMs]

    @Synchronized fun put(page: DanmakuPage) {
        pages[page.startMs] = page
        while (pages.size > capacity) pages.remove(pages.keys.first())
    }

    @Synchronized fun clear() = pages.clear()

    @Synchronized fun size(): Int = pages.size
}
