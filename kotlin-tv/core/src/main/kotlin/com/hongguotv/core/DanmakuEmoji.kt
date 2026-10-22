// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

/**
 * 官方弹幕表情表：`[偷笑]` 等占位符先映射成 Unicode 表情，再映射到 Fluent Emoji 图片。
 * 表情名称与图片对应关系取自参考项目 HongguoTV-Phone-TV-NoAds 的
 * `lib/danmaku_text.dart`、`lib/danmaku_emoji_assets.dart`；图片为 Microsoft Fluent Emoji（MIT），
 * 已按弹幕显示尺寸缩放到 64×64，授权与来源见 `app/src/main/assets/danmaku_emoji/`。
 */
internal val DANMAKU_EMOJI_NAMES: Map<String, String> = mapOf(
    "笑" to "😄",
    "哭" to "😢",
    "什么" to "❓",
    "敬礼" to "🫡",
    "偷笑" to "🤭",
    "捂嘴笑" to "🤭",
    "笑哭" to "😂",
    "捂脸" to "🤦",
    "微笑" to "🙂",
    "大笑" to "😆",
    "呲牙" to "😁",
    "尬笑" to "😅",
    "害羞" to "😊",
    "奸笑" to "😏",
    "坏笑" to "😏",
    "赞" to "👍",
    "点赞" to "👍",
    "鼓掌" to "👏",
    "流泪" to "😢",
    "大哭" to "😭",
    "泪奔" to "😭",
    "发怒" to "😠",
    "愤怒" to "😡",
    "惊讶" to "😮",
    "惊呆" to "😲",
    "震惊" to "😱",
    "思考" to "🤔",
    "灵光一闪" to "💡",
    "爱慕" to "😍",
    "色" to "😍",
    "酷" to "😎",
    "酷拽" to "😎",
    "调皮" to "😛",
    "吐舌" to "😛",
    "得意" to "😏",
    "闭嘴" to "🤐",
    "困" to "😪",
    "睡" to "😴",
    "睡觉" to "😴",
    "晕" to "😵",
    "流汗" to "😓",
    "冷汗" to "😰",
    "亲亲" to "😘",
    "飞吻" to "😘",
    "拥抱" to "🤗",
    "握手" to "🤝",
    "祈祷" to "🙏",
    "抱拳" to "🙏",
    "加油" to "💪",
    "耶" to "✌️",
    "胜利" to "✌️",
    "OK" to "👌",
    "爱心" to "❤️",
    "比心" to "❤️",
    "心碎" to "💔",
    "玫瑰" to "🌹",
    "礼物" to "🎁",
    "庆祝" to "🎉",
    "蛋糕" to "🎂",
    "啤酒" to "🍺",
    "咖啡" to "☕️",
    "狗头" to "🐶",
    "爽" to "😆",
    "怒" to "😠",
    "发呆" to "😶",
    "抠鼻" to "👃",
    "舔屏" to "😋",
    "看" to "👀",
    "感谢" to "🙏",
    "抱抱你" to "🤗",
    "尴尬流汗" to "😅",
    "戳手手" to "👉👈",
    "星星眼" to "🤩",
    "杀马特" to "😎",
    "黄脸干杯" to "🍻",
    "抱紧自己" to "🤗",
    "拜拜" to "👋",
    "热化了" to "🥵",
    "黄脸祈祷" to "🙏",
    "懵" to "😵",
    "举手" to "🙋",
    "加功德" to "🙏",
    "摊手" to "🤷",
    "无语流汗" to "😓",
    "续火花吧" to "🔥",
    "点火" to "🔥",
    "哭哭" to "😢",
    "吐舌小狗" to "🐶😛",
    "送花" to "💐",
    "爱心手" to "❤️",
    "贴贴" to "🤗",
    "灵机一动" to "💡",
    "打脸" to "🤦",
    "机智" to "🧠",
    "送心" to "💝",
    "666" to "🤙",
    "来看我" to "👀",
    "一起加油" to "💪",
    "哈欠" to "😪",
    "衰" to "😞",
    "疑问" to "❓",
    "泣不成声" to "😭",
    "小鼓掌" to "👏",
    "大金牙" to "😁",
    "石化" to "😨",
    "吐血" to "😵💔",
    "可怜" to "🥺",
    "嘘" to "🤫",
    "撇嘴" to "😒",
    "憨笑" to "😄",
    "抓狂" to "😫",
    "钱" to "💰",
    "恐惧" to "😨",
    "愉快" to "😄",
    "快哭了" to "🥺",
    "翻白眼" to "🙄",
    "互粉" to "🤝",
    "我想静静" to "😔",
    "委屈" to "🥺",
    "鄙视" to "😒",
    "再见" to "👋",
    "紫薇别走" to "🥺👋",
    "听歌" to "🎧",
    "求抱抱" to "🤗",
    "绝望的凝视" to "😑",
    "不失礼貌的微笑" to "🙂",
    "不看" to "🙈",
    "裂开" to "💔",
    "干饭人" to "🍚",
    "呆无辜" to "😇",
    "白眼" to "🙄",
    "猪头" to "🐷",
    "冷漠" to "😐",
    "暗中观察" to "👀",
    "二哈" to "🐶",
    "菜狗" to "🐶🥬",
    "黑脸" to "🌚",
    "展开说说" to "💬",
    "蜜蜂狗" to "🐶🐝",
    "柴犬" to "🐕",
    "摸头" to "🖐️",
    "皱眉" to "😟",
    "擦汗" to "😓",
    "红脸" to "😳",
    "做鬼脸" to "😜",
    "强" to "👍",
    "如花" to "🤡",
    "吐" to "🤮",
    "惊喜" to "🤩",
    "敲打" to "🔨",
    "奋斗" to "💪",
    "吐彩虹" to "🌈",
    "嘿哈" to "💪",
    "惊恐" to "😱",
    "囧" to "😖",
    "难过" to "😞",
    "斜眼" to "😒",
    "阴险" to "😏",
    "悠闲" to "😌",
    "咒骂" to "🤬",
    "吃瓜" to "🍉",
    "吃瓜群众" to "🍉",
    "绿帽子" to "🧢",
    "敢怒不敢言" to "😤",
    "求求了" to "🙏",
    "眼含热泪" to "🥺",
    "叹气" to "😮‍💨",
    "好开心" to "😄",
    "不是吧" to "😲",
    "鞠躬" to "🙇",
    "躺平" to "🛌",
    "九转大肠" to "🍲",
    "不你不想" to "🙅",
    "一头乱麻" to "😵",
    "kisskiss" to "😘",
    "你不大行" to "👎",
    "噢买尬" to "😱",
    "宕机" to "😵",
    "苦涩" to "😣",
    "逞强落泪" to "🥲",
    "求机位-黄脸" to "📍",
    "求机位3" to "📍",
    "精选" to "⭐",
    "强壮" to "💪",
    "碰拳" to "👊",
    "击掌" to "🙌",
    "左上" to "↖️",
    "勾引" to "😏",
    "拳头" to "✊",
    "弱" to "👎",
    "右边" to "👉",
    "左边" to "👈",
    "嘴唇" to "💋",
    "凋谢" to "🥀",
    "垃圾" to "🗑️",
    "撒花" to "🎉",
    "加一" to "➕",
    "减一" to "➖",
    "okk" to "👌",
    "V5" to "💪",
    "绝" to "🤩",
    "给力" to "💪",
    "红包" to "🧧",
    "屎" to "💩",
    "发" to "💰",
    "18禁" to "🔞",
    "炸弹" to "💣",
    "西瓜" to "🍉",
    "加鸡腿" to "🍗",
    "握爪" to "🐾",
    "太阳" to "☀️",
    "月亮" to "🌙",
    "给跪了" to "🙇",
    "蕉绿" to "😰",
    "扎心" to "💔",
    "胡瓜" to "🥒",
    "打call" to "📣",
    "栓Q" to "😓",
    "雪花" to "❄️",
    "圣诞树" to "🎄",
    "平安果" to "🍎",
    "圣诞帽" to "🎅",
    "气球" to "🎈",
    "烟花" to "🎆",
    "福" to "🧧",
    "candy" to "🍬",
    "糖葫芦" to "🍡",
    "鞭炮" to "🧨",
    "元宝" to "💰",
    "灯笼" to "🏮",
    "锦鲤" to "🎏",
    "巧克力" to "🍫",
    "戒指" to "💍",
    "棒棒糖" to "🍭",
    "纸飞机" to "✈️",
    "粽子" to "🍙",
)

