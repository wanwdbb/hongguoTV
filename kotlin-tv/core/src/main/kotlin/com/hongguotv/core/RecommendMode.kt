// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

/**
 * 推荐模式。
 *
 * [MIXED] 按真人剧 50%、漫剧 30%、AI 剧 20% 混合，单一类型模式只看指定类型。
 * [storedValue] 与共享推荐契约一致，用于本机保存，将来接入同步时不用再改格式。
 */
enum class RecommendMode(val storedValue: String,val label: String) {
    MIXED("mixed","混合推荐"),
    REAL("real-drama","真人剧"),
    COMIC("comic-drama","漫剧"),
    AI("ai-drama","AI剧");

    /** 单一类型模式对应的网站分类；混合模式返回 null。 */
    val type: CatalogType? get() = when(this) {
        MIXED -> null
        REAL -> CatalogType.REAL
        COMIC -> CatalogType.COMIC_DRAMA
        AI -> CatalogType.AI_DRAMA
    }

    companion object { fun fromStored(value: String?): RecommendMode = entries.firstOrNull { it.storedValue==value } ?: MIXED }
}
