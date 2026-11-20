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
        var welfareNavAttempts = 0
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
            AppLogBus.info("====== 启动极速连看 v1.2（目标轮数=${config.maxRounds}, 极速=${fast}, 仅领今日=${config.stopOnTomorrow}）======")
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
                delay(2800L)
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

                // 场景 1：跳出目标应用保护（若回到系统桌面则立即重拉汽水，不浪费返回键）
                if (fgPkg.isNotEmpty() &&
                    fgPkg != config.targetPackage &&
                    fgPkg != host.packageName &&
                    fgPkg != "com.android.systemui"
                ) {
                    val isLauncher = fgPkg.contains("launcher", ignoreCase = true) ||
                        fgPkg.contains("home", ignoreCase = true)
                    if (isLauncher || loopIndex <= 2 || externalJumpStreak >= 1) {
                        AppLogBus.info("当前在外部桌面/应用($fgPkg)，立即切回汽水音乐")
                        host.launchTargetApp(config.targetPackage)
                        externalJumpStreak = 0
                        delay(1600L)
                        continue
                    }
                    externalJumpStreak++
                    AppLogBus.warn("拦截广告外跳($fgPkg)，执行返回拉回")
                    host.performGlobalBack("拦截外跳:$fgPkg", fast)
                    delay(1400L)
                    continue
                } else {
                    externalJumpStreak = 0
                }

                val topRegion: (Rect) -> Boolean = { rect -> rect.bottom <= (screenH * 0.24f).toInt() }
                val modalRegion: (Rect) -> Boolean = { rect ->
                    rect.top >= (screenH * 0.24f).toInt() &&
                        rect.bottom <= (screenH * 0.86f).toInt() &&
                        rect.left >= (screenW * 0.10f).toInt() &&
                        rect.right <= (screenW * 0.90f).toInt()
                }

                // ====== 零截屏节点极速快检通道（耗时 <5ms，免去无谓 OCR 耗时） ======
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

                // 快检通道 A：无障碍节点已明确显示仍在倒计时且无中途退出框 -> 直接等待，无需截屏 OCR！
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

                // 快检通道 B：无障碍节点已直接暴露「领取成功，关闭，按钮」且无连看弹窗 -> 秒点关闭！
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
                    publish(TaskPhase.HANDLING_POPUP, "第 $completedAds 轮完成，快速关闭拉起连看...")
                    val closeTarget = host.findAdCloseNodeTarget(fastNodeRewardDone.bounds, screenW, screenH) ?: fastNodeRewardDone
                    closeAttemptsForCurrentAd++
                    host.performSmartTap(
                        match = closeTarget,
                        reason = "极速关闭:${closeTarget.matchedKeyword}",
                        delayMs = Humanizer.randomAdActionDelayMs(fast)
                    )
                    delay(Humanizer.randomPostActionSettleMs(fast))
                    continue
                }

                // ====== 常规双引擎全量感知（无障碍节点 + 静默截屏 OCR） ======
                val nodePreview = host.collectNodeTextPreview(6)
                val ocrLines = host.captureScreenOcrLines()
                val ocrPreview = ocrLines.take(7).joinToString(", ") { it.text.take(14) }
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
                    delay(1200L)
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
                    delay(1300L)
                    continue
                }

                val countdownHit = findHybrid(
                    keywords = listOf("秒后", "秒可领", "后可领", "后可获"),
                    ocrLines = ocrLines,
                    regionPredicate = topRegion
                )
                val inAdActivity = config.adActivityMarkers.any {
                    topAct.contains(it, ignoreCase = true)
                }
                val adHeaderHit = findHybrid(
                    keywords = listOf("反馈", "广告"),
                    ocrLines = ocrLines,
                    regionPredicate = topRegion
                )
                val isInsideAd = inAdActivity || countdownHit != null || adHeaderHit != null

                // 场景 5：核心无缝连看弹窗处理（「领取奖励 / 再看一个 / 继续观看」 vs 「坚持退出」）
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

                if ((isInsideAd && nextModalBtn != null) || exitModalBtn != null) {
                    // 情况 A：顶部仍在倒计时，点「继续观看」恢复当前广告
                    if (countdownHit != null) {
                        if (nextModalBtn != null) {
                            AppLogBus.info("倒计时未结束，点击「${nextModalBtn.matchedKeyword}」继续播放")
                            host.performSmartTap(
                                match = nextModalBtn,
                                reason = "继续播放当前广告",
                                delayMs = Humanizer.randomUiNavDelayMs(fast)
                            )
                        }
                        delay(1200L)
                        continue
                    }

                    // 情况 B：本条广告已播完弹出的连看/挽留框
                    if (!countedCurrentAd && activeAdStartMs > 0L) {
                        completedAds++
                        countedCurrentAd = true
                        AppLogBus.info("已完成第 $completedAds/${config.maxRounds} 轮广告")
                    }

                    val tomorrowHint = findHybrid(
                        keywords = listOf("明日免费听", "后日免费听", "第3日", "第4日", "第5日"),
                        ocrLines = ocrLines
                    )
                    val shouldStopForTomorrow = config.stopOnTomorrow && tomorrowHint != null

                    if (completedAds < config.maxRounds && !shouldStopForTomorrow) {
                        // 未满设定轮数：直接在弹窗内点击「领取奖励 / 再看一个」无缝开启下一条广告！
                        publish(TaskPhase.HANDLING_POPUP, "无缝连看第 ${completedAds + 1}/${config.maxRounds} 轮广告...")
                        if (nextModalBtn != null) {
                            AppLogBus.info("⚡ 无缝连看：点击「${nextModalBtn.fullText}」直接进入第 ${completedAds + 1} 轮")
                            host.performSmartTap(
                                match = nextModalBtn,
                                reason = "无缝连看第${completedAds + 1}轮:${nextModalBtn.matchedKeyword}",
                                delayMs = Humanizer.randomAdActionDelayMs(fast)
                            )
                        } else if (exitModalBtn != null) {
                            // 艺术字图形按钮兜底：「领取奖励」主按钮正位于「坚持退出」上方约 7% 屏幕高度处
                            val ctaRect = Rect(
                                exitModalBtn.bounds.left - 70,
                                (exitModalBtn.bounds.top - screenH * 0.082f).toInt(),
                                exitModalBtn.bounds.right + 70,
                                (exitModalBtn.bounds.top - screenH * 0.040f).toInt()
                            )
                            AppLogBus.info("⚡ 无缝连看：点击图形主按钮区开启第 ${completedAds + 1} 轮")
                            host.performHumanTap(ctaRect, "连看主按钮坐标兜底", Humanizer.randomAdActionDelayMs(fast))
                        }
                        activeAdStartMs = System.currentTimeMillis()
                        countedCurrentAd = false
                        closeAttemptsForCurrentAd = 0
                        delay(Humanizer.randomPostActionSettleMs(fast))
                        continue
                    } else {
                        // 已看满设定轮数（或勾选了仅领今日且遇到明日畅听）-> 点「坚持退出」收尾
                        publish(TaskPhase.HANDLING_POPUP, "已看满 $completedAds 轮，正在退出广告页...")
                        if (exitModalBtn != null) {
                            host.performSmartTap(
                                match = exitModalBtn,
                                reason = "看满轮数点坚持退出",
                                delayMs = Humanizer.randomAdActionDelayMs(fast)
                            )
                        } else {
                            host.performGlobalBack("关闭挽留弹窗收尾", fast)
                        }
                        activeAdStartMs = 0L
                        delay(Humanizer.randomPostActionSettleMs(fast))
                        continue
                    }
                }

                // 场景 6：单轮广告结束（出现「领取成功 / 奖励已领取」），快速点击右上角 × 拉起连看框
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
                    publish(TaskPhase.HANDLING_POPUP, "第 $completedAds 轮完成，点击关闭触发连看...")

                    val closeNodeTarget = host.findAdCloseNodeTarget(rewardDoneHit.bounds, screenW, screenH)
                    val topRightCloseText = findHybrid(
                        keywords = config.closeAdKeywords,
                        ocrLines = ocrLines,
                        excludeContaining = listOf("秒后"),
                        regionPredicate = {
                            it.bottom <= (screenH * 0.20f).toInt() && it.left >= (screenW * 0.52f).toInt()
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
                        closeAttemptsForCurrentAd == 1 && rewardDoneHit.bounds.left >= (screenW * 0.50f).toInt() -> {
                            val xCloseLeft = rewardDoneHit.bounds.right - 6
                            val xCloseRight = min(screenW - 14, rewardDoneHit.bounds.right + 64)
                            val rightXRect = if (xCloseRight > xCloseLeft + 14) {
                                Rect(xCloseLeft, rewardDoneHit.bounds.top, xCloseRight, rewardDoneHit.bounds.bottom)
                            } else {
                                rewardDoneHit.bounds
                            }
                            host.performHumanTap(rightXRect, "点击领取成功右侧×", actionDelay)
                        }
                        closeAttemptsForCurrentAd == 2 -> {
                            host.performGlobalBack("奖励已达成返回关闭", fast)
                        }
                        else -> {
                            val stdTopRight = Rect(
                                (screenW * 0.885f).toInt(),
                                (screenH * 0.052f).toInt(),
                                (screenW * 0.955f).toInt(),
                                (screenH * 0.092f).toInt()
                            )
                            host.performHumanTap(stdTopRight, "右上角×热区兜底", actionDelay)
                        }
                    }
                    delay(Humanizer.randomPostActionSettleMs(fast))
                    continue
                }

                // 场景 7：广告仍在播放中（防止空白加载帧误判退出：只要 topAct 仍是广告 Activity 就继续守护）
                if (isInsideAd) {
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

                // 若遇到全空过渡帧 (CommonLoadingDialog 切换瞬间)，等待下一帧确认，绝不误判为广告已退出！
                if (nodePreview.isEmpty() && ocrLines.isEmpty()) {
                    delay(700L)
                    continue
                }

                // ====== 以下确认已回到汽水音乐主界面 / 福利中心 / 抽屉弹窗 ======
                activeAdStartMs = 0L
                countedCurrentAd = false
                closeAttemptsForCurrentAd = 0

                // 场景 8A：冷启动开屏广告秒跳
                if (System.currentTimeMillis() - taskStartMs < 12_000L) {
                    val splashSkip = findHybrid(
                        keywords = listOf("跳过"),
                        ocrLines = ocrLines,
                        regionPredicate = { it.bottom <= (screenH * 0.20f).toInt() && it.width() < (screenW * 0.35f).toInt() }
                    )
                    if (splashSkip != null && splashSkip.fullText.length <= 8) {
                        host.performSmartTap(splashSkip, "跳过开屏广告", Humanizer.randomUiNavDelayMs(fast))
                        delay(1100L)
                        continue
                    }
                }

                // 场景 8B：关闭普通提示框
                val neutralPopup = findHybrid(config.neutralDismissKeywords, ocrLines)
                if (neutralPopup != null) {
                    host.performSmartTap(neutralPopup, "关闭弹窗:${neutralPopup.matchedKeyword}", Humanizer.randomUiNavDelayMs(fast))
                    delay(1100L)
                    continue
                }

                // 场景 8C：关闭付费推销浮层
                val promoPopup = findHybrid(config.promoBackKeywords, ocrLines)
                if (promoPopup != null) {
                    host.performGlobalBack("关闭推销/恭喜浮层:${promoPopup.matchedKeyword}", fast)
                    delay(1100L)
                    continue
                }

                // 场景 8D：检查是否已达设定轮数或今日上限
                val alreadyUnlocked = findHybrid(
                    keywords = config.unlockedKeywords,
                    ocrLines = ocrLines,
                    excludeContaining = listOf("看视频", "看广告", "领时长")
                )
                if (alreadyUnlocked != null) {
                    publish(TaskPhase.COMPLETED, "今日畅听已领满（${alreadyUnlocked.fullText}）")
                    return
                }

                if (completedAds >= config.maxRounds) {
                    val finalUnlockBtn = findHybrid(
                        keywords = listOf("立即解锁"),
                        ocrLines = ocrLines,
                        excludeContaining = listOf("元", "¥", "￥", "购买", "开通")
                    )
                    if (finalUnlockBtn != null) {
                        host.performSmartTap(finalUnlockBtn, "点击立即解锁畅听", Humanizer.randomUiNavDelayMs(fast))
                        delay(1300L)
                    }
                    publish(TaskPhase.COMPLETED, "已极速连看完成 $completedAds/${config.maxRounds} 轮并恢复原音量")
                    AppLogBus.info("已完成全部 $completedAds 轮广告连看，畅听领取完毕")
                    return
                }

                // 场景 9：点击福利抽屉「提前领」（如「再看1个提前领」）或福利中心「领时长」
                val entryMatch = findBestEntryButton(config, ocrLines, screenH)
                if (entryMatch != null) {
                    unknownTicks = 0
                    welfareNavAttempts = 0
                    publish(TaskPhase.FINDING_ENTRY, "点击「${entryMatch.matchedKeyword}」开启第 ${completedAds + 1} 轮...")
                    AppLogBus.info("命中入口[${entryMatch.source}]：「${entryMatch.fullText}」")
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

                // 场景 10：从首页/播放页快速切入底栏「福利」
                val onWelfarePage = findHybrid(
                    keywords = listOf("福利中心", "免费听时长", "去提现", "金币收益"),
                    ocrLines = ocrLines
                ) != null

                if (!onWelfarePage) {
                    val bottomBarFilter: (Rect) -> Boolean = { rect ->
                        rect.top >= (screenH * 0.85f).toInt() && rect.bottom <= (screenH * 0.965f).toInt()
                    }
                    val welfareTab = findHybrid(
                        keywords = config.welfareTabKeywords,
                        ocrLines = ocrLines,
                        regionPredicate = bottomBarFilter
                    )
                    if (welfareTab != null) {
                        welfareNavAttempts++
                        unknownTicks = 0
                        publish(TaskPhase.NAVIGATING_WELFARE, "切换到底栏「福利」...")
                        host.performSmartTap(
                            match = welfareTab,
                            reason = "切换底栏福利页",
                            delayMs = Humanizer.randomUiNavDelayMs(fast),
                            preferNodeAction = true
                        )
                        delay(1500L)
                        continue
                    }

                    // 精确避开荣耀/华为底部全面屏横条（控制在 90.8%~94.0% 高度区间，中心恰好对应实测生效点 y≈2608）
                    if (welfareNavAttempts < 3) {
                        welfareNavAttempts++
                        val fallbackTabRect = Rect(
                            (screenW * 0.64f).toInt(),
                            (screenH * 0.908f).toInt(),
                            (screenW * 0.70f).toInt(),
                            (screenH * 0.940f).toInt()
                        )
                        publish(TaskPhase.NAVIGATING_WELFARE, "快速点击底栏「福利」槽位...")
                        host.performHumanTap(
                            bounds = fallbackTabRect,
                            reason = "底栏福利槽位",
                            delayMs = Humanizer.randomUiNavDelayMs(fast)
                        )
                        delay(1500L)
                        continue
                    }
                }

                unknownTicks++
                if (unknownTicks == 4 || unknownTicks == 6) {
                    host.performGlobalBack("未知子页返回", fast)
                } else if (unknownTicks >= 8) {
                    publish(TaskPhase.ERROR, "连续多次未找到入口，请复制日志检查")
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

    private fun findBestEntryButton(
        config: RuleConfig,
        ocrLines: List<OcrLineRecord>,
        screenH: Int
    ): TargetMatch? {
        val excludeWords = listOf("金币", "宝箱", "元", "¥", "￥", "开通", "购买", "秒后", "秒可领")
        val contentArea: (Rect) -> Boolean = { rect ->
            rect.top >= (screenH * 0.12f).toInt() && rect.bottom <= (screenH * 0.92f).toInt()
        }
        return findHybrid(
            keywords = config.entryKeywords,
            ocrLines = ocrLines,
            excludeContaining = excludeWords,
            regionPredicate = contentArea
        )
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