internal val DANMAKU_EMOJI_ASSETS: Map<String, String> = mapOf(
    "↖️" to "2196-fe0f.png",
    "☀️" to "2600-fe0f.png",
    "☕️" to "2615-fe0f.png",
    "✈️" to "2708-fe0f.png",
    "✊" to "270a.png",
    "✌️" to "270c-fe0f.png",
    "❄️" to "2744-fe0f.png",
    "❓" to "2753.png",
    "❤️" to "2764-fe0f.png",
    "➕" to "2795.png",
    "➖" to "2796.png",
    "⭐" to "2b50.png",
    "🌈" to "1f308.png",
    "🌙" to "1f319.png",
    "🌚" to "1f31a.png",
    "🌹" to "1f339.png",
    "🍉" to "1f349.png",
    "🍎" to "1f34e.png",
    "🍗" to "1f357.png",
    "🍙" to "1f359.png",
    "🍚" to "1f35a.png",
    "🍡" to "1f361.png",
    "🍫" to "1f36b.png",
    "🍬" to "1f36c.png",
    "🍭" to "1f36d.png",
    "🍲" to "1f372.png",
    "🍺" to "1f37a.png",
    "🍻" to "1f37b.png",
    "🎁" to "1f381.png",
    "🎂" to "1f382.png",
    "🎄" to "1f384.png",
    "🎅" to "1f385.png",
    "🎆" to "1f386.png",
    "🎈" to "1f388.png",
    "🎉" to "1f389.png",
    "🎏" to "1f38f.png",
    "🎧" to "1f3a7.png",
    "🏮" to "1f3ee.png",
    "🐕" to "1f415.png",
    "🐝" to "1f41d.png",
    "🐶" to "1f436.png",
    "🐷" to "1f437.png",
    "🐾" to "1f43e.png",
    "👀" to "1f440.png",
    "👃" to "1f443.png",
    "👈" to "1f448.png",
    "👉" to "1f449.png",
    "👊" to "1f44a.png",
    "👋" to "1f44b.png",
    "👌" to "1f44c.png",
    "👍" to "1f44d.png",
    "👎" to "1f44e.png",
    "👏" to "1f44f.png",
    "💋" to "1f48b.png",
    "💍" to "1f48d.png",
    "💐" to "1f490.png",
    "💔" to "1f494.png",
    "💝" to "1f49d.png",
    "💡" to "1f4a1.png",
    "💣" to "1f4a3.png",
    "💩" to "1f4a9.png",
    "💪" to "1f4aa.png",
    "💬" to "1f4ac.png",
    "💰" to "1f4b0.png",
    "📍" to "1f4cd.png",
    "📣" to "1f4e3.png",
    "🔞" to "1f51e.png",
    "🔥" to "1f525.png",
    "🔨" to "1f528.png",
    "🖐️" to "1f590-fe0f.png",
    "🗑️" to "1f5d1-fe0f.png",
    "😁" to "1f601.png",
    "😂" to "1f602.png",
    "😄" to "1f604.png",
    "😅" to "1f605.png",
    "😆" to "1f606.png",
    "😇" to "1f607.png",
    "😊" to "1f60a.png",
    "😋" to "1f60b.png",
    "😌" to "1f60c.png",
    "😍" to "1f60d.png",
    "😎" to "1f60e.png",
    "😏" to "1f60f.png",
    "😐" to "1f610.png",
    "😑" to "1f611.png",
    "😒" to "1f612.png",
    "😓" to "1f613.png",
    "😔" to "1f614.png",
    "😖" to "1f616.png",
    "😘" to "1f618.png",
    "😛" to "1f61b.png",
    "😜" to "1f61c.png",
    "😞" to "1f61e.png",
    "😟" to "1f61f.png",
    "😠" to "1f620.png",
    "😡" to "1f621.png",
    "😢" to "1f622.png",
    "😣" to "1f623.png",
    "😤" to "1f624.png",
    "😨" to "1f628.png",
    "😪" to "1f62a.png",
    "😫" to "1f62b.png",
    "😭" to "1f62d.png",
    "😮" to "1f62e.png",
    "😮‍💨" to "1f62e-200d-1f4a8.png",
    "😰" to "1f630.png",
    "😱" to "1f631.png",
    "😲" to "1f632.png",
    "😳" to "1f633.png",
    "😴" to "1f634.png",
    "😵" to "1f635.png",
    "😶" to "1f636.png",
    "🙂" to "1f642.png",
    "🙄" to "1f644.png",
    "🙅" to "1f645.png",
    "🙇" to "1f647.png",
    "🙈" to "1f648.png",
    "🙋" to "1f64b.png",
    "🙌" to "1f64c.png",
    "🙏" to "1f64f.png",
    "🛌" to "1f6cc.png",
    "🤐" to "1f910.png",
    "🤔" to "1f914.png",
    "🤗" to "1f917.png",
    "🤙" to "1f919.png",
    "🤝" to "1f91d.png",
    "🤡" to "1f921.png",
    "🤦" to "1f926.png",
    "🤩" to "1f929.png",
    "🤫" to "1f92b.png",
    "🤬" to "1f92c.png",
    "🤭" to "1f92d.png",
    "🤮" to "1f92e.png",
    "🤷" to "1f937.png",
    "🥀" to "1f940.png",
    "🥒" to "1f952.png",
    "🥬" to "1f96c.png",
    "🥲" to "1f972.png",
    "🥵" to "1f975.png",
    "🥺" to "1f97a.png",
    "🧠" to "1f9e0.png",
    "🧢" to "1f9e2.png",
    "🧧" to "1f9e7.png",
    "🧨" to "1f9e8.png",
    "🫡" to "1fae1.png",
)

