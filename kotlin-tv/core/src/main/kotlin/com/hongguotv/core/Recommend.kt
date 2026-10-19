// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import kotlin.math.ceil
import kotlin.math.exp

/** 推荐候选：来自某个类型入口的剧集；[discovery] 表示来自兴趣题材等探索来源。 */
data class RecommendCandidate(val series: Series,val type: CatalogType,val discovery: Boolean = false)

/** 推荐用的标签与集数解析，规则与共享推荐核心一致。 */
internal object RecommendText {
    fun tags(value: String): List<String> = value.split(Regex("[/·,，#\\s]+")).map { it.trim() }.filter { it.isNotEmpty() && it!="短剧" }
    fun episodes(badge: String): Int = Regex("全\\s*(\\d+)\\s*集").find(badge)?.groupValues?.get(1)?.toIntOrNull() ?: 0
}

/**
 * 本机兴趣画像。
 *
 * 规则与参考实现的共享推荐核心一致：
 * 收藏每部 +4，实际看过 30 秒以上每部 +1（不足 30 秒不计，快进不虚增）；
 * [resetAt] 之前的观看记录只影响最近观看时间，不再累计兴趣（「重置兴趣」用）；
 * 7 天内看过的剧在排序里降权，已看完整剧的剧不再推荐。
 * [types] 是已知类型的内容，用于按类型偏好调整混合权重。
 */
data class RecommendProfile(
    val interests: Map<String,Int> = emptyMap(),
    val recent: Map<String,Long> = emptyMap(),
    val completed: Set<String> = emptySet(),
    val typeInterests: Map<CatalogType,Int> = emptyMap()
) {
    /** 一部剧的题材兴趣分：标签逐个累加，单标签最多算 6 分。 */
    fun interest(tags: String): Int = RecommendText.tags(tags).sumOf { (interests[it] ?: 0).coerceIn(-4,6) }
    fun isRecent(id: String,now: Long): Boolean = recent[id]?.let { now-it<RECENT_DAYS*DAY && now-it>=0 } ?: false

    companion object {
        const val DAY=24*60*60*1000L
        const val RECENT_DAYS=7L
        fun of(favorites: List<Series>,history: List<BackupProgress>,watched: Set<String>,now: Long,types: Map<String,CatalogType> = emptyMap(),resetAt: Long = 0): RecommendProfile {
            val interests=mutableMapOf<String,Int>()
            val recent=mutableMapOf<String,Long>()
            val completed=watched.toMutableSet()
            val typeInterests=mutableMapOf<CatalogType,Int>()
            fun score(series: Series,weight: Int) {
                RecommendText.tags(series.tags).forEach { tag -> interests[tag]=(interests[tag] ?: 0)+weight }
                types[series.id]?.let { type -> typeInterests[type]=(typeInterests[type] ?: 0)+weight }
            }
            history.forEach { row ->
                val id=row.series.id
                if(row.position>0 || row.completed) recent[id]=maxOf(recent[id] ?: 0,row.updatedAt)
                val episodes=RecommendText.episodes(row.series.badge)
                if(row.completed && episodes>0 && row.episodeIndex+1>=episodes) completed+=id
                if(row.updatedAt>=resetAt && row.position>=30_000) score(row.series,1)
            }
            favorites.forEach { score(it,4) }
            return RecommendProfile(interests,recent,completed,typeInterests)
        }
    }
}

/**
 * 一次推荐会话。
 *
 * 会话种子在应用启动或「换一批」时更换：同分候选按种子稳定排序，新会话换序，
 * 已经发过的剧不会重复出现。混合模式按权重逐批取候选，并预留 20% 给没看过或陌生题材，
 * 某个类型抓取失败时由其它类型补足。
 */
class RecommendSession(val seed: String,val mode: RecommendMode,private val profile: RecommendProfile = RecommendProfile(),private val now: Long = System.currentTimeMillis()) {
    private val emitted=linkedSetOf<String>()
    /** 混合模式的类型权重：默认真人剧 50%、漫剧 30%、AI 剧 20%，按类型兴趣最多各放大到 ±1，每类不低于 10%。 */
    val weights: Map<CatalogType,Double>
    val emittedCount get()=emitted.size

    init {
        val adjusted=TYPES.mapIndexed { index,type -> BASE[index]*exp(((profile.typeInterests[type] ?: 0)/12.0).coerceIn(-1.0,1.0)) }
        val sum=adjusted.sum()
        weights=if(TYPES.all { (profile.typeInterests[it] ?: 0)==0 }) TYPES.mapIndexed { index,type -> type to BASE[index] }.toMap()
            else TYPES.mapIndexed { index,type -> type to 0.1+0.7*adjusted[index]/sum }.toMap()
    }

