package io.github.jiaqi.qishuihelper.model

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo

enum class TaskPhase(val label: String, val isRunning: Boolean) {
    IDLE("空闲待命", false),
    LAUNCHING_APP("拉起汽水音乐", true),
    NAVIGATING_WELFARE("进入福利中心", true),
    FINDING_ENTRY("点击领时长入口", true),
    WATCHING_AD("看广告守护中", true),
    HANDLING_POPUP("处理弹窗/连看", true),
    COMPLETED("已完成畅听领取", false),
    STOPPED("已手动停止", false),
    ERROR("中止或频控保护", false)
}

data class RuleConfig(
    val targetPackage: String = DEFAULT_TARGET_PACKAGE,
    val maxRounds: Int = 2,
    val singleAdTimeoutSec: Int = 65,
    val autoMuteMedia: Boolean = true,
    val entryKeywords: List<String> = DEFAULT_ENTRY_KEYWORDS,
    val unlockedKeywords: List<String> = DEFAULT_UNLOCKED_KEYWORDS,
    val rewardDoneKeywords: List<String> = DEFAULT_REWARD_DONE_KEYWORDS,
    val nextAdKeywords: List<String> = DEFAULT_NEXT_AD_KEYWORDS,
    val noVideoKeywords: List<String> = DEFAULT_NO_VIDEO_KEYWORDS,
    val welfareTabKeywords: List<String> = DEFAULT_WELFARE_TAB_KEYWORDS,
    val closeAdKeywords: List<String> = DEFAULT_CLOSE_AD_KEYWORDS,
    val exitAdConfirmKeywords: List<String> = DEFAULT_EXIT_AD_CONFIRM_KEYWORDS,
    val liveExitConfirmKeywords: List<String> = DEFAULT_LIVE_EXIT_CONFIRM_KEYWORDS,
    val neutralDismissKeywords: List<String> = DEFAULT_NEUTRAL_DISMISS_KEYWORDS,
    val promoBackKeywords: List<String> = DEFAULT_PROMO_BACK_KEYWORDS,
    val adActivityMarkers: List<String> = DEFAULT_AD_ACTIVITY_MARKERS,
    val misJumpActivityMarkers: List<String> = DEFAULT_MISJUMP_ACTIVITY_MARKERS
) {
    companion object {
        const val DEFAULT_TARGET_PACKAGE = "com.luna.music"

        /**
         * 汽水音乐 v19~v21 真实畅听领取入口词（福利中心「领时长」为主，兼容首页弹窗与顶部入口）
         */
        val DEFAULT_ENTRY_KEYWORDS = listOf(
            "领时长",
            "看视频领时长",
            "看广告领时长",
            "领免费时长",
            "立得全天畅听",
            "立即解锁今日畅听",
            "看视频免费听",
            "看视频解锁",
            "免费听整首",
            "立即解锁"
        )

        val DEFAULT_WELFARE_TAB_KEYWORDS = listOf(
            "福利"
        )

        val DEFAULT_UNLOCKED_KEYWORDS = listOf(
            "今日已领完",
            "今日次数已达上限",
            "全天免费畅听中",
            "今日畅听已解锁"
        )

        /**
         * 激励广告倒计时结束标识（兼容 UIView desc="领取成功，关闭，按钮" 与右上角胶囊）
         */
        val DEFAULT_REWARD_DONE_KEYWORDS = listOf(
            "领取成功，关闭",
            "领取成功",
            "奖励已领取",
            "已领取奖励",
            "已获得奖励"
        )

        /**
         * 广告结束后中间弹窗继续看下一条的按钮词（汽水音乐弹窗主按钮常为「领取奖励 / 再看一个 / 继续观看」）
         */
        val DEFAULT_NEXT_AD_KEYWORDS = listOf(
            "再看一个",
            "再看1个",
            "继续观看",
            "领取奖励"
        )

        val DEFAULT_EXIT_AD_CONFIRM_KEYWORDS = listOf(
            "坚持退出",
            "残忍离开",
            "放弃奖励"
        )

        val DEFAULT_LIVE_EXIT_CONFIRM_KEYWORDS = listOf(
            "退出不看了",
            "退出直播"
        )

        val DEFAULT_NO_VIDEO_KEYWORDS = listOf(
            "当前无新视频",
            "暂无视频",
            "暂无可用视频",
            "今日次数已达上限",
            "视频加载失败"
        )

        val DEFAULT_CLOSE_AD_KEYWORDS = listOf(
            "领取成功，关闭",
            "关闭广告",
            "跳过",
            "关闭"
        )

        val DEFAULT_NEUTRAL_DISMISS_KEYWORDS = listOf(
            "稍后再说",
            "以后再说",
            "我知道了",
            "开心收下",
            "暂不开启",
            "暂不升级"
        )

        val DEFAULT_PROMO_BACK_KEYWORDS = listOf(
            "开会员听整月",
            "购买汽水会员",
            "立即抢购",
            "为汽水音乐评分",
            "恭喜获得第"
        )

        val DEFAULT_AD_ACTIVITY_MARKERS = listOf(
            "ExcitingVideo",
            "LunaRewardActivity",
            "TTReward",
            "RewardVideo",
            "Stub_Standard"
        )

        val DEFAULT_MISJUMP_ACTIVITY_MARKERS = listOf(
            "LunaDefaultLivePlayer",
            "LivePlayer",
            "Browser",
            "WebView",
            "LandPage",
            "Commerce"
        )
    }
}

data class TaskStatus(
    val phase: TaskPhase = TaskPhase.IDLE,
    val currentRound: Int = 0,
    val maxRounds: Int = 2,
    val roundElapsedSec: Int = 0,
    val detail: String = "支持一键全自动启动，或开启悬浮窗后去汽水音乐内点 ▶ 启动"
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
