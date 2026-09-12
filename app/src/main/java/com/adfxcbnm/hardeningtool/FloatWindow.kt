package com.adfxcbnm.hardeningtool

import android.animation.ValueAnimator
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.TextView

/**
 * 加固进行中的屏幕悬浮按钮（TYPE_APPLICATION_OVERLAY）。
 *
 * 动效体系（基于 motion-web 弹簧阻尼模型）：
 * - 欠阻尼弹簧入场/退场（scale + alpha, damping=0.88）
 * - 按压缩放反馈（press 0.85x → release spring 1.0x）
 * - 拖动速度耦合：按钮沿拖动方向倾斜（rotation proportional to velocity）
 * - 闲置呼吸：静止时微幅 scale 脉动（0.7Hz, 振幅 ±1.2%）
 * - 进度更新时脉冲反馈（scale bump 1.08x → spring back）
 */
object FloatWindow {

    private var windowManager: WindowManager? = null
    private var container: FrameLayout? = null
    private var floatView: View? = null
    private var percentText: TextView? = null
    private var ringView: ProgressRingView? = null
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private const val MOVE_SLOP = 12

    // Spring constants (motion-web §1)
    private const val SPRING_STIFFNESS = 65f
    private const val SPRING_DAMPING = 0.88f  // underdamped — visible overshoot
    private const val PRESS_SCALE = 0.85f
    private const val BUMP_SCALE = 1.08f
    private const val IDLE_AMPLITUDE = 0.012f  // ±1.2%
    private const val IDLE_FREQ = 0.7f         // Hz
    private const val LEAN_GAIN = 0.15f        // velocity → rotation gain
    private const val MAX_LEAN = 12f           // max rotation degrees

    // Spring state for scale
    private var scaleVel = 0f
    private var currentScale = 0f
    private var targetScale = 1f

    // Spring state for breathing
    private var breathPhase = 0f

    // Velocity tracking for lean
    private var lastDragX = 0f
    private var lastDragY = 0f
    private var lastDragTime = 0L
    private var dragVelX = 0f
    private var dragVelY = 0f

    // Animation loop
    private var animRunning = false
    private var animStartTime = 0L

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
            val radius = diameter / 2f

            // Container for the float button
            val frame = FrameLayout(context).apply {
                layoutParams = FrameLayout.LayoutParams(diameter + dp(context, 16).toInt(), diameter + dp(context, 16).toInt())
            }

            // Progress ring background
            val ring = ProgressRingView(context).apply {
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            }
            ringView = ring

