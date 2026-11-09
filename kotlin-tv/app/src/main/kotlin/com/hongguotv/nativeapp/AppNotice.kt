// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.accessibility.AccessibilityManager
import android.widget.PopupWindow
import android.widget.TextView

/** One non-interactive notification surface shared by every transient app message. */
internal class AppNotice(private val activity: Activity) {
    private val main=Handler(Looper.getMainLooper())
    private var popup: PopupWindow?=null
    private var active=false
    fun resume() { active=true }
    fun pause() { active=false;clear() }
    private val dismiss=Runnable { popup?.dismiss();popup=null }
    private fun dp(value: Int)=(value*activity.resources.displayMetrics.density).toInt()

    fun show(message: CharSequence, long: Boolean=false) {
        if(Looper.myLooper()!=Looper.getMainLooper()) { main.post { show(message,long) };return }
        clear()
        if(!active || activity.isFinishing || activity.isDestroyed) return
        val decor=activity.window.decorView
        val label=label(activity,message,decor.width)
        popup=PopupWindow(label,-2,-2,false).apply {
            isTouchable=false;isOutsideTouchable=false;isClippingEnabled=true
            inputMethodMode=PopupWindow.INPUT_METHOD_NOT_NEEDED
            showAtLocation(decor,Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL,0,dp(BOTTOM_MARGIN_DP))
        }
        val base=if(long) 5000 else 3000
        val manager=activity.getSystemService(AccessibilityManager::class.java)
        val duration=if(android.os.Build.VERSION.SDK_INT>=29)
            manager.getRecommendedTimeoutMillis(base,AccessibilityManager.FLAG_CONTENT_TEXT) else base
        main.postDelayed(dismiss,duration.toLong())
    }

    companion object {
        const val BOTTOM_MARGIN_DP=32
        /** Shared styling for transient messages and the playback continuation banner. */
        fun label(activity: Activity,message: CharSequence,availableWidth: Int): TextView {
            fun dp(value: Int)=(value*activity.resources.displayMetrics.density).toInt()
            return TextView(activity).apply {
                text=message;textSize=16f;setTextColor(TvStyle.text)
                gravity=Gravity.CENTER;setPadding(dp(22),dp(12),dp(22),dp(12))
                maxWidth=(availableWidth-dp(32)).coerceIn(dp(120),dp(620))
                background=TvStyle.shape(activity,TvStyle.raised,TvStyle.outline,18)
                elevation=dp(8).toFloat();isFocusable=false;isClickable=false
                accessibilityLiveRegion=View.ACCESSIBILITY_LIVE_REGION_POLITE
            }
        }
    }

    fun clear() {
        main.removeCallbacks(dismiss);popup?.dismiss();popup=null
    }
}
