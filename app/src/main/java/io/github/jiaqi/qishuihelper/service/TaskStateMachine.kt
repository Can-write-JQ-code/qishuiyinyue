package io.github.jiaqi.qishuihelper.service

import android.graphics.Rect
import io.github.jiaqi.qishuihelper.model.RuleConfig
import io.github.jiaqi.qishuihelper.model.TargetMatch
import io.github.jiaqi.qishuihelper.model.TaskPhase
import io.github.jiaqi.qishuihelper.model.TaskStatus
import io.github.jiaqi.qishuihelper.ocr.OcrLineRecord
import io.github.jiaqi.qishuihelper.ocr.ScreenOcrHelper
import io.github.jiaqi.qishuihelper.util.AppLogBus
import io.github.jiaqi.qishuihelper.util.Humanizer
import io.github.jiaqi.qishuihelper.util.VolumeGuard
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlin.math.min

class TaskStateMachine(
    private val host: AdHelperAccessibilityService,
    private val ocrHelper: ScreenOcrHelper,
    private val volumeGuard: VolumeGuard
) {

    suspend fun execute(config: RuleConfig, startedFromOverlay: Boolean = false) {
        val fast = config.fastMode
        var completedAds = 0
        var activeAdStartMs = 0L
        var countedCurrentAd = false
        var closeAttemptsForCurrentAd = 0
        var goldCoinAttempts = 0
        var externalJumpStreak = 0
        var unknownTicks = 0

        fun publish(newPhase: TaskPhase, detail: String, elapsedSec: Int = 0) {
            val displayRound = if (newPhase == TaskPhase.WATCHING_AD) {
                (completedAds + 1).coerceAtMost(config.maxRounds)
            } else {
                completedAds
            }
            host.onStatusUpdated(
                TaskStatus(
                    phase = newPhase,
                    currentRound = displayRound,
                    maxRounds = config.maxRounds,
                    roundElapsedSec = elapsedSec,
                    detail = detail
                )
            )
        }

        val taskStartMs = System.currentTimeMillis()
        val maxTotalDurationMs = ((config.maxRounds * (config.singleAdTimeoutSec + 35)) + 90) * 1000L

        try {
            AppLogBus.info("====== 启动极速连看 v1.3（目标轮数=${config.maxRounds}, 极速=${fast}, 仅领今日=${config.stopOnTomorrow}）======")
            volumeGuard.muteIfNeeded(config.autoMuteMedia)

            val initialPkg = host.getCurrentPackageName()
            if (startedFromOverlay && initialPkg == config.targetPackage) {
                AppLogBus.info("已在 ${config.targetPackage} 前台，直接就地接管")
            } else {
                publish(TaskPhase.LAUNCHING_APP, "正在打开 ${config.targetPackage}...")
                if (!host.launchTargetApp(config.targetPackage)) {
                    publish(TaskPhase.ERROR, "未检测到应用(${config.targetPackage})，请确认已安装")
                    return
                }
                delay(2500L)
            }

            var loopIndex = 0
            while (true) {
                loopIndex++
                if (System.currentTimeMillis() - taskStartMs > maxTotalDurationMs) {
                    publish(TaskPhase.ERROR, "总运行时间超过安全上限，已自动中止")
                    return
                }

                val (screenW, screenH) = host.getScreenSize()
                val fgPkg = host.getCurrentPackageName()
                val topAct = host.getCurrentActivityClass()
                val topWin = host.getLatestWindowClass()

                // 场景 1：跳出汽水音乐保护（若在桌面则立即重拉，不发无意义返回键）
                if (fgPkg.isNotEmpty() &&
                    fgPkg != config.targetPackage &&
                    fgPkg != host.packageName &&
                    fgPkg != "com.android.systemui"
                ) {
                    val isLauncher = fgPkg.contains("launcher", ignoreCase = true) ||
                        fgPkg.contains("home", ignoreCase = true)
                    if (isLauncher || loopIndex <= 2 || externalJumpStreak >= 1) {
                        AppLogBus.info("当前在外部应用/桌面($fgPkg)，立即切回汽水音乐")
                        host.launchTargetApp(config.targetPackage)
                        externalJumpStreak = 0
                        delay(1500L)
                        continue
                    }
                    externalJumpStreak++
                    AppLogBus.warn("拦截广告外跳($fgPkg)，执行返回拉回")
                    host.performGlobalBack("拦截外跳:$fgPkg", fast)
                    delay(1300L)
                    continue
                } else {
                    externalJumpStreak = 0
                }

                val topRegion: (Rect) -> Boolean = { rect -> rect.bottom <= (screenH * 0.22f).toInt() }
                val modalRegion: (Rect) -> Boolean = { rect ->
                    rect.top >= (screenH * 0.24f).toInt() &&
                        rect.bottom <= (screenH * 0.80f).toInt() &&
                        rect.left >= (screenW * 0.12f).toInt() &&
                        rect.right <= (screenW * 0.88f).toInt()
                }

                // ====== 零截屏节点极速通道 0：开屏广告秒跳（截图5：AdActivity 含「开通SVIP免广告」「跳过 4」） ======
                val isSplashAdActivity = topAct.endsWith(".AdActivity") || topAct == "AdActivity"
                if (isSplashAdActivity || System.currentTimeMillis() - taskStartMs < 12_000L) {
                    val fastSplashSkip = host.findNodeMatch(
                        keywords = listOf("跳过"),
                        excludeContaining = listOf("奖励已领取"),
                        regionPredicate = { it.bottom <= (screenH * 0.18f).toInt() && it.left >= (screenW * 0.65f).toInt() }
                    )
                    if (fastSplashSkip != null && fastSplashSkip.fullText.length <= 8) {
                        AppLogBus.info("⚡ 秒跳冷启动开屏广告：「${fastSplashSkip.fullText}」")
                        host.performSmartTap(fastSplashSkip, "秒跳开屏广告", Humanizer.randomUiNavDelayMs(fast))
                        delay(1000L)
                        continue
                    }
                }

                // ====== 零截屏节点极速通道 A：激励视频倒计时中（截图3：「19秒后可领取奖励」） ======
                val fastNodeCountdown = host.findNodeMatch(
                    keywords = listOf("秒后", "秒可领", "后可领", "后可获"),
                    regionPredicate = topRegion
                )
                val fastNodeExitBtn = host.findNodeMatch(config.exitAdConfirmKeywords, regionPredicate = modalRegion)
                val fastNodeNextBtn = host.findNodeMatch(
                    keywords = config.nextAdKeywords,
                    excludeContaining = listOf("秒后", "后可", "提前解锁下一天", "再看1次", "再看2次"),
                    regionPredicate = modalRegion
                )

                if (fastNodeCountdown != null && fastNodeExitBtn == null && fastNodeNextBtn == null) {
                    volumeGuard.muteIfNeeded(config.autoMuteMedia)
                    if (activeAdStartMs == 0L) {
                        activeAdStartMs = System.currentTimeMillis()
                        countedCurrentAd = false
                        closeAttemptsForCurrentAd = 0
                    }
                    val elapsedSec = ((System.currentTimeMillis() - activeAdStartMs) / 1000L).toInt()
                    publish(
                        TaskPhase.WATCHING_AD,
                        "第 ${(completedAds + 1).coerceAtMost(config.maxRounds)}/${config.maxRounds} 轮倒计时 (${fastNodeCountdown.fullText.take(10)})",
                        elapsedSec
                    )
                    delay(Humanizer.randomPollIntervalMs(fast))
                    continue
                }

                // ====== 零截屏节点极速通道 B：首次出现「领取成功，关闭」（截图2） ======
                // 注意：仅在 closeAttemptsForCurrentAd == 0 时触发！点过一次关闭后，下一轮必须走 OCR 以识别截图1的 Canvas 连看弹窗！
                if (closeAttemptsForCurrentAd == 0) {
                    val fastNodeRewardDone = host.findNodeMatch(
                        keywords = config.rewardDoneKeywords,
                        excludeContaining = listOf("秒后", "秒可领", "后可"),
                        regionPredicate = topRegion
                    )
                    if (fastNodeRewardDone != null && fastNodeCountdown == null && fastNodeExitBtn == null && fastNodeNextBtn == null) {
                        if (!countedCurrentAd) {
                            completedAds++
                            countedCurrentAd = true
                            AppLogBus.info("⚡ 节点快检：第 $completedAds/${config.maxRounds} 轮奖励达成「${fastNodeRewardDone.fullText}」")
                        }
                        publish(TaskPhase.HANDLING_POPUP, "第 $completedAds 轮完成，秒点右上角 × 唤起连看框...")
                        val closeTarget = host.findAdCloseNodeTarget(fastNodeRewardDone.bounds, screenW, screenH) ?: fastNodeRewardDone
                        closeAttemptsForCurrentAd = 1
                        host.performSmartTap(
                            match = closeTarget,
                            reason = "极速关闭:${closeTarget.matchedKeyword}",
                            delayMs = Humanizer.randomAdActionDelayMs(fast)
                        )
                        delay(Humanizer.randomPostActionSettleMs(fast))
                        continue
                    }
                }

                // ====== 零截屏节点极速通道 C：半屏畅听抽屉（如「再看2个提前领」）直接秒点 ======
                if (!isSplashAdActivity && completedAds < config.maxRounds && activeAdStartMs == 0L) {
                    val fastSheetEntry = host.findNodeMatch(
                        keywords = listOf("提前领", "领时长", "看视频免费听"),
                        excludeContaining = listOf("金币", "宝箱", "元")
                    )
                    if (fastSheetEntry != null) {
                        publish(TaskPhase.FINDING_ENTRY, "秒点「${fastSheetEntry.fullText}」开启第 ${completedAds + 1} 轮...")
                        AppLogBus.info("⚡ 节点快检命中入口：「${fastSheetEntry.fullText}」")
                        host.performSmartTap(
                            match = fastSheetEntry,
                            reason = "极速点击入口:${fastSheetEntry.matchedKeyword}",
                            delayMs = Humanizer.randomUiNavDelayMs(fast)
                        )
                        activeAdStartMs = System.currentTimeMillis()
                        countedCurrentAd = false
                        closeAttemptsForCurrentAd = 0
                        delay(Humanizer.randomPostActionSettleMs(fast))
                        continue
                    }
                }

                // ====== 双引擎全量感知（无障碍节点 + 静默截屏 OCR） ======
                val nodePreview = host.collectNodeTextPreview(6)
                val ocrLines = host.captureScreenOcrLines()
                val ocrPreview = ocrLines.take(8).joinToString(", ") { it.text.take(14) }
                AppLogBus.info(
                    "画面[${topAct.substringAfterLast('.').ifEmpty { topWin.substringAfterLast('.') }}] " +
                        "节点=$nodePreview | OCR(${ocrLines.size})=[$ocrPreview]"
                )

                // 场景 2：频控或「当前无新视频」兜底
                val noVideoHit = findHybrid(config.noVideoKeywords, ocrLines)
                if (noVideoHit != null) {
                    if (completedAds > 0) {
                        publish(TaskPhase.COMPLETED, "已连看 $completedAds 轮（${noVideoHit.matchedKeyword}）")
                    } else {
                        publish(TaskPhase.ERROR, "平台提示「${noVideoHit.matchedKeyword}」，请稍后再试")
                    }
                    return
                }

                // 场景 3：直播退出二次确认（「退出不看了」）
                val liveConfirmHit = findHybrid(config.liveExitConfirmKeywords, ocrLines)
                if (liveConfirmHit != null) {
                    host.performSmartTap(
                        match = liveConfirmHit,
                        reason = "确认退出直播:${liveConfirmHit.matchedKeyword}",
                        delayMs = Humanizer.randomUiNavDelayMs(fast)
                    )
                    delay(1100L)
                    continue
                }

                // 场景 4：误入直播间或外链页
                val isMisJumpAct = config.misJumpActivityMarkers.any {
                    topAct.contains(it, ignoreCase = true)
                }
                val liveTopMarker = findHybrid(
                    keywords = listOf("更多直播", "本场点赞"),
                    ocrLines = ocrLines,
                    regionPredicate = { it.bottom <= (screenH * 0.30f).toInt() }
                )
                if (isMisJumpAct || liveTopMarker != null) {
                    val liveCloseBtn = findHybrid(
                        keywords = listOf("关闭"),
                        ocrLines = ocrLines,
                        regionPredicate = {
                            it.bottom <= (screenH * 0.20f).toInt() && it.left >= (screenW * 0.70f).toInt()
                        }
                    )
                    if (liveCloseBtn != null) {
                        host.performSmartTap(liveCloseBtn, "关闭直播间", Humanizer.randomUiNavDelayMs(fast))
                    } else {
                        host.performGlobalBack("退出直播间/偏离页", fast)
                    }
                    delay(1200L)
                    continue
                }

                // 区分真正的激励视频（顶部必有「反馈」或倒计时，且不是开屏 AdActivity）
                val countdownHit = findHybrid(
                    keywords = listOf("秒后", "秒可领", "后可领", "后可获"),
                    ocrLines = ocrLines,
                    regionPredicate = topRegion
                )
                val inRewardAdActivity = !isSplashAdActivity && config.adActivityMarkers.any {
                    topAct.contains(it, ignoreCase = true)
                }
                val adFeedbackHit = findHybrid(
                    keywords = listOf("反馈"),
                    ocrLines = ocrLines,
                    regionPredicate = topRegion
                )
                val isInsideRewardAd = inRewardAdActivity || countdownHit != null || adFeedbackHit != null

                // 场景 5：核心无缝连看弹窗处理（完全对齐截图1：再看1个视频提前得 / 第3日免费听 / 绿色[领取奖励] / 灰色[坚持退出]）
                val exitModalBtn = findHybrid(
                    keywords = config.exitAdConfirmKeywords,
                    ocrLines = ocrLines,
                    regionPredicate = modalRegion
                )
                val nextModalBtn = findHybrid(
                    keywords = config.nextAdKeywords,
                    ocrLines = ocrLines,
                    excludeContaining = listOf("秒后", "秒可领", "后可", "提前解锁下一天", "再看1次", "再看2次", "再看3次"),
                    regionPredicate = modalRegion
                )
                val modalHeadlineHit = if (isInsideRewardAd) {
                    findHybrid(
                        keywords = listOf("视频提前得", "日免费听", "天免费听"),
                        ocrLines = ocrLines,
                        regionPredicate = { rect ->
                            rect.top >= (screenH * 0.28f).toInt() && rect.bottom <= (screenH * 0.56f).toInt()
                        }
                    )
                } else {
                    null
                }

                if (exitModalBtn != null || (isInsideRewardAd && (nextModalBtn != null || modalHeadlineHit != null))) {
                    // 情况 A：顶部仍在倒计时，点「继续观看」保住本轮广告
                    if (countdownHit != null) {
                        if (nextModalBtn != null) {
                            AppLogBus.info("倒计时未结束，点击「${nextModalBtn.matchedKeyword}」继续播放")
                            host.performSmartTap(
                                match = nextModalBtn,
                                reason = "继续播放当前广告",
                                delayMs = Humanizer.randomUiNavDelayMs(fast)
                            )
                        }
                        delay(1100L)
                        continue
                    }

                    // 情况 B：本条广告已播完弹出的截图1连看弹窗
                    if (!countedCurrentAd && activeAdStartMs > 0L) {
                        completedAds++
                        countedCurrentAd = true
                        AppLogBus.info("确认完成第 $completedAds/${config.maxRounds} 轮广告")
                    }

                    val tomorrowHint = findHybrid(
                        keywords = listOf("明日免费听", "后日免费听", "第2日", "第3日", "第4日", "第5日"),
                        ocrLines = ocrLines
                    )
                    val shouldStopForTomorrow = config.stopOnTomorrow && tomorrowHint != null

                    if (completedAds < config.maxRounds && !shouldStopForTomorrow) {
                        publish(TaskPhase.HANDLING_POPUP, "无缝连看第 ${completedAds + 1}/${config.maxRounds} 轮广告...")
                        val delayMs = Humanizer.randomAdActionDelayMs(fast)
                        when {
                            nextModalBtn != null -> {
                                AppLogBus.info("⚡ 无缝连看：点击绿色主按钮「${nextModalBtn.fullText}」进入第 ${completedAds + 1} 轮")
                                host.performSmartTap(
                                    match = nextModalBtn,
                                    reason = "连看第${completedAds + 1}轮:${nextModalBtn.matchedKeyword}",
                                    delayMs = delayMs
                                )
                            }
                            exitModalBtn != null -> {
                                // 截图1实测几何关系：绿色「领取奖励」中心正位于「坚持退出」上方 6.7% 屏幕高度处
                                val centerY = (exitModalBtn.bounds.centerY() - screenH * 0.067f).toInt()
                                val ctaRect = Rect(
                                    (screenW * 0.32f).toInt(),
                                    centerY - (screenH * 0.022f).toInt(),
                                    (screenW * 0.68f).toInt(),
                                    centerY + (screenH * 0.022f).toInt()
                                )
                                AppLogBus.info("⚡ 无缝连看：按「坚持退出」上方精准坐标点击绿色「领取奖励」进入第 ${completedAds + 1} 轮")
                                host.performHumanTap(ctaRect, "连看绿色按钮(相对定位)", delayMs)
                            }
                            else -> {
                                // 截图1实测几何比例：绿色「领取奖励」位于屏幕水平居中、垂直 52.5%~56.5%
                                val ctaRect = Rect(
                                    (screenW * 0.32f).toInt(),
                                    (screenH * 0.525f).toInt(),
                                    (screenW * 0.68f).toInt(),
                                    (screenH * 0.565f).toInt()
                                )
                                AppLogBus.info("⚡ 无缝连看：点击弹窗居中绿色「领取奖励」进入第 ${completedAds + 1} 轮")
                                host.performHumanTap(ctaRect, "连看绿色按钮(居中定位)", delayMs)
                            }
                        }
                        activeAdStartMs = System.currentTimeMillis()
                        countedCurrentAd = false
                        closeAttemptsForCurrentAd = 0
                        delay(Humanizer.randomPostActionSettleMs(fast))
                        continue
                    } else {
                        // 已看满设定轮数 -> 点击截图1中的「坚持退出」结束任务
                        publish(TaskPhase.HANDLING_POPUP, "已看满 $completedAds 轮，点击「坚持退出」收尾...")
                        val delayMs = Humanizer.randomAdActionDelayMs(fast)
                        if (exitModalBtn != null) {
                            host.performSmartTap(exitModalBtn, "看满轮数点坚持退出", delayMs)
                        } else {
                            // 截图1「坚持退出」实测位置：水平居中，垂直 60.2%~62.2%
                            val exitRect = Rect(
                                (screenW * 0.42f).toInt(),
                                (screenH * 0.600f).toInt(),
                                (screenW * 0.58f).toInt(),
                                (screenH * 0.624f).toInt()
                            )
                            host.performHumanTap(exitRect, "看满轮数点坚持退出(定位)", delayMs)
                        }
                        activeAdStartMs = 0L
                        delay(Humanizer.randomPostActionSettleMs(fast))
                        continue
                    }
                }

                // 场景 6：单轮广告结束（截图2：右上角「领取成功 ×」），点击拉起截图1连看弹窗
                val rewardDoneHit = findHybrid(
                    keywords = config.rewardDoneKeywords,
                    ocrLines = ocrLines,
                    excludeContaining = listOf("秒后", "秒可领", "后可"),
                    regionPredicate = topRegion
                )
                if (rewardDoneHit != null && countdownHit == null) {
                    if (!countedCurrentAd) {
                        completedAds++
                        countedCurrentAd = true
                        AppLogBus.info("第 $completedAds/${config.maxRounds} 轮奖励达成[${rewardDoneHit.source}]：「${rewardDoneHit.fullText}」")
                    }
                    publish(TaskPhase.HANDLING_POPUP, "第 $completedAds 轮完成，点击「领取成功 ×」...")

                    val closeNodeTarget = host.findAdCloseNodeTarget(rewardDoneHit.bounds, screenW, screenH)
                    val topRightCloseText = findHybrid(
                        keywords = config.closeAdKeywords,
                        ocrLines = ocrLines,
                        excludeContaining = listOf("秒后"),
                        regionPredicate = {
                            it.bottom <= (screenH * 0.18f).toInt() && it.left >= (screenW * 0.55f).toInt()
                        }
                    )

                    val actionDelay = Humanizer.randomAdActionDelayMs(fast)
                    closeAttemptsForCurrentAd++
                    when {
                        closeNodeTarget != null -> {
                            host.performSmartTap(closeNodeTarget, "节点关闭:${closeNodeTarget.matchedKeyword}", actionDelay)
                        }
                        topRightCloseText != null -> {
                            host.performSmartTap(topRightCloseText, "文字关闭:${topRightCloseText.matchedKeyword}", actionDelay)
                        }
                        closeAttemptsForCurrentAd == 1 && rewardDoneHit.bounds.left >= (screenW * 0.55f).toInt() -> {
                            // 截图2：「领取成功 ×」胶囊整体可点，偏右点击 × 位置更稳
                            val xCloseLeft = rewardDoneHit.bounds.right - 12
                            val xCloseRight = min(screenW - 16, rewardDoneHit.bounds.right + 58)
                            val rightXRect = if (xCloseRight > xCloseLeft + 14) {
                                Rect(xCloseLeft, rewardDoneHit.bounds.top, xCloseRight, rewardDoneHit.bounds.bottom)
                            } else {
                                rewardDoneHit.bounds
                            }
                            host.performHumanTap(rightXRect, "点击领取成功×胶囊", actionDelay)
                        }
                        closeAttemptsForCurrentAd == 2 -> {
                            // 截图2实测「领取成功 ×」右上角胶囊位置 (76%~95% W, 4.8%~7.4% H)
                            val capsuleRect = Rect(
                                (screenW * 0.88f).toInt(),
                                (screenH * 0.050f).toInt(),
                                (screenW * 0.945f).toInt(),
                                (screenH * 0.074f).toInt()
                            )
                            host.performHumanTap(capsuleRect, "右上角×精准坐标", actionDelay)
                        }
                        else -> {
                            host.performGlobalBack("奖励已达成返回触发弹窗", fast)
                        }
                    }
                    delay(Humanizer.randomPostActionSettleMs(fast))
                    continue
                }

                // 场景 7：激励视频仍在倒计时播放中（截图3）
                if (isInsideRewardAd) {
                    volumeGuard.muteIfNeeded(config.autoMuteMedia)
                    if (activeAdStartMs == 0L) {
                        activeAdStartMs = System.currentTimeMillis()
                        countedCurrentAd = false
                        closeAttemptsForCurrentAd = 0
                    }
                    val elapsedSec = ((System.currentTimeMillis() - activeAdStartMs) / 1000L).toInt()
                    publish(
                        TaskPhase.WATCHING_AD,
                        "第 ${(completedAds + 1).coerceAtMost(config.maxRounds)}/${config.maxRounds} 轮静音等待 (${elapsedSec}s)",
                        elapsedSec
                    )

                    if (elapsedSec > config.singleAdTimeoutSec) {
                        publish(TaskPhase.ERROR, "单轮广告超 ${config.singleAdTimeoutSec}s 未结束，安全中止")
                        return
                    }

                    if (elapsedSec >= 33 && countdownHit == null && ocrLines.isNotEmpty()) {
                        val lateCloseNode = host.findAdCloseNodeTarget(null, screenW, screenH)
                        if (lateCloseNode != null) {
                            if (!countedCurrentAd) {
                                completedAds++
                                countedCurrentAd = true
                            }
                            host.performSmartTap(lateCloseNode, "倒计时结束关闭×", Humanizer.randomAdActionDelayMs(fast))
                            delay(Humanizer.randomPostActionSettleMs(fast))
                            continue
                        }
                    }

                    delay(Humanizer.randomPollIntervalMs(fast))
                    continue
                }

                // 过渡空帧保护
                if (nodePreview.isEmpty() && ocrLines.isEmpty()) {
                    delay(650L)
                    continue
                }

                // ====== 以下为非激励视频界面（截图5开屏广告 / 截图4播放页 / 领时长抽屉页） ======
                activeAdStartMs = 0L
                countedCurrentAd = false
                closeAttemptsForCurrentAd = 0

                // 场景 8A：OCR 兜底跳过开屏广告（截图5右上角「跳过 4」）
                if (isSplashAdActivity || System.currentTimeMillis() - taskStartMs < 14_000L) {
                    val splashSkip = findHybrid(
                        keywords = listOf("跳过"),
                        ocrLines = ocrLines,
                        regionPredicate = { it.bottom <= (screenH * 0.18f).toInt() && it.left >= (screenW * 0.65f).toInt() }
                    )
                    if (splashSkip != null && splashSkip.fullText.length <= 8) {
                        AppLogBus.info("跳过开屏广告：「${splashSkip.fullText}」")
                        host.performSmartTap(splashSkip, "跳过开屏广告", Humanizer.randomUiNavDelayMs(fast))
                        delay(1000L)
                        continue
                    }
                }

                // 场景 8B：已完成目标轮数 -> 干净收尾退出
                if (completedAds >= config.maxRounds) {
                    val finalUnlockBtn = findHybrid(
                        keywords = listOf("立即解锁"),
                        ocrLines = ocrLines,
                        excludeContaining = listOf("元", "¥", "￥", "购买", "开通")
                    )
                    if (finalUnlockBtn != null) {
                        host.performSmartTap(finalUnlockBtn, "点击立即解锁畅听", Humanizer.randomUiNavDelayMs(fast))
                        delay(1100L)
                    } else if (topWin.contains("SheetDialog", ignoreCase = true)) {
                        host.performGlobalBack("收起底部畅听抽屉", fast)
                    }
                    publish(TaskPhase.COMPLETED, "已极速连看完成 $completedAds/${config.maxRounds} 轮并恢复原音量")
                    AppLogBus.info("🎉 已完成全部 $completedAds 轮广告无缝连看！")
                    return
                }

                // 场景 8C：关闭普通升级/提示弹窗与付费推销层
                val neutralPopup = findHybrid(config.neutralDismissKeywords, ocrLines)
                if (neutralPopup != null) {
                    host.performSmartTap(neutralPopup, "关闭提示窗:${neutralPopup.matchedKeyword}", Humanizer.randomUiNavDelayMs(fast))
                    delay(1000L)
                    continue
                }
                val promoPopup = findHybrid(config.promoBackKeywords, ocrLines)
                if (promoPopup != null) {
                    host.performGlobalBack("关闭推销层:${promoPopup.matchedKeyword}", fast)
                    delay(1000L)
                    continue
                }

                val alreadyUnlocked = findHybrid(
                    keywords = config.unlockedKeywords,
                    ocrLines = ocrLines,
                    excludeContaining = listOf("看视频", "看广告", "领时长")
                )
                if (alreadyUnlocked != null) {
                    publish(TaskPhase.COMPLETED, "今日已达上限（${alreadyUnlocked.fullText}）")
                    return
                }

                // 场景 9：匹配畅听入口（含抽屉「提前领」、福利页「领时长」、以及截图4播放页右下「免费听 2天」胶囊！）
                val entryMatch = findBestEntryButton(config, ocrLines, screenW, screenH)
                if (entryMatch != null) {
                    unknownTicks = 0
                    goldCoinAttempts = 0
                    publish(TaskPhase.FINDING_ENTRY, "点击「${entryMatch.fullText.take(10)}」进入看视频...")
                    AppLogBus.info("命中入口[${entryMatch.source}]：「${entryMatch.fullText}」${entryMatch.bounds.toShortString()}")
                    host.performSmartTap(
                        match = entryMatch,
                        reason = "点击入口:${entryMatch.matchedKeyword}",
                        delayMs = Humanizer.randomUiNavDelayMs(fast)
                    )
                    activeAdStartMs = System.currentTimeMillis()
                    countedCurrentAd = false
                    closeAttemptsForCurrentAd = 0
                    delay(Humanizer.randomPostActionSettleMs(fast))
                    continue
                }

                // 场景 10：在截图4播放页面（底栏为「发现|听抖音|暂停|会员|我的」，无「福利」文字时）
                // -> 直接点击截图4右上角（搜索放大镜左侧、与「模式选择」同行）的金色金币图标打开「领时长」页！
                val onPlayerHome = findHybrid(
                    keywords = listOf("模式选择", "听抖音"),
                    ocrLines = ocrLines
                ) != null
                val onWelfareDrawer = findHybrid(
                    keywords = listOf("金币收益", "去提现", "福利中心"),
                    ocrLines = ocrLines
                ) != null

                if (onPlayerHome && !onWelfareDrawer && goldCoinAttempts < 3) {
                    goldCoinAttempts++
                    unknownTicks = 0
                    publish(TaskPhase.NAVIGATING_WELFARE, "点击播放页右上角金币图标打开福利页...")

                    // 优先找右上角金币无障碍节点；若无节点则与左上角「模式选择」同一水平线取 x=82% 精准点击
                    val coinNode = host.findTopRightGoldCoinNode(screenW, screenH)
                    if (coinNode != null) {
                        AppLogBus.info("点击播放页右上角金币节点 ${coinNode.bounds.toShortString()}")
                        host.performSmartTap(coinNode, "右上角金币图标", Humanizer.randomUiNavDelayMs(fast))
                    } else {
                        val modeSelectHit = findHybrid(
                            keywords = listOf("模式选择"),
                            ocrLines = ocrLines,
                            regionPredicate = { it.bottom <= (screenH * 0.14f).toInt() }
                        )
                        val coinCenterY = modeSelectHit?.bounds?.centerY() ?: (screenH * 0.064f).toInt()
                        val coinRect = Rect(
                            (screenW * 0.795f).toInt(),
                            coinCenterY - 24,
                            (screenW * 0.842f).toInt(),
                            coinCenterY + 24
                        )
                        AppLogBus.info("点击播放页右上角金币坐标 ${coinRect.toShortString()}")
                        host.performHumanTap(coinRect, "右上角金币坐标", Humanizer.randomUiNavDelayMs(fast))
                    }
                    delay(1500L)
                    continue
                }

                // 兼容部分版本底栏确实写着「福利」二字的情况（严格匹配文字，不再盲点「会员」位置）
                val welfareTab = findHybrid(
                    keywords = config.welfareTabKeywords,
                    ocrLines = ocrLines,
                    regionPredicate = { it.top >= (screenH * 0.85f).toInt() }
                )
                if (welfareTab != null && !onWelfareDrawer) {
                    publish(TaskPhase.NAVIGATING_WELFARE, "切换到底栏「福利」...")
                    host.performSmartTap(welfareTab, "切换底栏福利", Humanizer.randomUiNavDelayMs(fast), preferNodeAction = true)
                    delay(1400L)
                    continue
                }

                unknownTicks++
                if (unknownTicks == 4 || unknownTicks == 6) {
                    host.performGlobalBack("未知子页尝试返回", fast)
                } else if (unknownTicks >= 8) {
                    publish(TaskPhase.ERROR, "连续未找到领时长入口，请复制日志检查")
                    return
                }
                delay(Humanizer.randomPollIntervalMs(fast))
            }
        } catch (ce: CancellationException) {
            AppLogBus.info("任务已停止")
            throw ce
        } catch (t: Throwable) {
            AppLogBus.warn("状态机异常：${t.message}")
            host.onStatusUpdated(
                TaskStatus(
                    phase = TaskPhase.ERROR,
                    currentRound = completedAds,
                    maxRounds = config.maxRounds,
                    detail = "执行异常：${t.message ?: "未知错误"}"
                )
            )
        } finally {
            volumeGuard.restoreIfNeeded()
            KeepAliveForegroundService.stop(host)
            AppLogBus.info("====== 任务结束（系统媒体音量已恢复）======")
        }
    }

    /**
     * 精准匹配入口按钮：
     * 1. 优先匹配「提前领 / 领时长 / 看视频领时长 / 看视频免费听 / 立即解锁」；
     * 2. 若在截图4播放页，匹配右下角「免费听 2天 / 免费听」胶囊按钮（严格限制在右侧控制栏或顶部，避免误点普通标题）。
     */
    private fun findBestEntryButton(
        config: RuleConfig,
        ocrLines: List<OcrLineRecord>,
        screenW: Int,
        screenH: Int
    ): TargetMatch? {
        val baseExcludes = listOf(
            "金币", "宝箱", "元", "¥", "￥", "开通", "购买", "免广告",
            "秒后", "秒可领", "明日免费听", "后日免费听", "日免费听", "免费听时长", "免费听VIP"
        )
        for (keyword in config.entryKeywords) {
            val regionFilter: (Rect) -> Boolean = if (keyword == "免费听") {
                // 截图4右下角「免费听 2天」位于 x > 60% W, y 在 75%~91% H（或旧版顶部导航栏 y < 18% H）
                { rect ->
                    (rect.left >= (screenW * 0.58f).toInt() &&
                        rect.top >= (screenH * 0.74f).toInt() &&
                        rect.bottom <= (screenH * 0.91f).toInt()) ||
                        (rect.top >= 0 && rect.bottom <= (screenH * 0.18f).toInt())
                }
            } else {
                { rect ->
                    rect.top >= (screenH * 0.12f).toInt() && rect.bottom <= (screenH * 0.92f).toInt()
                }
            }

            val hit = findHybrid(
                keywords = listOf(keyword),
                ocrLines = ocrLines,
                excludeContaining = baseExcludes,
                regionPredicate = regionFilter
            )
            if (hit != null) return hit
        }
        return null
    }

    private fun findHybrid(
        keywords: List<String>,
        ocrLines: List<OcrLineRecord>?,
        excludeContaining: List<String> = emptyList(),
        regionPredicate: ((Rect) -> Boolean)? = null
    ): TargetMatch? {
        host.findNodeMatch(
            keywords = keywords,
            excludeContaining = excludeContaining,
            regionPredicate = regionPredicate
        )?.let { return it }

        if (ocrLines.isNullOrEmpty()) return null
        return ocrHelper.findBestMatch(
            lines = ocrLines,
            keywords = keywords,
            excludeContaining = excludeContaining,
            regionPredicate = regionPredicate
        )
    }
}
