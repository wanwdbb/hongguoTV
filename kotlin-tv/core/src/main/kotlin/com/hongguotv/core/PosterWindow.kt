package com.hongguotv.core

/** Geometry shared by the TV view and JVM regression checks. */
object PosterWindow {
    fun rows(items: Int,columns: Int)=if(items<=0) 0 else (items-1)/columns.coerceAtLeast(1)+1
    fun range(items: Int,columns: Int,rowHeight: Int,offset: Int,viewport: Int): IntRange {
        val total=rows(items,columns)
        if(total==0) return IntRange.EMPTY
        val h=rowHeight.coerceAtLeast(1)
        val first=(offset.coerceAtLeast(0)/h-2).coerceIn(0,total-1)
        val last=((offset.coerceAtLeast(0)+viewport.coerceAtLeast(1)-1)/h+2).coerceIn(first,total-1)
        return first..last
    }
    fun move(index: Int,key: Int,count: Int,columns: Int): Int {
        if(count==0) return -1
        val i=index.coerceIn(0,count-1)
        return when(key) {
            -1 -> if(i%columns>0) i-1 else i
            1 -> if(i%columns<columns-1) (i+1).coerceAtMost(count-1) else i
            -columns -> if(i>=columns) i-columns else -1
            columns -> (i+columns).coerceAtMost(count-1)
            else -> i
        }
    }
}

data class BrowsePosition(val anchor: String,val offset: Int,val focus: String?,val fallback: Int=0)
/** Bounded per-query/type/filter state; positions cannot leak into unrelated pages. */
class BrowsePositions(private val capacity: Int=24) {
    private val values=linkedMapOf<String,BrowsePosition>()
    fun save(key: String,value: BrowsePosition) { values.remove(key); values[key]=value; while(values.size>capacity) values.remove(values.keys.first()) }
    operator fun get(key: String)=values[key]
    fun clear(key: String) { values.remove(key) }
}
/** A failed request releases the gate; detached pages invalidate all old tickets. */
class BrowseLoadGate {
    private var revision=0
    private var pending=false
    private var closed=false
    fun begin(): Int? { if(closed || pending) return null; pending=true; return ++revision }
    fun finish(ticket: Int): Boolean { if(closed || !pending || ticket!=revision) return false; pending=false; return true }
    fun close() { closed=true; pending=false; revision++ }
}