/** 长表情序列（ZWJ、肤色、组合表情）优先匹配，避免把 😮‍💨 拆成两个图片。 */
internal val DANMAKU_EMOJI_KEYS: List<String> = DANMAKU_EMOJI_ASSETS.keys.sortedByDescending { it.length }

private val DANMAKU_EMOJI_TAG = Regex(
    "\\[([^\\[\\]【】［］\\r\\n]{1,16})\\]|" +
    "【([^\\[\\]【】［］\\r\\n]{1,16})】|" +
    "［([^\\[\\]【】［］\\r\\n]{1,16})］"
)

/** 弹幕文本的一段：普通文字，或一张表情图片（[emojiAsset] 是 assets/danmaku_emoji 下的文件名）。 */
data class DanmakuPart(
    val text: String,
    val emojiAsset: String? = null,
    val fallback: String = text,
)

private fun danmakuEmojiForName(name: String): String? =
    DANMAKU_EMOJI_NAMES[name]
        ?: DANMAKU_EMOJI_NAMES[name.lowercase()]
        ?: DANMAKU_EMOJI_NAMES[name.uppercase()]

/** 把一段连续表情拆成单个图片；任何一段没有对应图片时返回 null，保留原文。 */
private fun danmakuEmojiParts(glyphs: String): List<Pair<String, String>>? {
    val result = ArrayList<Pair<String, String>>()
    var offset = 0
    while (offset < glyphs.length) {
        val key = DANMAKU_EMOJI_KEYS.firstOrNull { glyphs.startsWith(it, offset) } ?: return null
        val asset = DANMAKU_EMOJI_ASSETS.getValue(key)
        result += key to asset
        offset += key.length
    }
    return result.takeIf { it.isNotEmpty() }
}

