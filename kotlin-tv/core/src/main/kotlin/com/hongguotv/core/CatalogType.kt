// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

/**
 * 网站分类页的四个内容类型入口。
 *
 * 与搜索用的 [ContentType] 是两套体系：这里只用于「分类」浏览，
 * [path] 是网站分类页地址，[storedValue] 用于本机记忆上次选择。
 */
enum class CatalogType(val storedValue: String, val label: String, val path: String) {
    REAL("real-drama","真人剧","/category/real-drama"),
    COMIC_DRAMA("comic-drama","漫剧","/category/comic-drama"),
    AI_DRAMA("ai-drama","AI剧","/category/ai-drama"),
    COMIC("comic","漫画","/category/comic");

    companion object { fun fromStored(value: String?): CatalogType = entries.firstOrNull { it.storedValue==value } ?: REAL }
}

/**
 * 分类页的题材筛选项。
 *
 * [slug] 可以直接拼进分类页地址（`/category/<类型>/<slug>`），空串表示「全部」。
 * 网站尚未提供题材的入口（例如漫画）会返回空列表。
 */
data class CatalogGenre(val slug: String, val label: String)
