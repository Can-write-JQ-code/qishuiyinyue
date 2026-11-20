package io.github.jiaqi.qishuihelper.model

import android.graphics.Rect

enum class TaskPhase(val label: String, val isRunning: Boolean) {
    IDLE("空闲待命", false),
    LAUNCHING_APP("拉起目标应用", true),
    FINDING_ENTRY("寻址畅听入口", true),
    WAITING_AD_START("等待广告容器", true),
    WATCHING_AD("广告倒计时守护", true),
    POST_AD_CHECK("复核解锁结果", true),
    COMPLETED("已完成畅听解锁", false),
    STOPPED("已手动停止", false),
    ERROR("中止或频控保护", false)
}

data class RuleConfig(
    val targetPackage: String = DEFAULT_TARGET_PACKAGE,
    val maxRounds: Int = 3,
    val singleAdTimeoutSec: Int = 65,
    val autoMuteMedia: Boolean = true,
    val entryKeywords: List<String> = DEFAULT_ENTRY_KEYWORDS,
    val unlockedKeywords: List<String> = DEFAULT_UNLOCKED_KEYWORDS,
    val rewardDoneKeywords: List<String> = DEFAULT_REWARD_DONE_KEYWORDS,
    val nextAdKeywords: List<String> = DEFAULT_NEXT_AD_KEYWORDS,
    val noVideoKeywords: List<String> = DEFAULT_NO_VIDEO_KEYWORDS,
    val closeAdKeywords: List<String> = DEFAULT_CLOSE_AD_KEYWORDS,
    val neutralDismissKeywords: List<String> = DEFAULT_NEUTRAL_DISMISS_KEYWORDS,
    val adActivityMarkers: List<String> = DEFAULT_AD_ACTIVITY_MARKERS,
    val misJumpActivityMarkers: List<String> = DEFAULT_MISJUMP_ACTIVITY_MARKERS
) {
    companion object {
        const val DEFAULT_TARGET_PACKAGE = "com.luna.music"

        val DEFAULT_ENTRY_KEYWORDS = listOf(
            "看视频免费听",
            "看视频解锁",
            "看视频领",
            "看广告免费听",
            "免费听整首",
            "免费听",
            "免"
        )

        val DEFAULT_UNLOCKED_KEYWORDS = listOf(
            "免费畅听中",
            "全天免费畅听中",
            "今日免费畅听",
            "今日已解锁",
            "24:00前免费"
        )

        val DEFAULT_REWARD_DONE_KEYWORDS = listOf(
            "领取成功",
            "奖励已领取",
            "已领取奖励",
            "已获得奖励",
            "恭喜获得"
        )

        val DEFAULT_NEXT_AD_KEYWORDS = listOf(
            "再看一个",
            "再看1个",
            "再看一条",
            "继续观看",
            "解锁全天"
        )

        val DEFAULT_NO_VIDEO_KEYWORDS = listOf(
            "当前无新视频",
            "暂无视频",
            "暂无可用视频",
            "今日次数已达上限",
            "视频加载失败"
        )

        val DEFAULT_CLOSE_AD_KEYWORDS = listOf(
            "关闭广告",
            "关闭",
            "跳过"
        )

        val DEFAULT_NEUTRAL_DISMISS_KEYWORDS = listOf(
            "我知道了",
            "以后再说",
            "暂不开启",
            "暂不升级"
        )

        val DEFAULT_AD_ACTIVITY_MARKERS = listOf(
            "ExcitingVideo",
            "TTReward",
            "RewardVideo",
            "Stub_Standard",
            "AdActivity"
        )

        val DEFAULT_MISJUMP_ACTIVITY_MARKERS = listOf(
            "LivePlayer",
            "LunaDefaultLivePlayer",
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
    val maxRounds: Int = 3,
    val roundElapsedSec: Int = 0,
    val detail: String = "点击启动后将自动打开应用并静音领畅听"
)

enum class MatchSource {
    NODE,
    OCR
}

data class TargetMatch(
    val matchedKeyword: String,
    val fullText: String,
    val bounds: Rect,
    val source: MatchSource
)
