package io.github.jiaqi.qishuihelper.util

import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.PointF
import android.graphics.Rect
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

object Humanizer {

    /**
     * 轮询间隔随机抖动（2.4s ~ 3.6s），规避恒定频率轮询特征
     */
    fun randomPollIntervalMs(): Long = Random.nextLong(2400L, 3600L)

    /**
     * 识别到目标按钮后的拟人反应延迟（1.2s ~ 3.1s）
     */
    fun randomReactionDelayMs(): Long = Random.nextLong(1200L, 3100L)

    /**
     * 误跳偏离后触发返回前的短延迟（0.85s ~ 1.45s）
     */
    fun randomBackDelayMs(): Long = Random.nextLong(850L, 1450L)

    /**
     * 在目标矩形框内生成带中心聚集趋势的随机点击坐标（偏移控制在 ±8~15px 且不越出按钮安全边界）
     */
    fun computeTapPoint(bounds: Rect, screenWidth: Int, screenHeight: Int): PointF {
        val safeMaxOffsetX = min(15f, max(2f, bounds.width() * 0.28f))
        val safeMaxOffsetY = min(12f, max(2f, bounds.height() * 0.28f))

        // 两次均匀分布求均值，形成以中心为峰值的三角拟人分布
        val offsetX = ((Random.nextFloat() - 0.5f) + (Random.nextFloat() - 0.5f)) * safeMaxOffsetX
        val offsetY = ((Random.nextFloat() - 0.5f) + (Random.nextFloat() - 0.5f)) * safeMaxOffsetY

        val rawX = bounds.exactCenterX() + offsetX
        val rawY = bounds.exactCenterY() + offsetY

        val clampedX = rawX.coerceIn(12f, max(13f, screenWidth - 12f))
        val clampedY = rawY.coerceIn(12f, max(13f, screenHeight - 12f))
        return PointF(clampedX, clampedY)
    }

    /**
     * 构造带随机按压时长（65ms ~ 135ms）及 0~2px 自然手指微滑移的点击手势
     */
    fun buildHumanTapGesture(point: PointF): GestureDescription {
        val durationMs = Random.nextLong(65L, 136L)
        val driftX = (point.x + Random.nextInt(-2, 3)).coerceAtLeast(1f)
        val driftY = (point.y + Random.nextInt(-2, 3)).coerceAtLeast(1f)

        val path = Path().apply {
            moveTo(point.x, point.y)
            lineTo(driftX, driftY)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0L, durationMs)
        return GestureDescription.Builder()
            .addStroke(stroke)
            .build()
    }
}
