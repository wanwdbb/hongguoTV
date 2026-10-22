// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.RectF
import android.graphics.Typeface
import android.view.View
import com.hongguotv.core.DanmakuComment
import com.hongguotv.core.DanmakuPart
import com.hongguotv.core.danmakuParts
import kotlin.math.ceil
import kotlin.math.max

/**
 * 弹幕层：叠在播放器上，按播放进度计算每条弹幕的横向位置。
 * 位置完全由播放器时间决定，所以暂停、快进、倍速都不用另外同步。
 * 文字和表情先画进小位图再贴，避免每帧重新排版几十条弹幕。
 */
class DanmakuView(context: Context) : View(context) {
    private class Flight(val item: DanmakuComment, val lane: Int, val width: Float, val text: String, val parts: List<DanmakuPart>)
    private class Piece(val part: DanmakuPart, val width: Float, val bitmap: Bitmap?)
    private val metrics=resources.displayMetrics
    private var textSize=android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_SP,20f,resources.displayMetrics)
    private var laneHeight=textSize*1.4f
    private var maxLines=5
    private var alphaPercent=100
    private val gapMinimum=28f*metrics.density
    private val topPadding=18f*metrics.density
    private val shadowOffset=2f*metrics.density
    private val white=Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color=Color.argb(238,255,255,255); textSize=this@DanmakuView.textSize; typeface=Typeface.DEFAULT_BOLD
    }
    private val shadow=Paint(white).apply { color=Color.argb(190,0,0,0) }
    private val emojiPaint=Paint(Paint.ANTI_ALIAS_FLAG).apply { alpha=238 }
    private val emojiShadow=Paint(Paint.ANTI_ALIAS_FLAG).apply {
        colorFilter=PorterDuffColorFilter(Color.argb(190,0,0,0),PorterDuff.Mode.SRC_IN)
    }
    private val sprites=object: LinkedHashMap<String,Bitmap>(64,.75f,true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String,Bitmap>?)=size>160
    }
    // Fluent Emoji PNGs are bundled at 64×64; keep a small decoded cache and share it across episodes.
    private val emojiBitmaps=object: LinkedHashMap<String,Bitmap>(64,.75f,true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String,Bitmap>?)=size>96
    }
    private val missingEmoji=HashSet<String>()
    private var items=emptyList<DanmakuComment>()
    private var flights=emptyList<Flight>()
    private var lastPlanAt=0L
    /** 一条弹幕从右边缘走到完全离开的时长；电视上比手机慢一点更好读。 */
    var lifetime=12_000L
    var positionProvider: () -> Long = { 0L }
    var playingProvider: () -> Boolean = { false }
    init { isFocusable=false; isClickable=false; isSoundEffectsEnabled=false }
    /**
     * 应用「弹幕设置」：字号（sp）、不透明度（百分比）、滚动时长、最多显示行数。
     * 字号或透明度变化后文字位图需要重新生成，这里一并清掉旧缓存并按新参数重新排轨。
     */
    fun configure(sizeSp: Float,alpha: Int,speedMs: Long,lines: Int=5) {
        val px=android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_SP,sizeSp.coerceIn(12f,30f),metrics)
        val percent=alpha.coerceIn(40,100)
        val duration=speedMs.coerceIn(4_000L,30_000L)
        val rows=lines.coerceIn(1,9)
        if(px==textSize && percent==alphaPercent && duration==lifetime && rows==maxLines) return
        textSize=px; laneHeight=px*1.4f; alphaPercent=percent; lifetime=duration; maxLines=rows
        white.textSize=px; shadow.textSize=px
        white.color=Color.argb(238*percent/100,255,255,255)
        shadow.color=Color.argb(190*percent/100,0,0,0)
        emojiPaint.alpha=238*percent/100
        emojiShadow.colorFilter=PorterDuffColorFilter(Color.argb(190*percent/100,0,0,0),PorterDuff.Mode.SRC_IN)
        sprites.clear(); plan(); invalidate()
    }
    /** 换集时释放上一集的文字位图；拖动进度时不要调用，避免同一句反复重建。 */
    fun clearSprites() { sprites.clear() }
    fun submit(list: List<DanmakuComment>) { items=list; plan(); invalidate() }
    fun clear() { items=emptyList(); flights=emptyList(); invalidate() }
    override fun onSizeChanged(w: Int,h: Int,oldw: Int,oldh: Int) { super.onSizeChanged(w,h,oldw,oldh); plan() }
    private fun lanes()=minOf(maxLines,((height*0.44f)/laneHeight).toInt().coerceIn(1,9))
    private fun shown(text: String)=if(text.length>52) text.take(51)+"…" else text
    private fun emojiBitmap(asset: String): Bitmap? {
        emojiBitmaps[asset]?.let { return it }
        if(asset in missingEmoji) return null
        val bitmap=runCatching {
            context.assets.open("danmaku_emoji/$asset").use { BitmapFactory.decodeStream(it) }
        }.getOrNull()
        if(bitmap==null) missingEmoji+=asset else emojiBitmaps[asset]=bitmap
        return bitmap
    }
    /**
     * 把一条弹幕按 [DanmakuPart] 画进位图：文字和 64×64 表情图共用同一行，
     * 表情按字号等比缩放；图片缺失时退回原文占位符，保证不会出现空白弹幕。
     */
    private fun sprite(text: String,parts: List<DanmakuPart>): Bitmap {
        sprites[text]?.let { return it }
        val fm=white.fontMetrics
        val textHeight=fm.descent-fm.ascent
        val emojiSize=textSize*1.25f
        val contentHeight=max(textHeight,emojiSize)
        val pieces=ArrayList<Piece>(parts.size)
        var contentWidth=0f
        for(part in parts) {
            val bitmap=part.emojiAsset?.let(::emojiBitmap)
            val width=if(bitmap!=null) emojiSize else white.measureText(part.fallback)
            pieces+=Piece(part,width,bitmap)
            contentWidth+=width
        }
        val width=max(1,ceil(contentWidth).toInt()+ceil(shadowOffset).toInt()+1)
        val height=max(1,ceil(contentHeight).toInt()+ceil(shadowOffset).toInt()+1)
        val bitmap=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888)
        val canvas=Canvas(bitmap)
        val textTop=(contentHeight-textHeight)/2f
        val baseline=textTop-fm.ascent
        val emojiTop=(contentHeight-emojiSize)/2f
        var x=0f
        for(piece in pieces) {
            val image=piece.bitmap
            if(image!=null) {
                val destination=RectF(x,emojiTop,x+emojiSize,emojiTop+emojiSize)
                val shadowDestination=RectF(destination.left+shadowOffset,destination.top+shadowOffset,
                    destination.right+shadowOffset,destination.bottom+shadowOffset)
                canvas.drawBitmap(image,null,shadowDestination,emojiShadow)
                canvas.drawBitmap(image,null,destination,emojiPaint)
            } else {
                val fallback=piece.part.fallback
                canvas.drawText(fallback,x+shadowOffset,baseline+shadowOffset,shadow)
                canvas.drawText(fallback,x,baseline,white)
            }
            x+=piece.width
        }
        sprites[text]=bitmap
        return bitmap
    }
    private fun plan() {
        val width=width.toFloat()
        val now=positionProvider()
        lastPlanAt=now
        if(width<=0f || items.isEmpty()) { flights=emptyList(); return }
        // 只排当前时间前后的一小段：整集几百条全部预渲染会卡主线程，也会挤爆文字位图缓存。
        // 播放过程中每 2 秒补排一次，后面的弹幕进入 20 秒窗口时再生成。
        val lanes=arrayOfNulls<Flight>(lanes())
        val planned=ArrayList<Flight>(items.size)
        for(item in items) {
            // 已经在画面上消失的弹幕不再绘制，但仍参与 2 个滚动周期内的轨道占用计算，
            // 否则每条弹幕过期时，后面还在画面上的弹幕会突然换行。
            val expired=item.timeMs<now-lifetime
            if(item.timeMs<now-2*lifetime || item.timeMs>now+20_000L) continue
            val text=shown(item.text)
            val parts=danmakuParts(text)
            val size=sprite(text,parts).width.toFloat()+gapMinimum/2
            var lane=-1
            for(index in lanes.indices) {
                val previous=lanes[index]
                if(previous==null) { lane=index; break }
                val gap=item.timeMs-previous.item.timeMs
                if(gap>=lifetime) { lane=index; break }
                // 前一条还在走：两条同时在场的那段时间里都不能撞上。
                val left=(width+previous.width)*gap/lifetime-previous.width
                val right=width-(width+size)*(lifetime-gap)/lifetime
                if(left>=gapMinimum && right>=gapMinimum) { lane=index; break }
            }
            if(lane<0) continue
            val flight=Flight(item,lane,size,text,parts)
            lanes[lane]=flight; if(!expired) planned+=flight
        }
        flights=planned
    }
    override fun onDraw(canvas: Canvas) {
        val now=positionProvider()
        // 播放推进时补排新进入窗口的弹幕；暂停时位置不变，不重复计算。
        if(now<lastPlanAt || now-lastPlanAt>=2_000L) plan()
        if(flights.isEmpty()) return
        val width=width.toFloat()
        var active=false
        for(flight in flights) {
            val elapsed=now-flight.item.timeMs
            if(elapsed<0L) { if(elapsed>-1_000L) active=true; continue }
            if(elapsed>lifetime) continue
            active=true
            val bitmap=sprite(flight.text,flight.parts)
            val x=width-(width+flight.width)*elapsed/lifetime
            val top=topPadding+flight.lane*laneHeight
            canvas.drawBitmap(bitmap,x.toInt().toFloat(),(top+textSize-bitmap.height).toInt().toFloat(),null)
        }
        if(active && playingProvider()) postInvalidateOnAnimation()
    }
}
