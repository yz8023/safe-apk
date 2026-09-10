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
import android.view.View
import android.view.WindowManager
import android.widget.TextView

/**
 * 加固进行中的屏幕悬浮按钮（TYPE_APPLICATION_OVERLAY）。
 * 需要 SYSTEM_ALERT_WINDOW 权限；未授予时静默跳过（仅通知栏保活）。
 */
object FloatWindow {

    private var windowManager: WindowManager? = null
    private var floatView: TextView? = null
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

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
            val tv = TextView(context).apply {
                text = "加固中 0%"
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                gravity = Gravity.CENTER
                setPadding(dp(context, 20).toInt(), dp(context, 10).toInt(), dp(context, 20).toInt(), dp(context, 10).toInt())
                background = GradientDrawable().apply {
                    cornerRadius = dp(context, 24).toFloat()
                    setColor(0xDD2E7D32.toInt())
                }
                setOnClickListener {
                    val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)
                        ?: Intent(context, MainActivity::class.java)
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(intent)
                }
            }
            val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else WindowManager.LayoutParams.TYPE_PHONE
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                y = dp(context, 96).toInt()
            }
            wm.addView(tv, params)
            windowManager = wm
            floatView = tv
        } catch (ignored: Exception) {
        }
    }

    fun update(progressText: String) {
        mainHandler.post {
            floatView?.text = "加固中 $progressText"
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