    /** 取下一批推荐，最多 [limit] 部，返回顺序已经按种子打散。 */
    fun append(candidates: List<RecommendCandidate>,limit: Int = DEFAULT_LIMIT): List<RecommendCandidate> {
        if(limit<=0) return emptyList()
        val unique=linkedMapOf<String,RecommendCandidate>()
        candidates.forEach { candidate ->
            val id=candidate.series.id
            if(!id.matches(ID) || candidate.series.title.isBlank()) return@forEach
            if(id in emitted || id in profile.completed) return@forEach
            if(mode.type!=null && candidate.type!=mode.type) return@forEach
            unique.putIfAbsent(id,candidate)
        }
        val queues=TYPES.associateWith { TypeQueue() }.toMutableMap()
        unique.values.sortedWith(compareByDescending<RecommendCandidate> { score(it) }.thenBy { it.series.id }).forEach { candidate ->
            val queue=queues[candidate.type] ?: return@forEach
            queue.all+=candidate
            if(exploration(candidate)) {
                queue.explore+=candidate
                if(candidate.discovery) queue.marked+=candidate
            }
        }
        val counts=TYPES.associateWith { 0 }.toMutableMap()
        val desired=TYPES.associateWith { type -> if(mode.type==null) limit*weights.getValue(type) else if(type==mode.type) limit.toDouble() else 0.0 }
        val selected=mutableSetOf<String>()
        val result=mutableListOf<RecommendCandidate>()
        fun peek(type: CatalogType,kind: Int): RecommendCandidate? = queues.getValue(type).peek(kind,selected)
        fun nextType(kind: Int): CatalogType? = TYPES.filter { peek(it,kind)!=null }.maxByOrNull { desired.getValue(it)-counts.getValue(it) }
        fun take(candidate: RecommendCandidate) { result+=candidate; selected+=candidate.series.id; counts[candidate.type]=counts.getValue(candidate.type)+1 }

        repeat(ceil(limit*EXPLORATION_RATIO).toInt()) {
            val type=nextType(QUEUE_EXPLORE) ?: return@repeat
            val candidate=peek(type,QUEUE_MARKED) ?: peek(type,QUEUE_EXPLORE) ?: return@repeat
            take(candidate)
        }
        while(result.size<limit) {
            val type=nextType(QUEUE_ALL) ?: break
            take(peek(type,QUEUE_ALL) ?: break)
        }
        val prefix="$seed|$emittedCount|order|"
        val ordered=result.sortedBy { unitHash(prefix+it.series.id) }
        ordered.forEach { emitted+=it.series.id }
        return ordered
    }

    private fun score(candidate: RecommendCandidate): Double =
        profile.interest(candidate.series.tags)*2 + unitHash("$seed|${candidate.series.id}")*4 - if(profile.isRecent(candidate.series.id,now)) 100.0 else 0.0

    private fun exploration(candidate: RecommendCandidate): Boolean {
        if(profile.isRecent(candidate.series.id,now)) return false
        return candidate.discovery || RecommendText.tags(candidate.series.tags).all { (profile.interests[it] ?: 0)<=0 }
    }

    private class TypeQueue {
        val all=mutableListOf<RecommendCandidate>()
        val explore=mutableListOf<RecommendCandidate>()
        val marked=mutableListOf<RecommendCandidate>()
        private var cursors=intArrayOf(0,0,0)
        fun peek(kind: Int,selected: Set<String>): RecommendCandidate? {
            val list=when(kind) { QUEUE_ALL -> all; QUEUE_EXPLORE -> explore; else -> marked }
            var index=cursors[kind]
            while(index<list.size && list[index].series.id in selected) index++
            cursors[kind]=index
            return list.getOrNull(index)
        }
    }

    companion object {
        const val DEFAULT_LIMIT=24
        const val EXPLORATION_RATIO=0.2
        private val TYPES=listOf(CatalogType.REAL,CatalogType.COMIC_DRAMA,CatalogType.AI_DRAMA)
        private val BASE=listOf(0.5,0.3,0.2)
        private val ID=Regex("[0-9]{1,30}")
        /** FNV-1a 32 位散列，和参考实现同一套种子算法：同一种子、同一输入结果稳定。 */
        internal fun unitHash(value: String): Double {
            var h=2166136261L
            value.forEach { ch -> h=((h xor ch.code.toLong())*16777619L) and 0xffffffffL }
            return h.toDouble()/4294967296.0
        }
    }
}

private const val QUEUE_ALL=0
private const val QUEUE_EXPLORE=1
private const val QUEUE_MARKED=2