private fun appendDanmakuPlain(
    value: String,
    parts: MutableList<DanmakuPart>,
    pending: StringBuilder,
    flush: () -> Unit,
) {
    var offset = 0
    while (offset < value.length) {
        val key = DANMAKU_EMOJI_KEYS.firstOrNull { value.startsWith(it, offset) }
        if (key == null) {
            val codePoint = value.codePointAt(offset)
            val count = Character.charCount(codePoint)
            pending.append(value, offset, offset + count)
            offset += count
        } else {
            flush()
            parts += DanmakuPart(key, DANMAKU_EMOJI_ASSETS.getValue(key), key)
            offset += key.length
        }
    }
}

/**
 * 把弹幕原文拆成文字和表情图片。支持 `[偷笑]`、`【偷笑】`、`［偷笑］` 三种占位符，
 * 也识别直接出现在文本里的 emoji；未收录的占位符原样保留，不会误伤普通方括号内容。
 */
fun danmakuParts(value: String): List<DanmakuPart> {
    if (value.isEmpty()) return emptyList()
    val parts = ArrayList<DanmakuPart>()
    val pending = StringBuilder()
    fun flush() {
        if (pending.isNotEmpty()) {
            parts += DanmakuPart(pending.toString())
            pending.clear()
        }
    }
    var offset = 0
    for (match in DANMAKU_EMOJI_TAG.findAll(value)) {
        appendDanmakuPlain(value.substring(offset, match.range.first), parts, pending, ::flush)
        val name = match.groupValues[1].ifEmpty { match.groupValues[2].ifEmpty { match.groupValues[3] } }
        val emoji = danmakuEmojiForName(name)?.let(::danmakuEmojiParts)
        if (emoji.isNullOrEmpty()) {
            pending.append(match.value)
        } else {
            flush()
            for ((glyph, asset) in emoji) parts += DanmakuPart(glyph, asset, match.value)
        }
        offset = match.range.last + 1
    }
    appendDanmakuPlain(value.substring(offset), parts, pending, ::flush)
    flush()
    return parts
}

/** 纯文本预览：已知表情转成 Unicode；图片加载失败时由 [DanmakuPart.fallback] 兜底。 */
fun danmakuDisplayText(value: String): String =
    danmakuParts(value).joinToString("") { it.text }