            // Center button
            val tv = TextView(context).apply {
                text = "0%"
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                gravity = Gravity.CENTER
                width = diameter
                height = diameter
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(0xDD1B5E20.toInt())  // deeper green
                    setStroke(dp(context, 2).toInt(), 0x33FFFFFF)
                }
                // Shadow for depth
                elevation = dp(context, 6)
            }
            percentText = tv

            frame.addView(ring)
            frame.addView(tv, FrameLayout.LayoutParams(diameter, diameter).apply {
                gravity = Gravity.CENTER
            })

            val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else WindowManager.LayoutParams.TYPE_PHONE

            val pad = dp(context, 8).toInt()
            val params = WindowManager.LayoutParams(
                diameter + pad * 2,
                diameter + pad * 2,
                type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = dp(context, 8).toInt()
                y = dp(context, 120).toInt()
            }

            wm.addView(frame, params)

            // Entrance animation: spring scale from 0 to 1
            currentScale = 0f
            scaleVel = 0f
            targetScale = 1f
            frame.scaleX = 0f
            frame.scaleY = 0f
            frame.alpha = 0f
            startAnimLoop(frame)

            // Touch handling with spring feedback
            var startX = 0f
            var startY = 0f
            var startTouchX = 0f
            var startTouchY = 0f
            var moving = false

            frame.setOnTouchListener { v, event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        startX = params.x.toFloat()
                        startY = params.y.toFloat()
                        startTouchX = event.rawX
                        startTouchY = event.rawY
                        lastDragX = event.rawX
                        lastDragY = event.rawY
                        lastDragTime = System.currentTimeMillis()
                        dragVelX = 0f
                        dragVelY = 0f
                        moving = false
                        // Press feedback: spring to pressed scale
                        targetScale = PRESS_SCALE
                        scaleVel = 0f
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = event.rawX - startTouchX
                        val dy = event.rawY - startTouchY
                        if (Math.abs(dx) > MOVE_SLOP || Math.abs(dy) > MOVE_SLOP) moving = true
                        if (moving) {
                            // Track velocity for lean effect
                            val now = System.currentTimeMillis()
                            val dt = ((now - lastDragTime).coerceAtLeast(1)) / 1000f
                            dragVelX = (event.rawX - lastDragX) / dt
                            dragVelY = (event.rawY - lastDragY) / dt
                            lastDragX = event.rawX
                            lastDragY = event.rawY
                            lastDragTime = now

                            params.x = (startX + dx).toInt()
                            params.y = (startY + dy).toInt()
                            runCatching { wm.updateViewLayout(v, params) }
                        }
                        true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        if (!moving) {
                            // Spring bounce on tap
                            targetScale = BUMP_SCALE
                            scaleVel = 0f
                            // Launch app after a short delay for the bounce to show
                            mainHandler.postDelayed({
                                targetScale = 1f
                                val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)
                                    ?: Intent(context, MainActivity::class.java)
                                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                context.startActivity(intent)
                            }, 120)
                        } else {
                            // Snap to nearest edge after drag
                            snapToEdge(v, params, wm, context)
                            targetScale = 1f
                            scaleVel = 0f
                        }
                        dragVelX = 0f
                        dragVelY = 0f
                        true
                    }
                    else -> false
                }
            }

            windowManager = wm
            container = frame
            floatView = tv
        } catch (ignored: Exception) {
        }
    }

    private fun startAnimLoop(view: View) {
        if (animRunning) return
        animRunning = true
        animStartTime = System.currentTimeMillis()

        val animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = Long.MAX_VALUE
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                if (!animRunning) {
                    cancel()
                    return@addUpdateListener
                }
                updateSpring(view)
            }
        }
        animator.start()
    }

    /**
     * Spring-damper update (motion-web §1):
     *   vel += (target - x) * STIFFNESS * dt
     *   vel *= DAMPING
     *   x += vel * dt
     *
     * Plus idle breathing (§6) and velocity-coupled lean (§2).
     */
    private fun updateSpring(view: View) {
        val now = System.currentTimeMillis()
        val dt = ((now - animStartTime).coerceAtLeast(1)) / 1000f
        animStartTime = now

        // Scale spring
        val scaleDiff = targetScale - currentScale
        scaleVel += scaleDiff * SPRING_STIFFNESS * dt
        scaleVel *= SPRING_DAMPING
        currentScale += scaleVel * dt

        // Idle breathing (§6): subtle scale oscillation when not interacting
        breathPhase += IDLE_FREQ * dt * Math.PI.toFloat() * 2f
        val breathOffset = Math.sin(breathPhase.toDouble()).toFloat() * IDLE_AMPLITUDE
        val finalScale = currentScale + breathOffset

        // Velocity-coupled lean (§2): rotation proportional to drag velocity
        val velMag = Math.sqrt((dragVelX * dragVelX + dragVelY * dragVelY).toDouble()).toFloat()
        val leanAngle = clamp(velMag * LEAN_GAIN * if (dragVelX < 0) -1f else 1f, -MAX_LEAN, MAX_LEAN)

        // Apply transforms
        view.post {
            view.scaleX = finalScale
            view.scaleY = finalScale
            view.rotation = if (velMag > 50f) leanAngle else view.rotation * 0.92f  // decay lean
            view.alpha = clamp(currentScale, 0f, 1f)  // fade in with scale
        }

        // Decay drag velocity when not dragging
        dragVelX *= 0.92f
        dragVelY *= 0.92f
    }

    /**
     * Snap to nearest screen edge after drag ends.
     * Uses spring animation for the snap.
     */
    private fun snapToEdge(view: View, params: WindowManager.LayoutParams, wm: WindowManager, context: Context) {
        val displayMetrics = context.resources.displayMetrics
        val screenWidth = displayMetrics.widthPixels
        val midX = params.x + view.width / 2
        val targetX = if (midX < screenWidth / 2) dp(context, 8).toInt() else screenWidth - view.width - dp(context, 8).toInt()

        val startX = params.x
        val animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 300
            interpolator = android.view.animation.OvershootInterpolator(0.6f)
            addUpdateListener { anim ->
                params.x = (startX + (targetX - startX) * anim.animatedFraction).toInt()
                runCatching { wm.updateViewLayout(view, params) }
            }
        }
        animator.start()
    }

    fun update(progressText: String) {
        mainHandler.post {
            percentText?.text = progressText
            ringView?.setProgress(progressText.removeSuffix("%").toFloatOrNull() ?: 0f)
            // Pulse feedback on progress update (§8 - mini screen shake)
            targetScale = BUMP_SCALE
            scaleVel = 0f
            mainHandler.postDelayed({ targetScale = 1f }, 80)
        }
    }

    fun hide() {
        mainHandler.post { hideOnMain() }
    }

    private fun hideOnMain() {
        animRunning = false
        // Spring out animation
        container?.let { view ->
            val animator = ValueAnimator.ofFloat(1f, 0f).apply {
                duration = 250
                interpolator = DecelerateInterpolator()
                addUpdateListener { anim ->
                    val v = anim.animatedValue as Float
                    view.scaleX = v
                    view.scaleY = v
                    view.alpha = v
                }
            }
            animator.start()
            mainHandler.postDelayed({
                try { windowManager?.removeView(view) } catch (_: Exception) {}
            }, 260)
        } ?: run {
            try { floatView?.let { windowManager?.removeView(it) } } catch (_: Exception) {}
        }
        floatView = null
        container = null
        percentText = null
        ringView = null
        windowManager = null
    }

    private fun dp(context: Context, value: Int): Float =
        value * context.resources.displayMetrics.density

    private fun clamp(v: Float, min: Float, max: Float): Float =
        if (v < min) min else if (v > max) max else v
}

