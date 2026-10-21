// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import org.junit.Assert.assertEquals
import org.junit.Test

class DanmakuTest {
    private fun item(id: String,text: String,time: Long)=DanmakuComment(id,text,time)

    @Test fun mergeKeepsTimeOrderAndDropsDuplicateIds() {
        val merged=DanmakuPage.merge(listOf(item("b","第二条",2_000),item("a","第一条",1_000),item("a","第一条",1_000)))
        assertEquals(listOf("第一条","第二条"),merged.map { it.text })
        assertEquals(listOf(1_000L,2_000L),merged.map { it.timeMs })
    }

    @Test fun mergeCollapsesRepeatsWithinWindow() {
        val merged=DanmakuPage.merge(listOf(item("1","好笑",1_000),item("2","好笑",1_500),item("3","好笑",2_800)))
        assertEquals(1,merged.size)
        assertEquals("好笑  ♥ 3",merged.single().text)
        assertEquals(1_000L,merged.single().timeMs)
    }

    @Test fun mergeKeepsRepeatsOutsideWindow() {
        val merged=DanmakuPage.merge(listOf(item("1","好笑",1_000),item("2","好笑",9_000)))
        assertEquals(listOf("好笑","好笑"),merged.map { it.text })
    }

    @Test fun mergeTrimsAndDropsBlank() {
        val merged=DanmakuPage.merge(listOf(item("1","  ",1_000),item("2"," 来了 ",2_000)))
        assertEquals(listOf("来了"),merged.map { it.text })
    }

    @Test fun mergeWindowIsThreeSeconds() {
        assertEquals(3_000L,DanmakuPage.MERGE_WINDOW)
        val merged=DanmakuPage.merge(listOf(item("1","哈",0),item("2","哈",3_000)))
        assertEquals("哈  ♥ 2",merged.single().text)
    }

    @Test fun facePlaceholdersBecomeReadableText() {
        assertEquals("这也太偷笑了",danmakuFaceText("这也太[偷笑]了"))
        assertEquals("赞中国赞",danmakuFaceText("[赞][中国赞]"))
        // 未收录的方括号内容保持原样，普通括号文本也不能被误改。
        assertEquals("[未知表情]",danmakuFaceText("[未知表情]"))
        assertEquals("【偷笑】",danmakuFaceText("【偷笑】"))
    }

    @Test fun cacheKeepsOnlyLatestSegments() {
        val cache=DanmakuCache(capacity=2)
        cache.put(DanmakuPage(emptyList(),0,30_000,0))
        cache.put(DanmakuPage(emptyList(),30_000,60_000,0))
        cache.put(DanmakuPage(emptyList(),60_000,90_000,0))
        assertEquals(2,cache.size())
        assertEquals(null,cache.get(0))
        assertEquals(30_000L,cache.get(30_000)?.startMs)
        assertEquals(60_000L,cache.get(60_000)?.startMs)
        cache.clear()
        assertEquals(0,cache.size())
    }
}
