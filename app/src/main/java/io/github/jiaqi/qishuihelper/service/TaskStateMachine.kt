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

class TaskStateMachine(
    private val host: AdHelperAccessibilityService,
    private val ocrHelper: ScreenOcrHelper,
    private val volumeGuard: VolumeGuard
) {

    suspend fun execute(config: RuleConfig) {
        var currentRound = 0
        var roundStartMs = System.currentTimeMillis()
        var phase = TaskPhase.LAUNCHING_APP

        fun publish(newPhase: TaskPhase, detail: String, elapsedSec: Int = 0) {
            phase = newPhase
            val status = TaskStatus(
                phase = newPhase,
                currentRound = currentRound,
                maxRounds = config.maxRounds,
                roundElapsedSec = elapsedSec,
                detail = detail
            )
            host.onStatusUpdated(status)
        }

        val taskStartMs = System.currentTimeMillis()
        val maxTotalDurationMs = ((config.maxRounds * (config.singleAdTimeoutSec + 35)) + 60) * 1000L

        try {
            AppLogBus.info("====== 启动自动化畅听任务（目标=${config.targetPackage}, 上限=${config.maxRounds}轮）======")
            volumeGuard.muteIfNeeded(config.autoMuteMedia)

            // Step 1: 拉起目标应用
            publish(TaskPhase.LAUNCHING_APP, "正在打开 ${config.targetPackage}...")
            if (!host.launchTargetApp(config.targetPackage)) {
                publish(TaskPhase.ERROR, "未找到目标应用(${config.targetPackage})，请检查是否已安装")
                AppLogBus.warn("任务终止：设备未安装 ${config.targetPackage}")
                return
            }
            delay(3600L)

            // Step 2: 寻址畅听入口
            phase = TaskPhase.FINDING_ENTRY
            var entryAttempts = 0
            var waitingAdTicks = 0
            var postRewardCloseAttempts = 0
            var postAdCheckTicks = 0
            var externalJumpStreak = 0

            while (true) {
                if (System.currentTimeMillis() - taskStartMs > maxTotalDurationMs) {
                    publish(TaskPhase.ERROR, "任务总耗时超过安全阈值，已自动停止")
                    AppLogBus.warn("触发总时长兜底保护，安全中止任务")
                    break
                }

                val (screenW, screenH) = host.getScreenSize()
                val fgPkg = host.getCurrentPackageName()
                val topAct = host.getCurrentActivityClass()

                when (phase) {
                    TaskPhase.FINDING_ENTRY -> {
                        entryAttempts++
                        publish(TaskPhase.FINDING_ENTRY, "正在查找免费畅听入口 (第 $entryAttempts/7 次)...")

                        if (fgPkg.isNotEmpty() && fgPkg != config.targetPackage && fgPkg != host.packageName) {
                            if (entryAttempts <= 2) {
                                AppLogBus.info("等待目标应用切入前台(当前=$fgPkg)...")
                                host.launchTargetApp(config.targetPackage)
                                delay(2800L)
                                continue
                            }
                        }

                        // 先查节点，必要时截屏 OCR
                        val ocrLines = host.captureScreenOcrLines()

                        // 1) 检查是否提示无新视频
                        findHybrid(config.noVideoKeywords, ocrLines)?.let { hit ->
                            publish(TaskPhase.ERROR, "检测到「${hit.matchedKeyword}」，已停止")
                            AppLogBus.warn("频控或库存提示：${hit.fullText}")
                            return
                        }

                        // 2) 关闭青少年模式/升级提示等中性遮挡弹窗
                        val neutralPopup = findHybrid(config.neutralDismissKeywords, ocrLines)
                        if (neutralPopup != null) {
                            AppLogBus.info("检测到遮挡弹窗「${neutralPopup.fullText}」，先予以关闭")
                            host.performHumanTap(neutralPopup.bounds, "关闭遮挡弹窗:${neutralPopup.matchedKeyword}")
                            delay(1800L)
                            continue
                        }

                        // 3) 检测是否已经解锁全天畅听（排除带引导动词的按钮文案）
                        findHybrid(
                            keywords = config.unlockedKeywords,
                            ocrLines = ocrLines,
                            excludeContaining = listOf("看视频", "看广告", "去开启", "立即解锁", "再看")
                        )?.let { unlocked ->
                            publish(TaskPhase.COMPLETED, "当前已处于畅听状态（${unlocked.fullText}）")
                            AppLogBus.info("检测到已解锁标识「${unlocked.fullText}」，无需重复看视频")
                            return
                        }

                        // 4) 查找主动入口（顶部「免/免费听」）或被动拦截弹窗（「看视频免费听」）
                        val entryMatch = findEntryTarget(config, ocrLines, screenH)
                        if (entryMatch != null) {
                            AppLogBus.info("命中入口[${entryMatch.source}]：「${entryMatch.fullText}」${entryMatch.bounds.toShortString()}")
                            host.performHumanTap(entryMatch.bounds, "点击入口:${entryMatch.matchedKeyword}")
                            currentRound = 1
                            roundStartMs = System.currentTimeMillis()
                            waitingAdTicks = 0
                            postRewardCloseAttempts = 0
                            publish(TaskPhase.WAITING_AD_START, "已点击入口，等待激励视频容器加载...")
                            delay(2400L)
                            continue
                        }

                        if (entryAttempts >= 7) {
                            publish(TaskPhase.ERROR, "未找到「免费听/免」入口，请确认停留在首页或播放页")
                            AppLogBus.warn("连续 $entryAttempts 次未识别到畅听入口，自动停止")
                            return
                        }
                        delay(Humanizer.randomPollIntervalMs())
                    }

                    TaskPhase.WAITING_AD_START -> {
                        waitingAdTicks++
                        val ocrLines = host.captureScreenOcrLines()

                        // 检查点击入口后是否弹出「当前无新视频」
                        findHybrid(config.noVideoKeywords, ocrLines)?.let { hit ->
                            val msg = if (currentRound > 1) {
                                "已完成 ${currentRound - 1} 轮，后续提示「${hit.matchedKeyword}」"
                            } else {
                                "平台提示「${hit.matchedKeyword}」，请稍后再试"
                            }
                            publish(if (currentRound > 1) TaskPhase.COMPLETED else TaskPhase.ERROR, msg)
                            AppLogBus.warn("等待广告时命中提示：${hit.fullText}")
                            return
                        }

                        // 检查点击顶部「免」字后是否弹出了半屏抽屉（若已解锁则直接完成）
                        findHybrid(
                            keywords = config.unlockedKeywords,
                            ocrLines = ocrLines,
                            excludeContaining = listOf("看视频", "看广告", "再看")
                        )?.let { unlocked ->
                            publish(TaskPhase.COMPLETED, "已确认解锁全天畅听（${unlocked.fullText}）")
                            AppLogBus.info("抽屉页确认已解锁：${unlocked.fullText}")
                            return
                        }

                        // 判断是否已进入穿山甲/激励视频页面
                        val inAdActivity = config.adActivityMarkers.any {
                            topAct.contains(it, ignoreCase = true)
                        }
                        val hasAdUiMarker = findHybrid(
                            keywords = listOf("秒后", "广告", "反馈") + config.rewardDoneKeywords,
                            ocrLines = ocrLines
                        ) != null

                        if (inAdActivity || hasAdUiMarker) {
                            AppLogBus.info("已进入第 $currentRound 轮广告容器 (Activity=$topAct)")
                            volumeGuard.muteIfNeeded(config.autoMuteMedia)
                            roundStartMs = System.currentTimeMillis()
                            postRewardCloseAttempts = 0
                            publish(TaskPhase.WATCHING_AD, "第 $currentRound 轮激励视频静音等待中...", 1)
                            delay(Humanizer.randomPollIntervalMs())
                            continue
                        }

                        // 若停留在畅听半屏弹窗，点击二级确认按钮（如「看视频免费听」「立即观看」「再看一个」）
                        val secondaryCta = findHybrid(
                            keywords = listOf("看视频免费听", "看视频解锁", "立即观看", "去观看") + config.nextAdKeywords,
                            ocrLines = ocrLines
                        )
                        if (secondaryCta != null && waitingAdTicks <= 3) {
                            AppLogBus.info("点击二级确认按钮：「${secondaryCta.fullText}」")
                            host.performHumanTap(secondaryCta.bounds, "二级确认:${secondaryCta.matchedKeyword}")
                            delay(2500L)
                            continue
                        }

                        if (waitingAdTicks >= 5) {
                            publish(TaskPhase.ERROR, "点击后未拉起激励广告页，已自动中止")
                            AppLogBus.warn("等待广告容器超时(当前Activity=$topAct)")
                            return
                        }
                        delay(2400L)
                    }

                    TaskPhase.WATCHING_AD -> {
                        val elapsedSec = ((System.currentTimeMillis() - roundStartMs) / 1000L).toInt()
                        publish(TaskPhase.WATCHING_AD, "第 $currentRound/${config.maxRounds} 轮倒计时守护中 (${elapsedSec}s)", elapsedSec)

                        // 守护 1：检测摇一摇误跳到外部第三方 App（淘宝/京东/浏览器等）
                        if (fgPkg.isNotEmpty() &&
                            fgPkg != config.targetPackage &&
                            fgPkg != host.packageName &&
                            fgPkg != "com.android.systemui"
                        ) {
                            externalJumpStreak++
                            AppLogBus.warn("拦截外部跳转：当前前台包名=$fgPkg (连续第 $externalJumpStreak 次)")
                            if (externalJumpStreak >= 2) {
                                AppLogBus.info("尝试直接重拉 ${config.targetPackage} 回前台")
                                host.launchTargetApp(config.targetPackage)
                            } else {
                                host.performGlobalBack("拦截外跳:$fgPkg")
                            }
                            delay(2000L)
                            continue
                        } else {
                            externalJumpStreak = 0
                        }

                        // 守护 2：检测应用内误跳（如摇进直播间 LunaDefaultLivePlayerActivity 或内置落地页）
                        if (config.misJumpActivityMarkers.any { topAct.contains(it, ignoreCase = true) }) {
                            AppLogBus.warn("拦截应用内偏离页面($topAct)，执行返回回到广告页")
                            host.performGlobalBack("拉回广告容器:$topAct")
                            delay(2000L)
                            continue
                        }

                        // 守护 3：单轮超时兜底
                        if (elapsedSec > config.singleAdTimeoutSec) {
                            publish(TaskPhase.ERROR, "第 $currentRound 轮广告等待超 ${config.singleAdTimeoutSec}s，已中止")
                            AppLogBus.warn("第 $currentRound 轮广告超过 ${config.singleAdTimeoutSec}s 未出现完成信号，安全中止")
                            return
                        }

                        // 先用轻量无障碍树探测，未命中再调用静默截屏 OCR
                        val fastDoneNode = host.findNodeMatch(
                            keywords = config.rewardDoneKeywords,
                            excludeContaining = listOf("秒后", "后可")
                        )
                        val fastNextNode = host.findNodeMatch(
                            keywords = config.nextAdKeywords,
                            excludeContaining = listOf("秒后")
                        )
                        val ocrLines = if (fastDoneNode == null && fastNextNode == null) {
                            host.captureScreenOcrLines()
                        } else {
                            null
                        }

                        // 检查频控/加载失败
                        findHybrid(config.noVideoKeywords, ocrLines)?.let { hit ->
                            publish(TaskPhase.ERROR, "广告异常：「${hit.matchedKeyword}」")
                            AppLogBus.warn("广告中断：${hit.fullText}")
                            return
                        }

                        // 优先检查是否弹出「再看一个解锁全天」续看框
                        val nextMatch = fastNextNode ?: findOcrOnly(
                            keywords = config.nextAdKeywords,
                            ocrLines = ocrLines,
                            excludeContaining = listOf("秒后")
                        )
                        if (nextMatch != null && currentRound < config.maxRounds) {
                            AppLogBus.info("识别到二段激励弹窗[${nextMatch.source}]：「${nextMatch.fullText}」")
                            host.performHumanTap(nextMatch.bounds, "连看下一轮:${nextMatch.matchedKeyword}")
                            currentRound++
                            roundStartMs = System.currentTimeMillis()
                            waitingAdTicks = 0
                            postRewardCloseAttempts = 0
                            publish(TaskPhase.WAITING_AD_START, "进入第 $currentRound/${config.maxRounds} 轮激励视频...")
                            delay(2600L)
                            continue
                        }

                        // 检查单轮奖励是否发放完成（「领取成功 / 奖励已领取」，严格排除「15秒后可领取」）
                        val rewardDone = fastDoneNode ?: findOcrOnly(
                            keywords = config.rewardDoneKeywords,
                            ocrLines = ocrLines,
                            excludeContaining = listOf("秒后", "后可")
                        )
                        if (rewardDone != null) {
                            AppLogBus.info("第 $currentRound 轮奖励已达成[${rewardDone.source}]：「${rewardDone.fullText}」")
                            val fullOcr = ocrLines ?: host.captureScreenOcrLines()
                            val closeTarget = findAdCloseButton(config, fullOcr, screenW, screenH)

                            if (closeTarget != null) {
                                host.performHumanTap(closeTarget.bounds, "关闭广告:${closeTarget.matchedKeyword}")
                                postAdCheckTicks = 0
                                publish(TaskPhase.POST_AD_CHECK, "已关闭第 $currentRound 轮广告，检查续看或解锁状态...")
                                delay(2500L)
                                continue
                            }

                            val iconRect = host.findTopRightCloseIconNode(screenW, screenH)
                            if (iconRect != null) {
                                host.performHumanTap(iconRect, "点击右上角关闭图标")
                                postAdCheckTicks = 0
                                publish(TaskPhase.POST_AD_CHECK, "已点击右上角关闭图标，复核状态中...")
                                delay(2500L)
                                continue
                            }

                            // 纯自绘 × 符号未识别为文字时：先尝试系统返回拉起挽留框或关闭，次轮兜底右上角安全热区
                            postRewardCloseAttempts++
                            if (postRewardCloseAttempts == 1) {
                                host.performGlobalBack("奖励已领取，尝试返回关闭广告页")
                                delay(2200L)
                            } else {
                                val fallbackCloseRect = Rect(
                                    (screenW * 0.88f).toInt(),
                                    (screenH * 0.055f).toInt(),
                                    (screenW * 0.955f).toInt(),
                                    (screenH * 0.095f).toInt()
                                )
                                host.performHumanTap(fallbackCloseRect, "右上角关闭热区兜底")
                                postAdCheckTicks = 0
                                publish(TaskPhase.POST_AD_CHECK, "已执行右上角关闭兜底，复核结果中...")
                                delay(2500L)
                            }
                            continue
                        }

                        // 若观看已超 16s 且顶层 Activity 已经自动回到主界面
                        val stillInAdAct = config.adActivityMarkers.any { topAct.contains(it, ignoreCase = true) }
                        if (elapsedSec >= 16 && topAct.contains("MainActivity", ignoreCase = true) && !stillInAdAct) {
                            AppLogBus.info("检测到已自动返回主页面($topAct)，转入结果复核")
                            postAdCheckTicks = 0
                            publish(TaskPhase.POST_AD_CHECK, "已返回主界面，正在复核畅听权益...")
                            delay(2000L)
                            continue
                        }

                        delay(Humanizer.randomPollIntervalMs())
                    }

                    TaskPhase.POST_AD_CHECK -> {
                        postAdCheckTicks++
                        val ocrLines = host.captureScreenOcrLines()

                        // 1) 关闭第 1 条广告后常弹出「再看一个，解锁全天免费畅听」
                        val nextBtn = findHybrid(
                            keywords = config.nextAdKeywords,
                            ocrLines = ocrLines,
                            excludeContaining = listOf("秒后")
                        )
                        if (nextBtn != null) {
                            if (currentRound < config.maxRounds) {
                                AppLogBus.info("复核阶段发现连看激励弹窗：「${nextBtn.fullText}」，继续第 ${currentRound + 1} 轮")
                                host.performHumanTap(nextBtn.bounds, "二段续看:${nextBtn.matchedKeyword}")
                                currentRound++
                                roundStartMs = System.currentTimeMillis()
                                waitingAdTicks = 0
                                postRewardCloseAttempts = 0
                                publish(TaskPhase.WAITING_AD_START, "正在进入第 $currentRound/${config.maxRounds} 轮视频...")
                                delay(2500L)
                                continue
                            } else {
                                publish(TaskPhase.COMPLETED, "已完成设定上限($currentRound 轮)观看，任务结束")
                                AppLogBus.info("已达到最大连看轮数($currentRound)，不再继续续看")
                                return
                            }
                        }

                        // 2) 检查是否出现全天已解锁提示
                        findHybrid(
                            keywords = config.unlockedKeywords,
                            ocrLines = ocrLines,
                            excludeContaining = listOf("看视频", "看广告", "再看")
                        )?.let { unlocked ->
                            publish(TaskPhase.COMPLETED, "成功解锁畅听（共看 $currentRound 轮：${unlocked.fullText}）")
                            AppLogBus.info("复核确认已解锁畅听：「${unlocked.fullText}」")
                            return
                        }

                        // 3) 检查是否提示无更多视频
                        findHybrid(config.noVideoKeywords, ocrLines)?.let { hit ->
                            publish(TaskPhase.COMPLETED, "已完成 $currentRound 轮观看（${hit.matchedKeyword}）")
                            AppLogBus.info("已完成 $currentRound 轮，后续无更多广告：${hit.fullText}")
                            return
                        }

                        if (postAdCheckTicks >= 3) {
                            publish(TaskPhase.COMPLETED, "已完成 $currentRound 轮激励视频观看，任务收尾完成")
                            AppLogBus.info("复核完成，未再弹出续看提示，共完成 $currentRound 轮观看")
                            return
                        }
                        delay(2300L)
                    }

                    else -> return
                }
            }
        } catch (ce: CancellationException) {
            AppLogBus.info("任务协程已响应停止指令")
            throw ce
        } catch (t: Throwable) {
            AppLogBus.warn("状态机运行异常：${t.message}")
            host.onStatusUpdated(
                TaskStatus(
                    phase = TaskPhase.ERROR,
                    currentRound = currentRound,
                    maxRounds = config.maxRounds,
                    detail = "执行异常：${t.message ?: "未知错误"}"
                )
            )
        } finally {
            volumeGuard.restoreIfNeeded()
            KeepAliveForegroundService.stop(host)
            AppLogBus.info("====== 任务收尾结束（系统媒体音量守护已复位）======")
        }
    }

    private fun findEntryTarget(
        config: RuleConfig,
        ocrLines: List<OcrLineRecord>?,
        screenH: Int
    ): TargetMatch? {
        for (keyword in config.entryKeywords) {
            // 短关键词（如「免」「免费听」）仅匹配屏幕顶部 24% 导航区，严防误点中部歌名或歌词
            val regionCheck: ((Rect) -> Boolean)? = if (keyword.length <= 3) {
                { rect -> rect.top >= 0 && rect.bottom <= (screenH * 0.24f).toInt() }
            } else {
                { rect -> rect.top >= 0 && rect.bottom <= (screenH * 0.90f).toInt() }
            }

            host.findNodeMatch(
                keywords = listOf(keyword),
                regionPredicate = regionCheck
            )?.let { return it }

            if (ocrLines != null) {
                ocrHelper.findBestMatch(
                    lines = ocrLines,
                    keywords = listOf(keyword),
                    regionPredicate = regionCheck
                )?.let { return it }
            }
        }
        return null
    }

    private fun findAdCloseButton(
        config: RuleConfig,
        ocrLines: List<OcrLineRecord>?,
        screenW: Int,
        screenH: Int
    ): TargetMatch? {
        val topAreaFilter: (Rect) -> Boolean = { rect ->
            rect.bottom <= (screenH * 0.20f).toInt() && rect.left >= (screenW * 0.55f).toInt()
        }
        host.findNodeMatch(
            keywords = config.closeAdKeywords,
            excludeContaining = listOf("秒后"),
            regionPredicate = topAreaFilter
        )?.let { return it }

        if (ocrLines != null) {
            ocrHelper.findBestMatch(
                lines = ocrLines,
                keywords = config.closeAdKeywords,
                excludeContaining = listOf("秒后"),
                regionPredicate = topAreaFilter
            )?.let { return it }
        }
        return null
    }

    private fun findHybrid(
        keywords: List<String>,
        ocrLines: List<OcrLineRecord>?,
        excludeContaining: List<String> = emptyList()
    ): TargetMatch? {
        host.findNodeMatch(keywords, excludeContaining)?.let { return it }
        return findOcrOnly(keywords, ocrLines, excludeContaining)
    }

    private fun findOcrOnly(
        keywords: List<String>,
        ocrLines: List<OcrLineRecord>?,
        excludeContaining: List<String> = emptyList()
    ): TargetMatch? {
        if (ocrLines.isNullOrEmpty()) return null
        return ocrHelper.findBestMatch(
            lines = ocrLines,
            keywords = keywords,
            excludeContaining = excludeContaining
        )
    }
}
