// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.content.Context
import android.graphics.Rect
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.*
import com.hongguotv.core.*

/** Windowed rows reuse a bounded pool. The full list is data, not retained poster views. */
internal class StreamingPosterGrid(
    context: Context, private val columns: Int, private val upper: View,
    private val artwork: (ImageView,String)->Unit,
    private val open: (Series)->Unit,
    private val selected: (Series)->Unit = {},
    private val actions: (Series,View)->Unit = { _,_ -> },
    private val left: (() -> View?)? = null,
    private val embedded: Boolean=false,
    private val label: (Series)->String = { it.badge }
): ScrollView(context) {
    private fun dp(n: Int)=(n*resources.displayMetrics.density).toInt()
    private val canvas=FrameLayout(context)
    private val footer=TextView(context).apply { textSize=12f; setTextColor(TvStyle.muted); gravity=Gravity.CENTER }
    private val items=mutableListOf<Series>()
    private val indices=linkedMapOf<String,Int>()
    private val mounted=sortedMapOf<Int,LinearLayout>()
    private val pool=java.util.ArrayDeque<LinearLayout>()
    private val gate=BrowseLoadGate()
    private var ticket: Int?=null
    private var available=false
    private var closed=false
    private var cardWidth=1
    private var rowHeight=dp(220)
    private var focusedId: String?=null
    private var lastWindow=""
    private var restoring=false
    private var pendingRestore: Pair<BrowsePosition,Boolean>?=null
    var outerScroll: ScrollView?=null
    var more: (() -> Unit)?=null
    init {
        isVerticalScrollBarEnabled=false; clipToPadding=false
        addView(canvas); canvas.addView(footer,FrameLayout.LayoutParams(-1,dp(40)))
        setOnScrollChangeListener { _,_,y,_,old -> updateViewport(); if(y>old) requestMore() }
        addOnLayoutChangeListener { _,l,_,r,_,ol,_,orr,_ -> if(r-l!=orr-ol) resize(); updateViewport(); applyRestore() }
    }
    override fun onMeasure(w: Int,h: Int) { super.onMeasure(w,if(embedded) View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED) else h) }
    override fun onDetachedFromWindow() {
        closed=true; gate.close(); more=null
        mounted.values.forEach(::clearArtwork); pool.forEach(::clearArtwork)
        super.onDetachedFromWindow()
    }
    fun first(): View? {
        if(items.isEmpty()) return null
        val row=(viewport().first.coerceAtLeast(0)/rowHeight).coerceAtMost(PosterWindow.rows(items.size,columns)-1)
        return mount(row).getChildAt(0)
    }
    fun find(id: String): View? {
        val index=indices[id] ?: return null
        return mount(index/columns).getChildAt(index%columns)
    }
    fun capturePosition(): BrowsePosition {
        val (offset,_)=viewport()
        val row=(offset/rowHeight).coerceIn(0,(PosterWindow.rows(items.size,columns)-1).coerceAtLeast(0))
        val focus=findFocus()?.tag?.toString() ?: focusedId
        return BrowsePosition(items.getOrNull(row*columns)?.id.orEmpty(),offset-row*rowHeight,focus,(outerScroll ?: this).scrollY)
    }
    fun restore(position: BrowsePosition,focus: Boolean=true) { pendingRestore=position to focus; post { applyRestore() } }
    private fun applyRestore() {
        val (position,focus)=pendingRestore ?: return
        if(width<=0 || items.isEmpty() || rowHeight<=0 || !isLaidOut) return
        pendingRestore=null; restoring=true
        val row=(indices[position.anchor] ?: 0)/columns
        val target=row*rowHeight+position.offset
        val outer=outerScroll
        if(outer!=null) {
            val relative=IntArray(2);val host=IntArray(2)
            getLocationOnScreen(relative);outer.getLocationOnScreen(host)
            outer.scrollTo(0,(outer.scrollY+relative[1]-host[1]+target).coerceAtLeast(0))
        } else scrollTo(0,target.coerceAtLeast(0))
        updateViewport()
        if(focus) position.focus?.let { find(it)?.requestFocus() }
        post { restoring=false; updateViewport() }
    }
    fun state(hasMore: Boolean,loading: Boolean=false,error: Boolean=false) {
        if(closed) return
        if(!loading) { ticket?.let { gate.finish(it) };ticket=null }
        available=hasMore
        footer.text=when { error -> "加载失败，向下浏览重试"; loading -> "正在加载…"; hasMore -> "继续向下浏览"; items.isEmpty() -> "暂无内容"; else -> "已全部加载" }
    }
    fun requestNext() { requestMore(true) }
    private fun requestMore(force: Boolean=false) {
        if(closed || restoring || !available || more==null || !isAttachedToWindow) return
        val (offset,viewport)=viewport()
        if(!force && canvas.height-offset-viewport>viewport/2) return
        val next=gate.begin() ?: return
        ticket=next; state(true,true); more?.invoke()
    }
    fun append(next: List<Series>) {
        if(closed) return
        val before=items.size
        val oldFocus=findFocus()?.tag?.toString()
        next.forEach { if(it.id !in indices) { indices[it.id]=items.size;items+=it } }
        if(items.size>before && before%columns!=0) {
            mounted.remove(before/columns)?.let { row -> canvas.removeView(row);clearArtwork(row);if(pool.size<4) pool.add(row) }
        }
        resize(); updateViewport()
        oldFocus?.let { find(it)?.requestFocus() }
        post { applyRestore();updateViewport() }
        android.util.Log.i("HongguoTV","Poster window append before=$before after=${items.size} mounted=${mounted.size*columns} focus=$focusedId scroll=${(outerScroll ?: this).scrollY}")
    }
    private fun viewport(): Pair<Int,Int> {
        val outer=outerScroll ?: return scrollY to height.coerceAtLeast(1)
        val ours=IntArray(2);val host=IntArray(2)
        getLocationOnScreen(ours);outer.getLocationOnScreen(host)
        return (host[1]-ours[1]) to outer.height.coerceAtLeast(1)
    }
    fun updateViewport() {
        if(closed || width<=0 || items.isEmpty()) return
        val (offset,viewport)=viewport()
        val needed=PosterWindow.range(items.size,columns,rowHeight,offset,viewport).toMutableSet()
        // Keep the focused row during touch scrolling; remote movement explicitly mounts its target.
        focusedId?.let { indices[it] }?.let { needed+=it/columns }
        mounted.keys.filter { it !in needed }.toList().forEach { index ->
            val row=mounted.remove(index)!!;canvas.removeView(row);clearArtwork(row)
            if(pool.size<4) pool.add(row)
        }
        needed.sorted().forEach { mount(it) }
        val marker="${mounted.keys.firstOrNull()}:${mounted.keys.lastOrNull()}:${items.size}"
        if(marker!=lastWindow) {
            lastWindow=marker
            android.util.Log.i("HongguoTV","Poster window total=${items.size} rows=${mounted.size} cards=${mounted.size*columns} pool=${pool.size} offset=$offset viewport=$viewport")
        }
    }
    private fun clearArtwork(row: LinearLayout) { for(i in 0 until row.childCount) ((row.getChildAt(i) as LinearLayout).getChildAt(0) as ImageView).setImageDrawable(null) }
    private fun newRow(): LinearLayout=LinearLayout(context).apply {
        orientation=LinearLayout.HORIZONTAL; gravity=Gravity.TOP
        repeat(columns) {
            val card=LinearLayout(context).apply { orientation=LinearLayout.VERTICAL;id=View.generateViewId();isFocusable=true;isFocusableInTouchMode=true;setPadding(dp(4),dp(4),dp(4),dp(8)) }
            card.addView(ImageView(context).apply { scaleType=ImageView.ScaleType.FIT_CENTER;importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO },LinearLayout.LayoutParams(-1,1))
            card.addView(TextView(context).apply { textSize=if(columns==3) 12f else 14f;setTextColor(TvStyle.text);maxLines=2;ellipsize=TextUtils.TruncateAt.END;setPadding(dp(3),dp(6),dp(3),0) },LinearLayout.LayoutParams(-1,dp(44)))
            card.addView(TextView(context).apply { textSize=11f;setTextColor(TvStyle.muted);maxLines=1;ellipsize=TextUtils.TruncateAt.END;setPadding(dp(3),dp(3),dp(3),0) },LinearLayout.LayoutParams(-1,dp(20)))
            addView(card,LinearLayout.LayoutParams(cardWidth,-2).apply { rightMargin=dp(10) })
        }
    }
    private fun mount(rowIndex: Int): LinearLayout {
        mounted[rowIndex]?.let { return it }
        val row=if(pool.isEmpty()) newRow() else pool.removeFirst()
        for(column in 0 until columns) {
            val index=rowIndex*columns+column
            val card=row.getChildAt(column) as LinearLayout
            val item=items.getOrNull(index)
            card.visibility=if(item==null) View.INVISIBLE else View.VISIBLE
            if(item==null) continue
            val image=card.getChildAt(0) as ImageView
            card.tag=item.id;card.background=TvStyle.card(context,false);card.contentDescription="${item.title}，${label(item)}"
            card.layoutParams=card.layoutParams.apply { width=cardWidth }
            image.layoutParams=image.layoutParams.apply { height=((cardWidth-dp(8))/TvStyle.POSTER_ASPECT).toInt() }
            (card.getChildAt(1) as TextView).text=item.title;(card.getChildAt(2) as TextView).text=label(item)
            card.setOnClickListener { open(item) };card.setOnLongClickListener { actions(item,card);true }
            card.setOnFocusChangeListener { _,focused ->
                card.background=TvStyle.card(context,focused)
                if(focused) { focusedId=item.id;selected(item);artwork(image,item.cover);if(index>=items.size-columns*2) requestMore(true) }
            }
            card.setOnKeyListener { _,key,event ->
                if(key==KeyEvent.KEYCODE_MENU) { if(event.action==KeyEvent.ACTION_UP) actions(item,card);true }
                else if(event.action!=KeyEvent.ACTION_DOWN) false
                else when(key) {
                    KeyEvent.KEYCODE_DPAD_UP -> { val target=PosterWindow.move(index,-columns,items.size,columns);if(target<0) upper.requestFocus() else focusIndex(target);true }
                    KeyEvent.KEYCODE_DPAD_DOWN -> { if(index+columns>=items.size) requestMore(true);focusIndex(PosterWindow.move(index,columns,items.size,columns));true }
                    KeyEvent.KEYCODE_DPAD_LEFT -> { if(index%columns==0) left?.invoke()?.requestFocus() else focusIndex(index-1);true }
                    KeyEvent.KEYCODE_DPAD_RIGHT -> { focusIndex(PosterWindow.move(index,1,items.size,columns));true }
                    else -> false
                }
            }
            image.post { if(!closed && image.isAttachedToWindow && card.tag==item.id) artwork(image,item.cover) }
        }
        canvas.addView(row,FrameLayout.LayoutParams(-1,rowHeight).apply { topMargin=rowIndex*rowHeight })
        mounted[rowIndex]=row
        return row
    }
    private fun focusIndex(index: Int) {
        val item=items.getOrNull(index) ?: return
        find(item.id)?.requestFocus();post { updateViewport() }
    }
    private fun resize() {
        if(width<=0) return
        val nextWidth=((width-paddingLeft-paddingRight)/columns-dp(10)).coerceAtLeast(dp(30))
        if(nextWidth!=cardWidth) {
            cardWidth=nextWidth
            mounted.values.toList().forEach { canvas.removeView(it);clearArtwork(it) };mounted.clear();pool.clear()
        }
        rowHeight=((cardWidth-dp(8))/TvStyle.POSTER_ASPECT).toInt()+dp(88)
        val total=PosterWindow.rows(items.size,columns)*rowHeight
        canvas.layoutParams=canvas.layoutParams.apply { height=total+dp(40) }
        footer.layoutParams=(footer.layoutParams as FrameLayout.LayoutParams).apply { topMargin=total }
    }
}
