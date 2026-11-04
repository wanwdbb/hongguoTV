package com.hongguotv.core

/** Only continue an unambiguous, explicitly numbered adjacent season of the same title. */
object SeasonContinuation {
    data class Title(val base: String, val season: Int)
    private val marker=Regex("第([0-9０-９一二三四五六七八九十百零〇两]+)季")
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
        val matches=marker.findAll(title).toList();if(matches.size!=1)return null
        val match=matches.single();val n=number(match.groupValues[1]) ?: return null
        val base=normalize(title.removeRange(match.range));if(base.isEmpty())return null
        return Title(base,n)
    }
    fun query(title: String): String? = parse(title)?.let {marker.replace(title,"").trim(' ','（','）','(',')','-')}
    fun select(current: Series, candidates: List<Series>): Series? {
        val from=parse(current.title) ?: return null
        return candidates.distinctBy {it.id}.filter {
            val to=parse(it.title)
            it.id!=current.id && !it.preview && to!=null && to.base==from.base && to.season==from.season+1 &&
                (current.bookType==0 || it.bookType==0 || current.bookType==it.bookType)
        }.singleOrNull()
    }
}
