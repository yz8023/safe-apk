package com.adfxcbnm.hardeningtool.motion

import androidx.compose.animation.core.*
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.composed
import kotlinx.coroutines.delay

/**
 * motion-web 弹簧阻尼动效体系 for Jetpack Compose.
 *
 * 核心原则：
 * §1 欠阻尼弹簧 — 有过冲回弹，读感「落地有重量」
 * §2 速度耦合 — 元素沿运动方向倾斜
 * §4 二次运动 — 环境元素随主运动弹性响应
 * §6 闲置呼吸 — 无交互时微幅脉动
 */

// Spring tokens
object MotionTokens {
    // Entrance spring: underdamped, visible overshoot
    val entranceSpring = spring<Float>(
        dampingRatio = 0.6f,   // underdamped — bouncy
        stiffness = 300f
    )
    // Press spring: snappy
    val pressSpring = spring<Float>(
        dampingRatio = 0.7f,
        stiffness = 500f
    )
    // Settle spring: calm
    val settleSpring = spring<Float>(
        dampingRatio = 0.85f,
        stiffness = 200f
    )
    // Exit: fast fade
    val exitDuration = 180
    // Stagger delay between cards
    const val staggerDelayMs = 60L
}

/**
 * 交错入场容器：每个子元素按 index 延迟入场，
 * 使用欠阻尼弹簧实现 scale+translateY+alpha 组合动效。
 */
@Composable
fun StaggeredEntrance(
    index: Int = 0,
    baseDelayMs: Long = MotionTokens.staggerDelayMs,
    content: @Composable () -> Unit
) {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(index * baseDelayMs)
        visible = true
    }

    val alpha by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(durationMillis = 300, easing = FastOutSlowInEasing),
        label = "stagger_alpha"
    )
    val offsetY by animateFloatAsState(
        targetValue = if (visible) 0f else 24f,
        animationSpec = MotionTokens.entranceSpring,
        label = "stagger_offset"
    )
    val scale by animateFloatAsState(
        targetValue = if (visible) 1f else 0.92f,
        animationSpec = MotionTokens.entranceSpring,
        label = "stagger_scale"
    )

    val density = LocalDensity.current.density
    Box(
        modifier = Modifier
            .graphicsLayer {
                this.alpha = alpha
                translationY = offsetY * density
                this.scaleX = scale
                this.scaleY = scale
            }
    ) {
        content()
    }
}

/**
 * 按压缩放修饰符：按下时 scale 缩小,释放时弹簧回弹。
 * motion-web §1 欠阻尼弹簧 + §8 按压反馈。
 */
fun Modifier.springPress(): Modifier = this.composed {
    var pressed by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.92f else 1f,
        animationSpec = MotionTokens.pressSpring,
        label = "press_scale"
    )

    this
        .scale(scale)
        .pointerInput(Unit) {
            detectTapGestures(
                onPress = {
                    pressed = true
                    tryAwaitRelease()
                    pressed = false
                }
            )
        }
}

/**
 * 闲置呼吸动效 (motion-web §6)：
 * 在没有交互时,元素微幅 scale 脉动,不同频率避免同步。
 */
fun Modifier.idleBreathing(
    frequencyHz: Float = 0.7f,
    amplitude: Float = 0.008f,
    enabled: Boolean = true
): Modifier {
    if (!enabled) return this
    return this.composed {
        val infiniteTransition = rememberInfiniteTransition(label = "breath")
        val breath by infiniteTransition.animateFloat(
            initialValue = 1f - amplitude,
            targetValue = 1f + amplitude,
            animationSpec = infiniteRepeatable(
                animation = tween(
                    durationMillis = (1000f / frequencyHz).toInt(),
                    easing = LinearEasing
                ),
                repeatMode = RepeatMode.Reverse
            ),
            label = "breath_scale"
        )
        scale(breath)
    }
}

/**
 * 脉冲反馈：进度更新或状态变化时,元素快速放大后回弹。
 * motion-web §8 — 短促冲击反馈。返回动画 scale 值。
 */
@Composable
fun pulseScale(trigger: Any?): Float {
    var bumped by remember { mutableStateOf(false) }
    LaunchedEffect(trigger) {
        bumped = true
        delay(80)
        bumped = false
    }
    val scale by animateFloatAsState(
        targetValue = if (bumped) 1.06f else 1f,
        animationSpec = MotionTokens.pressSpring,
        label = "pulse"
    )
    return scale
}

/**
 * 弹性滑入修饰符：从指定方向滑入,配合弹簧阻尼。
 */
fun Modifier.slideInSpring(
    direction: SlideDirection = SlideDirection.Up,
    delayMs: Long = 0
): Modifier = this.composed {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(delayMs)
        visible = true
    }

    val targetOffset = when (direction) {
        SlideDirection.Up, SlideDirection.Left -> if (visible) 0f else 40f
        SlideDirection.Down, SlideDirection.Right -> if (visible) 0f else -40f
    }
    val offset by animateFloatAsState(
        targetValue = targetOffset,
        animationSpec = MotionTokens.entranceSpring,
        label = "slide_offset"
    )

    val density = LocalDensity.current.density
    graphicsLayer {
        when (direction) {
            SlideDirection.Up, SlideDirection.Down -> translationY = offset * density
            SlideDirection.Left, SlideDirection.Right -> translationX = offset * density
        }
    }
}

enum class SlideDirection { Up, Down, Left, Right }
