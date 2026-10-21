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

/**
 * 官方弹幕里的表情是 `[偷笑]` 这样的占位符。电视盒子普遍没有彩色 emoji 字体，
 * 直接画 emoji 会在部分设备（以及 MuMu 模拟器）上触发字体图集花屏，所以这里只去掉
 * 方括号保留可读文字；未收录的方括号内容原样保留，避免误伤普通文本。
 */
private val DANMAKU_FACES = setOf(
    "偷笑", "奸笑", "捂脸", "大笑", "微笑", "笑哭", "呲牙", "赞", "鼓掌",
    "比心", "爱心", "玫瑰", "狗头", "害羞", "流泪", "大哭", "我想静静",
    "耶", "中国赞", "握手", "抱拳", "作揖", "666", "绝", "疑问", "晕",
    "惊呆", "酷", "白眼", "哼", "灵机一动", "思考", "委屈", "发怒",
    "惊恐", "睡", "可爱", "色", "机智", "点亮", "打call",
    "爱慕", "吃瓜", "看", "加油", "汗", "天啊", "打脸", "摸头", "送心", "加一",
    "嘿哈", "皱眉", "哦", "哇", "强", "弱", "胜利", "拥抱", "亲亲", "可怜",
    "坏笑", "调皮", "发呆", "惊讶", "难过", "抓狂", "吐", "愉快", "困", "流汗",
    "憨笑", "悠闲", "奋斗", "咒骂", "嘘", "疯了", "衰", "骷髅", "敲打", "再见",
    "擦汗", "糗大了", "左哼哼", "右哼哼", "哈欠", "鄙视", "快哭了", "阴险", "吓",
    "菜刀", "西瓜", "啤酒", "篮球", "乒乓", "咖啡", "饭", "猪头", "凋谢", "示爱",
    "心碎", "蛋糕", "闪电", "炸弹", "刀", "足球", "瓢虫", "便便", "月亮", "太阳",
    "礼物", "勾引", "拳头", "差劲", "爱你", "NO", "OK", "爱情", "飞吻", "跳跳",
    "发抖", "怄火", "转圈", "磕头", "回头", "跳绳", "挥手", "激动", "街舞", "献吻",
    "左太极", "右太极", "红包", "發", "福", "鸡", "庆祝", "烟花", "爆竹"
)
private val DANMAKU_FACE_TOKEN = Regex("\\[([^\\]]{1,8})\\]")

internal fun danmakuFaceText(value: String): String =
    DANMAKU_FACE_TOKEN.replace(value) { match -> match.groupValues[1].takeIf(DANMAKU_FACES::contains) ?: match.value }
