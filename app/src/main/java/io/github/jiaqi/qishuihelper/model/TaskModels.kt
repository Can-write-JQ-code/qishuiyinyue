package io.github.jiaqi.qishuihelper.model

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo

enum class TaskPhase(val label: String, val isRunning: Boolean) {
    IDLE("空闲待命", false),
    STAGE1_SPLASH("启动与跳开屏", true),
    STAGE3_ENTER_AD("寻址首条广告", true),
    STAGE4_COUNTDOWN("等待倒计时结束", true),
    STAGE5_CLAIM("两步领奖连看中", true),
    COMPLETED("已完成畅听领取", false),
    STOPPED("已手动停止", false),
    ERROR("异常中止", false)
}

/**
 * 基于屏幕宽高百分比的动态搜索区域（借鉴 qishuiAScriptProject 的跨分辨率 ROI 区域设计）
 */
data class PctRegion(
    val leftPct: Float,
    val topPct: Float,
    val rightPct: Float,
    val bottomPct: Float
) {
    fun toScreenRect(screenW: Int, screenH: Int): Rect {
        val l = (screenW * leftPct).toInt().coerceIn(0, screenW - 2)
        val t = (screenH * topPct).toInt().coerceIn(0, screenH - 2)
        val r = (screenW * rightPct).toInt().coerceIn(l + 2, screenW)
        val b = (screenH * bottomPct).toInt().coerceIn(t + 2, screenH)
        return Rect(l, t, r, b)
    }

    companion object {
        /** 开屏广告右上角「跳过」区域 */
        val SPLASH_SKIP = PctRegion(0.70f, 0.04f, 0.98f, 0.14f)

        /** 激励广告右上角「领取成功 × / 倒计时」专属区 */
        val AD_CLAIM_TOP = PctRegion(0.54f, 0.025f, 0.99f, 0.135f)

        /** 中间弹窗「领取奖励 / 坚持退出 / 继续观看」专属区（严格隔离顶部状态栏与底部下载诱导按钮） */
        val AD_REWARD_MODAL = PctRegion(0.12f, 0.25f, 0.88f, 0.71f)

        /** 直播间右上角「更多直播」区域 */
        val LIVE_MORE_TOP_RIGHT = PctRegion(0.66f, 0.08f, 0.98f, 0.16f)

        /** 直播间最右上角关闭(×)按钮专属区（严格限制在最右侧 86%~99%，彻底避开主播关注与观众头像区） */
        val LIVE_CLOSE_TOP_RIGHT = PctRegion(0.86f, 0.04f, 0.99f, 0.12f)

        /** 直播间中部/底部特征词（「直播间 / 退出不看了」）区域 */
        val LIVE_CENTER_BOTTOM = PctRegion(0.08f, 0.40f, 0.92f, 0.82f)

        /** 续费会员弹窗底部「元开通 / 立即开通」检测区 */
        val MEMBERSHIP_PAY_BOTTOM = PctRegion(0.04f, 0.85f, 0.96f, 0.97f)

        /** 第三方落地页左上角「完成 / 关闭」检测区 */
        val LANDING_DONE_TOP_LEFT = PctRegion(0.01f, 0.04f, 0.22f, 0.13f)

        /** 首页/福利抽屉进首条广告按钮区（「提前领 / 领时长 / 立即解锁 / 免费听X天」） */
        val FIRST_AD_ENTRY_AREA = PctRegion(0.04f, 0.14f, 0.96f, 0.92f)
    }
}

data class RuleConfig(
    val targetPackage: String = DEFAULT_TARGET_PACKAGE,
    val maxRounds: Int = 6,
    val infiniteUntilQuotaEnd: Boolean = false,
    val singleAdTimeoutSec: Int = 45,
    val maxIdleRounds: Int = 6,
    val autoMuteMedia: Boolean = true
) {
    companion object {
        const val DEFAULT_TARGET_PACKAGE = "com.luna.music"
    }
}

data class TaskStatus(
    val phase: TaskPhase = TaskPhase.IDLE,
    val currentRound: Int = 0,
    val maxRounds: Int = 6,
    val infiniteMode: Boolean = false,
    val roundElapsedSec: Int = 0,
    val detail: String = "v2.0 局部极速裁剪 OCR + 确定性两步连看内核"
)

enum class MatchSource {
    NODE,
    OCR
}

data class TargetMatch(
    val matchedKeyword: String,
    val fullText: String,
    val bounds: Rect,
    val source: MatchSource,
    val clickableNode: AccessibilityNodeInfo? = null
)
