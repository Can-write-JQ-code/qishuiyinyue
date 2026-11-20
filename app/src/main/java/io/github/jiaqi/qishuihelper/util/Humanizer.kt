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
     * 轮询间隔：极速模式 1.15s ~ 1.55s，常规模式 2.1s ~ 2.9s
     */
    fun randomPollIntervalMs(fastMode: Boolean = true): Long {
        return if (fastMode) {
            Random.nextLong(1150L, 1550L)
        } else {
            Random.nextLong(2100L, 2900L)
        }
    }

    /**
     * 普通界面导航点击前延迟（跳开屏、切福利页、点领时长）：极速模式仅 200ms ~ 420ms
     */
    fun randomUiNavDelayMs(fastMode: Boolean = true): Long {
        return if (fastMode) {
            Random.nextLong(200L, 420L)
        } else {
            Random.nextLong(650L, 1200L)
        }
    }

    /**
     * 广告关键按钮点击前拟人延迟（关广告、点领取奖励连看）：极速模式 340ms ~ 720ms，兼顾自然度与爽快感
     */
    fun randomAdActionDelayMs(fastMode: Boolean = true): Long {
        return if (fastMode) {
            Random.nextLong(340L, 720L)
        } else {
            Random.nextLong(1000L, 2100L)
        }
    }

    /**
     * 点击后界面过渡等待时间
     */
    fun randomPostActionSettleMs(fastMode: Boolean = true): Long {
        return if (fastMode) {
            Random.nextLong(1100L, 1450L)
        } else {
            Random.nextLong(1800L, 2400L)
        }
    }

    /**
     * 返回键前短延迟
     */
    fun randomBackDelayMs(fastMode: Boolean = true): Long {
        return if (fastMode) {
            Random.nextLong(320L, 620L)
        } else {
            Random.nextLong(750L, 1250L)
        }
    }

    /**
     * 在目标矩形框内生成带中心聚集趋势的随机点击坐标（偏移控制在 ±8~15px 且不越出安全边界）
     */
    fun computeTapPoint(bounds: Rect, screenWidth: Int, screenHeight: Int): PointF {
        val safeMaxOffsetX = min(15f, max(2f, bounds.width() * 0.28f))
        val safeMaxOffsetY = min(12f, max(2f, bounds.height() * 0.28f))

        val offsetX = ((Random.nextFloat() - 0.5f) + (Random.nextFloat() - 0.5f)) * safeMaxOffsetX
        val offsetY = ((Random.nextFloat() - 0.5f) + (Random.nextFloat() - 0.5f)) * safeMaxOffsetY

        val rawX = bounds.exactCenterX() + offsetX
        val rawY = bounds.exactCenterY() + offsetY

        val clampedX = rawX.coerceIn(12f, max(13f, screenWidth - 12f))
        val clampedY = rawY.coerceIn(12f, max(13f, screenHeight - 12f))
        return PointF(clampedX, clampedY)
    }

    /**
     * 构造带随机按压时长（58ms ~ 118ms）及 0~2px 自然微滑移的手势
     */
    fun buildHumanTapGesture(point: PointF): GestureDescription {
        val durationMs = Random.nextLong(58L, 118L)
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
