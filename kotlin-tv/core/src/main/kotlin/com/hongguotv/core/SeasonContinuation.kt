package com.hongguotv.core

/** Only continue an unambiguous, explicitly numbered adjacent season of the same title. */
object SeasonContinuation {
    data class Title(val base: String, val season: Int)
    private val marker=Regex("第?([0-9０-９一二三四五六七八九十百零〇两]+)季")
    private fun number(raw: String): Int? {
        val s=java.text.Normalizer.normalize(raw,java.text.Normalizer.Form.NFKC)
        s.toIntOrNull()?.let { return it.takeIf { n->n in 1..999 } }
        var total=0;var digit=0
        for(c in s) when(c) {
            '十','百' -> {total+=(if(digit==0) 1 else digit)*(if(c=='十') 10 else 100);digit=0}
            else -> {digit="零一二三四五六七八九".indexOf(if(c=='〇') '零' else if(c=='两') '二' else c);if(digit<0)return null}
        }
        return (total+digit).takeIf {it in 1..999}
    }
    private fun normalize(s: String)=java.text.Normalizer.normalize(s,java.text.Normalizer.Form.NFKC).filter {it.isLetterOrDigit()}.lowercase(java.util.Locale.ROOT)
    fun parse(title: String): Title? {
        val matches=marker.findAll(title).toList()
        if(matches.isEmpty()) return normalize(title).takeIf { it.isNotEmpty() && !title.contains("季") }?.let { Title(it,1) }
        if(matches.size!=1)return null
        val match=matches.single();val n=number(match.groupValues[1]) ?: return null
        val base=normalize(title.removeRange(match.range));if(base.isEmpty())return null
        return Title(base,n)
    }
    fun query(title: String): String? = parse(title)?.let {marker.replace(title,"").trim(' ','（','）','(',')','-')}
    private fun identity(series: Series): Title? {
        val parsed=parse(series.title) ?: return null
        val explicit=marker.containsMatchIn(series.title)
        if(series.seasonIndex>0 && explicit && parsed.season!=series.seasonIndex) return null
        return parsed.copy(season=series.seasonIndex.takeIf { it>0 } ?: parsed.season)
    }
    /** Related-work season_index is authoritative when a title omits its number. */
    fun select(current: Series, candidates: List<Series>): Series? {
        val from=identity(current) ?: return null
        return candidates.distinctBy {it.id}.filter {
            val to=identity(it)
            it.id!=current.id && !it.preview && to!=null && to.base==from.base && to.season==from.season+1 &&
                (current.bookType==0 || it.bookType==0 || current.bookType==it.bookType)
        }.singleOrNull()
    }
    /** Detail pages may omit season_index; retain verified related metadata, reject conflicts. */
    fun verifiedDetail(current: Series,candidate: Series,detail: Detail): Detail? {
        if(detail.series.id!=candidate.id || detail.episodes.isEmpty()) return null
        val merged=detail.series.copy(seasonIndex=detail.series.seasonIndex.takeIf { it>0 } ?: candidate.seasonIndex)
        return detail.copy(series=merged).takeIf { select(current,listOf(merged))!=null }
    }
}
