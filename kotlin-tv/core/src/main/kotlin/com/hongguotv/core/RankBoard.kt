// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

/**
 * 网站「热播榜」的四个榜单，顺序与网站顶部导航一致。
 *
 * [slug] 同时是地址段（`/rank/<slug>`）和页面数据键（`rank_<slug>/page`）；
 * [rankKey] 是页面自己声明的榜单标识（综合榜是 `hongguo`），用来确认没有拿到别的榜单。
 */
enum class RankBoard(val slug: String, val label: String, val rankKey: String) {
    HONGGUO("hot-drama","红果热播榜","hongguo"),
    REAL("hot-real-drama","真人剧热播榜","real"),
    AI("hot-ai-drama","AI剧热播榜","ai"),
    COMIC("hot-comic-drama","漫剧热播榜","comic");

    val path get() = "/rank/$slug"
    val pageKey get() = "rank_$slug/page"
    /** 榜单对应的内容类型；综合榜是混合榜单，没有单一类型。 */
    val type: CatalogType? get() = when(this) {
        HONGGUO -> null
        REAL -> CatalogType.REAL
        AI -> CatalogType.AI_DRAMA
        COMIC -> CatalogType.COMIC_DRAMA
    }

    companion object { fun fromStored(value: String?): RankBoard = entries.firstOrNull { it.slug==value } ?: HONGGUO }
}
