package com.adfxcbnm.hardeningtool

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView

/**
 * 加固进行中的屏幕悬浮按钮（TYPE_APPLICATION_OVERLAY）。
 * 圆形可拖动：长按拖动位置，点击回到应用；拖动阈值防止点击误触。
 * 需要 SYSTEM_ALERT_WINDOW 权限；未授予时静默跳过（仅通知栏保活）。
 */
object FloatWindow {

    private var windowManager: WindowManager? = null
    private var floatView: TextView? = null
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private const val MOVE_SLOP = 12 // px，超过视为拖动

    fun canDraw(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(context)

    fun show(context: Context) {
        if (!canDraw(context)) return
        mainHandler.post { showOnMain(context) }
    }

    private fun showOnMain(context: Context) {
        hideOnMain()
        try {
            val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val diameter = dp(context, 56).toInt()
            val tv = TextView(context).apply {
                text = "0%"
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                gravity = Gravity.CENTER
                width = diameter
                height = diameter
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(0xDD2E7D32.toInt())
                }
            }
            val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else WindowManager.LayoutParams.TYPE_PHONE
            val params = WindowManager.LayoutParams(
                diameter,
                diameter,
                type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = 0
                y = dp(context, 120).toInt()
            }
            wm.addView(tv, params)
            // 记录拖动起点；参数引用在 drag listener 闭包内更新
            var startX = 0f
            var startY = 0f
            var startTouchX = 0f
            var startTouchY = 0f
            var moving = false
            tv.setOnTouchListener { v, event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        startX = params.x.toFloat()
                        startY = params.y.toFloat()
                        startTouchX = event.rawX
                        startTouchY = event.rawY
                        moving = false
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = event.rawX - startTouchX
                        val dy = event.rawY - startTouchY
                        if (Math.abs(dx) > MOVE_SLOP || Math.abs(dy) > MOVE_SLOP) moving = true
                        if (moving) {
                            params.x = (startX + dx).toInt()
                            params.y = (startY + dy).toInt()
                            runCatching { wm.updateViewLayout(v, params) }
                        }
                        true
                    }
                    MotionEvent.ACTION_UP -> {
                        if (!moving) {
                            val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)
                                ?: Intent(context, MainActivity::class.java)
                            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            context.startActivity(intent)
                        }
                        true
                    }
                    else -> false
                }
            }
            windowManager = wm
            floatView = tv
        } catch (ignored: Exception) {
        }
    }

    fun update(progressText: String) {
        mainHandler.post {
            floatView?.text = "$progressText"
        }
    }

    fun hide() {
        mainHandler.post { hideOnMain() }
    }

    private fun hideOnMain() {
        try {
            floatView?.let { windowManager?.removeView(it) }
        } catch (ignored: Exception) {
        }
        floatView = null
        windowManager = null
    }

    private fun dp(context: Context, value: Int): Float =
        value * context.resources.displayMetrics.density
}
