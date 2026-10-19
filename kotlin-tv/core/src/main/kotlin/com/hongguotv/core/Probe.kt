package com.hongguotv.core

fun main(args: Array<String>) {
    val repository=ContentRepository()
    if(args.contains("--ranking")) {
        try {
            for(board in RankBoard.entries) {
                val first=repository.ranking(board)
                val metadata=checkNotNull(first.ranking)
                check(first.items.isNotEmpty())
                val seen=mutableSetOf<String>()
                for(pageNumber in listOf(1,2,metadata.totalPages).distinct().filter { it<=metadata.totalPages }) {
                    val result=if(pageNumber==1) first else repository.ranking(board,pageNumber)
                    val ranking=checkNotNull(result.ranking)
                    check(result.items.isNotEmpty() && result.items.none { it.id in seen })
                    seen.addAll(result.items.map { it.id })
                    val ranks=result.items.map { checkNotNull(ranking.positions[it.id]).rank }
                    check(ranks.all { it!=null && it>0 } && ranks.filterNotNull().zipWithNext().all { (a,b) -> a<b })
                    check(result.items.all { ranking.positions[it.id]!!.heat.isNotBlank() })
                    check(result.items.all { item -> item.title.isNotBlank() && item.title.none { it.code<32 } }) { "标题里残留控制字符" }
                    check(result.hasMore==(pageNumber<ranking.totalPages))
                    val status=result.items.firstNotNullOfOrNull { ranking.positions[it.id]!!.status.ifBlank { null } } ?: "-"
                    println("board=${board.label}(${board.slug}); page=$pageNumber/${ranking.totalPages}; items=${result.items.size}; ranks=${ranks.first()}..${ranks.last()}; status=$status; firstHeat=${ranking.positions[result.items.first().id]!!.heat}; first=${result.items.first().title}; updated=${ranking.updatedText}")
                }
            }
        } finally {
            repository.http.connectionPool.evictAll(); repository.http.dispatcher.executorService.shutdown()
        }
        return
    }
    if(args.contains("--category")) {
        try {
            for(type in CatalogType.entries) {
                val all=repository.category(type)
                check(all.items.isNotEmpty())
                println("type=${type.label}(${type.storedValue}); items=${all.items.size}; genres=${all.genres.size}; hasMore=${all.hasMore}; first=${all.items.first().title}")
                if(all.genres.isEmpty()) { println("  genres=none (网站没有题材筛选)"); continue }
                println("  genres=${all.genres.joinToString(" ") { it.label }}")
                val genre=all.genres.first()
                val filtered=repository.category(type,genre.slug)
                check(filtered.items.isNotEmpty())
                check(filtered.items.map { it.id }!=all.items.map { it.id }) { "题材没有过滤出不同片单" }
                check(filtered.genres.map { it.slug }==all.genres.map { it.slug })
                val second=if(filtered.hasMore) repository.category(type,genre.slug,2) else null
                if(second!=null) check(second.items.none { it.id in filtered.items.map { item -> item.id }.toSet() })
                println("  genre=${genre.label}; items=${filtered.items.size}; hasMore=${filtered.hasMore}; page2=${second?.items?.size ?: 0}; first=${filtered.items.first().title}")
            }
        } finally {
            repository.http.connectionPool.evictAll(); repository.http.dispatcher.executorService.shutdown()
        }
        return
    }
    if(args.contains("--recommend")) {
        try {
            fun pool(page: Int,mode: RecommendMode): List<RecommendCandidate> {
                val types=mode.type?.let { listOf(it) } ?: listOf(CatalogType.REAL,CatalogType.COMIC_DRAMA,CatalogType.AI_DRAMA)
                return types.flatMap { type ->
                    runCatching { repository.category(type,page=page).items }.onFailure { println("  source=${type.label} failed=${it.message}") }.getOrDefault(emptyList())
                        .map { RecommendCandidate(it,type) }
                }
            }
            val profile=RecommendProfile(interests=mapOf("逆袭" to 6),recent=emptyMap(),completed=emptySet())
            val session=RecommendSession("probe-seed",RecommendMode.MIXED,profile)
            val first=session.append(pool(1,RecommendMode.MIXED))
            val second=session.append(pool(2,RecommendMode.MIXED))
            check(first.size==24 && second.size==24) { "批量不足：${first.size}/${second.size}" }
            val counts=first.groupingBy { it.type }.eachCount()
            check(counts.getValue(CatalogType.REAL)==12 && counts.getValue(CatalogType.COMIC_DRAMA)==7 && counts.getValue(CatalogType.AI_DRAMA)==5) { "混合比例变化：$counts" }
            check(first.map { it.series.id }.toSet().intersect(second.map { it.series.id }.toSet()).isEmpty()) { "分页出现重复" }
            check((first+second).all { it.series.id.matches(Regex("[0-9]{1,30}")) && it.series.title.isNotBlank() })
            println("mixed=${counts.getValue(CatalogType.REAL)}/${counts.getValue(CatalogType.COMIC_DRAMA)}/${counts.getValue(CatalogType.AI_DRAMA)} (real/comic/ai); batch1=${first.size}; batch2=${second.size}; overlap=0; first=${first.first().series.title}; second=${second.first().series.title}")
            val single=RecommendSession("probe-seed",RecommendMode.AI).append(pool(1,RecommendMode.AI))
            check(single.isNotEmpty() && single.all { it.type==CatalogType.AI_DRAMA }) { "单一类型模式混入了其它类型" }
            println("single=ai; items=${single.size}")
            val seeded=RecommendSession("probe-seed-2",RecommendMode.MIXED,profile).append(pool(1,RecommendMode.MIXED))
            check(seeded.size==first.size && seeded.map { it.series.id }!=first.map { it.series.id }) { "换种子没有换序" }
            println("newSeed=reshuffled; first=${seeded.first().series.title}")
        } finally {
            repository.http.connectionPool.evictAll(); repository.http.dispatcher.executorService.shutdown()
        }
        return
    }
    val type=if(args.contains("--comic")) ContentType.COMIC else ContentType.SHORT
    val keyword=if(type==ContentType.COMIC) "修仙" else "总裁"
    val search=repository.search(keyword,type=type); println("search=${search.items.size}; hasMore=${search.hasMore}")
    if(type==ContentType.COMIC && search.hasMore) {
        val next=repository.search(keyword,2,type)
        println("searchPage2=${next.items.size}; overlap=${search.items.map { it.id }.intersect(next.items.map { it.id }.toSet()).size}")
    }
    val home=repository.home(type=type); println("type=${type.label}; home=${home.items.size}; hasMore=${home.hasMore}")
    if(type==ContentType.COMIC) {
        val next=repository.home(2,type)
        println("homePage2=${next.items.size}; overlap=${home.items.map { it.id }.intersect(next.items.map { it.id }.toSet()).size}")
    }
    val detail=repository.detail(args.firstOrNull { it.matches(Regex("[0-9]{1,30}")) } ?: home.items.first().id)
    println("detail=${detail.series.title}; episodes=${detail.episodes.size}")
    val stream=repository.stream(detail.episodes.first())
    RemoteVideo(repository.http,stream).use { remote ->
        remote.prepare(); val first=remote.read(0,1024*1024)
        val tail=remote.read((remote.total-65536).coerceAtLeast(0),65536)
        println("quality=${stream.quality}; total=${remote.total}; first=${first.size}; tail=${tail.size}; header=${String(first,4,4)}")
        if(args.contains("--save")) java.io.File("/tmp/hongguo-kotlin-first.mp4").writeBytes(first)
    }
    repository.http.connectionPool.evictAll(); repository.http.dispatcher.executorService.shutdown()
}
