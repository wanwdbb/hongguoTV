// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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

    @Test fun facePlaceholdersBecomeEmojiImages() {
        val parts=danmakuParts("这也太[偷笑]了")
        assertEquals("这也太",parts[0].text)
        assertEquals("🤭",parts[1].text)
        assertEquals("1f92d.png",parts[1].emojiAsset)
        assertEquals("[偷笑]",parts[1].fallback)
        assertEquals("了",parts[2].text)
    }

    @Test fun referenceNamesMapToGlyphs() {
        assertEquals("🤭",danmakuDisplayText("[偷笑]"))
        assertEquals("👍👍",danmakuDisplayText("[赞][点赞]"))
        assertEquals("🤭",danmakuDisplayText("【捂嘴笑】"))
        assertEquals("🤭",danmakuDisplayText("［偷笑］"))
        assertEquals("😮‍💨",danmakuDisplayText("[叹气]"))
    }

    @Test fun unknownOrUnclosedPlaceholdersStayLiteral() {
        assertEquals("[中国赞]",danmakuDisplayText("[中国赞]"))
        assertEquals("[未知表情]",danmakuDisplayText("[未知表情]"))
        assertEquals("[偷笑",danmakuDisplayText("[偷笑"))
    }

    @Test fun compoundExpressionsUseMultipleImages() {
        val parts=danmakuParts("[吐舌小狗]")
        assertEquals(listOf("1f436.png","1f61b.png"),parts.mapNotNull { it.emojiAsset })
        assertEquals(listOf("[吐舌小狗]","[吐舌小狗]"),parts.map { it.fallback })
    }

    @Test fun directEmojiAlsoUsesImage() {
        val parts=danmakuParts("好😁")
        assertEquals("好",parts[0].text)
        assertEquals("1f601.png",parts[1].emojiAsset)
        assertEquals("😁",parts[1].text)
    }

    @Test fun expressionNamesAreCaseInsensitive() {
        assertEquals("👌",danmakuDisplayText("[OK]"))
        assertEquals("👌",danmakuDisplayText("[ok]"))
    }

    @Test fun everyReferenceNameResolvesToImages() {
        assertEquals(236,DANMAKU_EMOJI_NAMES.size)
        assertEquals(145,DANMAKU_EMOJI_ASSETS.size)
        for(name in DANMAKU_EMOJI_NAMES.keys) {
            val parts=danmakuParts("[$name]")
            assertTrue("表情没有对应图片: $name",parts.isNotEmpty() && parts.all { it.emojiAsset!=null })
        }
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