/**
 * Circular progress ring drawn around the float button.
 * Shows hardening progress with a smooth arc animation.
 */
class ProgressRingView(context: Context) : View(context) {

    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(3f)
        color = 0x66FFFFFF
        strokeCap = Paint.Cap.ROUND
    }
    private val progressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(3f)
        color = 0xAAFFFFFF.toInt()
        strokeCap = Paint.Cap.ROUND
    }
    private var progress = 0f
    private var animProgress = 0f

    fun setProgress(percent: Float) {
        progress = (percent / 100f).coerceIn(0f, 1f)
        // Smooth progress animation
        val animator = ValueAnimator.ofFloat(animProgress, progress).apply {
            duration = 300
            interpolator = DecelerateInterpolator()
            addUpdateUpdateListener { animProgress = it.animatedValue as Float; invalidate() }
        }
        animator.start()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val pad = ringPaint.strokeWidth / 2f
        val rect = android.graphics.RectF(pad, pad, width - pad, height - pad)
        // Background ring
        canvas.drawArc(rect, -90f, 360f, false, ringPaint)
        // Progress arc
        if (animProgress > 0f) {
            canvas.drawArc(rect, -90f, 360f * animProgress, false, progressPaint)
        }
    }

    private fun dp(v: Float): Float = v * resources.displayMetrics.density
}

private fun ValueAnimator.addUpdateUpdateListener(block: (android.animation.ValueAnimator) -> Unit) {
    addUpdateListener(block)
}
