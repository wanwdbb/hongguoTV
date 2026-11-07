// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.widget.CompoundButton

/** Capsule indicator; the containing settings row owns touch and remote activation. */
internal class SettingsSwitch(context: Context): CompoundButton(context) {
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG)
    init { buttonDrawable=null; setWillNotDraw(false) }
    override fun setChecked(checked: Boolean) { super.setChecked(checked); invalidate() }
    override fun onDraw(canvas: Canvas) {
        val w=width.toFloat();val h=height.toFloat();val inset=h*.1f;val radius=h/2-inset
        paint.color=if(isChecked) TvStyle.accent else TvStyle.outline
        canvas.drawRoundRect(0f,0f,w,h,h/2,h/2,paint)
        paint.color=Color.WHITE
        canvas.drawCircle(if(isChecked) w-h/2 else h/2,h/2,radius,paint)
    }
}
