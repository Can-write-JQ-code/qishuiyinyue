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
        val maxTotalDurationMs = ((config.maxRounds * (config.singleAdTimeoutSec + 40)) + 90) * 1000L

        try {
            AppLogBus.info("====== 启动畅听任务 v1.1（目标=${config.targetPackage}, 目标轮数=${config.maxRounds}）======")
            volumeGuard.muteIfNeeded(config.autoMuteMedia)

            val initialPkg = host.getCurrentPackageName()
            if (startedFromOverlay && initialPkg == config.targetPackage) {
                AppLogBus.info("检测到当前已在 ${config.targetPackage} 前台，直接接管当前界面")
            } else {
                publish(TaskPhase.LAUNCHING_APP, "正在打开 ${config.targetPackage}...")
                if (!host.launchTargetApp(config.targetPackage)) {
                    publish(TaskPhase.ERROR, "未检测到应用(${config.targetPackage})，请确认已安装")
                    AppLogBus.warn("任务终止：设备未安装 ${config.targetPackage}")
                    return
                }
                delay(3800L)
            }

            var loopIndex = 0
            while (true) {
                loopIndex++
                if (System.currentTimeMillis() - taskStartMs > maxTotalDurationMs) {
                    publish(TaskPhase.ERROR, "总运行时间超过安全上限，已自动中止")
                    AppLogBus.warn("触发总时长兜底保护，安全停止")
                    return
                }

                val (screenW, screenH) = host.getScreenSize()
                val fgPkg = host.getCurrentPackageName()
                val topAct = host.getCurrentActivityClass()

                // 场景 1：检查是否误跳到了外部第三方 App（淘宝/京东/浏览器等）
                if (fgPkg.isNotEmpty() &&
                    fgPkg != config.targetPackage &&
                    fgPkg != host.packageName &&
                    fgPkg != "com.android.systemui"
                ) {
                    // 刚启动前 2 轮如果还在桌面，重拉一次汽水音乐
                    if (loopIndex <= 2) {
                        AppLogBus.info("等待目标应用进入前台(当前=$fgPkg)，重拉一次...")
                        host.launchTargetApp(config.targetPackage)
                        delay(3000L)
                        continue
                    }
                    externalJumpStreak++
                    AppLogBus.warn("检测到跳出汽水音乐(当前=$fgPkg, 连续第 $externalJumpStreak 次)，自动拉回")
                    if (externalJumpStreak >= 2) {
                        host.launchTargetApp(config.targetPackage)
                    } else {
                        host.performGlobalBack("拦截外部应用跳转:$fgPkg")
                    }
                    delay(2200L)
                    continue
                } else {
                    externalJumpStreak = 0
                }

                // 同步采集无障碍节点与静默截屏 OCR，并输出可见诊断日志
                val nodePreview = host.collectNodeTextPreview(8)
                val ocrLines = host.captureScreenOcrLines()
                val ocrPreview = ocrLines.take(8).joinToString(", ") { it.text.take(14) }
                AppLogBus.info(
                    "画面[${fgPkg.substringAfterLast('.')}/${topAct.substringAfterLast('.')}] " +
                        "节点(${nodePreview.size})=$nodePreview | OCR(${ocrLines.size})=[$ocrPreview]"
                )

                // 场景 2：频控或「当前无新视频」兜底
                val noVideoHit = findHybrid(config.noVideoKeywords, ocrLines)
                if (noVideoHit != null) {
                    if (completedAds > 0) {
                        publish(TaskPhase.COMPLETED, "已完成 $completedAds 轮观看（${noVideoHit.matchedKeyword}）")
                        AppLogBus.info("后续提示「${noVideoHit.fullText}」，已完成 $completedAds 轮，正常收尾")
                    } else {
                        publish(TaskPhase.ERROR, "平台提示「${noVideoHit.matchedKeyword}」，请稍后再试")
                        AppLogBus.warn("检测到无视频/频控提示：${noVideoHit.fullText}")
                    }
                    return
                }

                // 场景 3：直播间退出二次确认框（「退出不看了」）
                val liveConfirmHit = findHybrid(config.liveExitConfirmKeywords, ocrLines)
                if (liveConfirmHit != null) {
                    publish(TaskPhase.HANDLING_POPUP, "点击直播退出确认：${liveConfirmHit.matchedKeyword}")
                    host.performSmartTap(liveConfirmHit, "直播退出确认:${liveConfirmHit.matchedKeyword}")
                    delay(1800L)
                    continue
                }

                // 场景 4：误入直播间（Activity 为 LunaDefaultLivePlayerActivity 或顶部出现「更多直播」）
                val isMisJumpAct = config.misJumpActivityMarkers.any {
                    topAct.contains(it, ignoreCase = true)
                }
                val liveTopMarker = findHybrid(
                    keywords = listOf("更多直播", "本场点赞"),
                    ocrLines = ocrLines,
                    regionPredicate = { it.bottom <= (screenH * 0.30f).toInt() }
                )
                if (isMisJumpAct || liveTopMarker != null) {
                    AppLogBus.warn("检测到进入直播间或外链页(act=$topAct)，执行关闭返回")
                    val liveCloseBtn = findHybrid(
                        keywords = listOf("关闭"),
                        ocrLines = ocrLines,
                        regionPredicate = {
                            it.bottom <= (screenH * 0.20f).toInt() && it.left >= (screenW * 0.70f).toInt()
                        }
                    )
                    if (liveCloseBtn != null) {
                        host.performSmartTap(liveCloseBtn, "关闭直播间按钮")
                    } else {
                        host.performGlobalBack("退出直播间/偏离页")
                    }
                    delay(2000L)
                    continue
                }

                // 判断顶部是否仍在倒计时（如「18秒后可领取奖励」）以及是否身处激励广告容器
                val topRegion: (Rect) -> Boolean = { rect -> rect.bottom <= (screenH * 0.24f).toInt() }
                val countdownHit = findHybrid(
                    keywords = listOf("秒后", "后可领", "后可获"),
                    ocrLines = ocrLines,
                    regionPredicate = topRegion
                )
                val inAdActivity = config.adActivityMarkers.any {
                    topAct.contains(it, ignoreCase = true)
                }
                val adFeedbackHit = findHybrid(
                    keywords = listOf("反馈"),
                    ocrLines = ocrLines,
                    regionPredicate = topRegion
                )
                val isInsideAd = inAdActivity || countdownHit != null || adFeedbackHit != null

                // 场景 5：处理激励视频中间挽留/连看弹窗（「领取奖励 / 再看一个 / 继续观看」 + 「坚持退出」）
                val modalRegion: (Rect) -> Boolean = { rect ->
                    rect.top >= (screenH * 0.24f).toInt() &&
                        rect.bottom <= (screenH * 0.86f).toInt() &&
                        rect.left >= (screenW * 0.10f).toInt() &&
                        rect.right <= (screenW * 0.90f).toInt()
                }
                val exitModalBtn = findHybrid(
                    keywords = config.exitAdConfirmKeywords,
                    ocrLines = ocrLines,
                    regionPredicate = modalRegion
                )
                val nextModalBtn = findHybrid(
                    keywords = config.nextAdKeywords,
                    ocrLines = ocrLines,
                    excludeContaining = listOf("秒后", "后可", "提前解锁下一天", "再看1次", "再看2次", "再看3次"),
                    regionPredicate = modalRegion
                )

                // 仅当身处广告容器或同时存在「坚持退出」挽留框时，才按广告弹窗处理（避免和福利中心文本混淆）
                if ((isInsideAd && nextModalBtn != null) || exitModalBtn != null) {
                    // 情况 A：顶部还在倒计时，说明误触了返回，必须点「继续观看」保住本轮广告
                    if (countdownHit != null && nextModalBtn != null) {
                        AppLogBus.info("倒计时未结束，点击「${nextModalBtn.fullText}」继续当前广告")
                        host.performSmartTap(nextModalBtn, "恢复广告播放:${nextModalBtn.matchedKeyword}")
                        delay(2000L)
                        continue
                    }

                    // 情况 B：本轮已看完后弹出的连看/挽留弹窗
                    if (!countedCurrentAd && activeAdStartMs > 0L) {
                        completedAds++
                        countedCurrentAd = true
                        AppLogBus.info("确认已完成第 $completedAds/${config.maxRounds} 轮广告")
                    }

                    val tomorrowHint = findHybrid(
                        keywords = listOf("明日免费听", "后日免费听", "提前得"),
                        ocrLines = ocrLines
                    )
                    if (completedAds < config.maxRounds && tomorrowHint == null && nextModalBtn != null) {
                        publish(TaskPhase.HANDLING_POPUP, "点击「${nextModalBtn.matchedKeyword}」进入第 ${completedAds + 1} 轮广告...")
                        host.performSmartTap(nextModalBtn, "连看下一轮:${nextModalBtn.matchedKeyword}")
                        activeAdStartMs = System.currentTimeMillis()
                        countedCurrentAd = false
                        closeAttemptsForCurrentAd = 0
                        delay(2600L)
                        continue
                    } else {
                        // 已达设定轮数，或提示「提前得明日免费听」-> 点击「坚持退出」收手
                        if (exitModalBtn != null) {
                            publish(TaskPhase.HANDLING_POPUP, "今日目标已达成，点击「${exitModalBtn.matchedKeyword}」退出广告")
                            host.performSmartTap(exitModalBtn, "完成目标退出:${exitModalBtn.matchedKeyword}")
                        } else {
                            host.performGlobalBack("关闭多余续看弹窗")
                        }
                        activeAdStartMs = 0L
                        delay(2400L)
                        continue
                    }
                }

                // 场景 6：单轮广告播放完成（顶部出现「领取成功 / 奖励已领取」且无倒计时）
                val rewardDoneHit = findHybrid(
                    keywords = config.rewardDoneKeywords,
                    ocrLines = ocrLines,
                    excludeContaining = listOf("秒后", "后可"),
                    regionPredicate = topRegion
                )
                if (rewardDoneHit != null && countdownHit == null) {
                    if (!countedCurrentAd) {
                        completedAds++
                        countedCurrentAd = true
                        AppLogBus.info("第 $completedAds/${config.maxRounds} 轮奖励已达成[${rewardDoneHit.source}]：「${rewardDoneHit.fullText}」")
                    }
                    publish(TaskPhase.HANDLING_POPUP, "第 $completedAds 轮领取成功，正在关闭广告...")

                    // 五级关闭按钮精准寻址（融合开源 GKD 节点规则 + OCR + 右侧 × 坐标）
                    val closeNodeTarget = host.findAdCloseNodeTarget(rewardDoneHit.bounds, screenW, screenH)
                    val topRightCloseText = findHybrid(
                        keywords = config.closeAdKeywords,
                        ocrLines = ocrLines,
                        excludeContaining = listOf("秒后"),
                        regionPredicate = {
                            it.bottom <= (screenH * 0.20f).toInt() && it.left >= (screenW * 0.52f).toInt()
                        }
                    )

                    closeAttemptsForCurrentAd++
                    when {
                        closeNodeTarget != null -> {
                            host.performSmartTap(closeNodeTarget, "节点关闭广告:${closeNodeTarget.matchedKeyword}")
                        }
                        topRightCloseText != null -> {
                            host.performSmartTap(topRightCloseText, "文字关闭广告:${topRightCloseText.matchedKeyword}")
                        }
                        closeAttemptsForCurrentAd == 1 && rewardDoneHit.bounds.left >= (screenW * 0.50f).toInt() -> {
                            // 「领取成功 ×」胶囊中 × 紧贴在「领取成功」右侧
                            val xCloseLeft = rewardDoneHit.bounds.right - 6
                            val xCloseRight = min(screenW - 14, rewardDoneHit.bounds.right + 64)
                            val rightXRect = if (xCloseRight > xCloseLeft + 14) {
                                Rect(xCloseLeft, rewardDoneHit.bounds.top, xCloseRight, rewardDoneHit.bounds.bottom)
                            } else {
                                rewardDoneHit.bounds
                            }
                            host.performHumanTap(rightXRect, "点击领取成功右侧×区")
                        }
                        closeAttemptsForCurrentAd == 2 -> {
                            host.performGlobalBack("奖励已达成，执行返回关闭")
                        }
                        else -> {
                            val stdTopRight = Rect(
                                (screenW * 0.885f).toInt(),
                                (screenH * 0.052f).toInt(),
                                (screenW * 0.955f).toInt(),
                                (screenH * 0.092f).toInt()
                            )
                            host.performHumanTap(stdTopRight, "右上角标准×热区兜底")
                        }
                    }
                    delay(2400L)
                    continue
                }

                // 场景 7：广告仍在倒计时播放中
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
                        "第 ${(completedAds + 1).coerceAtMost(config.maxRounds)}/${config.maxRounds} 轮静音等待中 (${elapsedSec}s)",
                        elapsedSec
                    )

                    if (elapsedSec > config.singleAdTimeoutSec) {
                        publish(TaskPhase.ERROR, "单轮广告超 ${config.singleAdTimeoutSec}s 未结束，安全中止")
                        AppLogBus.warn("广告等待超过 ${config.singleAdTimeoutSec}s，自动停止")
                        return
                    }

                    // 部分字节 SDK 广告播满 35s 后无「领取成功」字样、仅倒计时消失并出现右上角 × 图标
                    if (elapsedSec >= 35 && countdownHit == null) {
                        val lateCloseNode = host.findAdCloseNodeTarget(null, screenW, screenH)
                        if (lateCloseNode != null) {
                            if (!countedCurrentAd) {
                                completedAds++
                                countedCurrentAd = true
                            }
                            AppLogBus.info("倒计时已结束(${elapsedSec}s)，点击右上角关闭图标")
                            host.performSmartTap(lateCloseNode, "倒计时消失关闭×")
                            delay(2400L)
                            continue
                        }
                    }

                    delay(Humanizer.randomPollIntervalMs())
                    continue
                }

                // ====== 以下为非广告容器场景（汽水音乐首页 / 播放页 / 福利中心 / 普通弹窗） ======
                if (activeAdStartMs > 0L && !countedCurrentAd) {
                    val prevElapsed = ((System.currentTimeMillis() - activeAdStartMs) / 1000L).toInt()
                    if (prevElapsed >= 15) {
                        completedAds++
                        AppLogBus.info("广告已自动关闭返回主界面，计入第 $completedAds 轮完成")
                    }
                }
                activeAdStartMs = 0L
                countedCurrentAd = false
                closeAttemptsForCurrentAd = 0

                // 场景 8A：冷启动前 12 秒若遇到开屏广告「跳过」，顺手点掉
                if (System.currentTimeMillis() - taskStartMs < 12_000L) {
                    val splashSkip = findHybrid(
                        keywords = listOf("跳过"),
                        ocrLines = ocrLines,
                        regionPredicate = { it.bottom <= (screenH * 0.20f).toInt() && it.width() < (screenW * 0.35f).toInt() }
                    )
                    if (splashSkip != null && splashSkip.fullText.length <= 8) {
                        AppLogBus.info("跳过冷启动开屏广告：「${splashSkip.fullText}」")
                        host.performSmartTap(splashSkip, "跳过开屏广告")
                        delay(1800L)
                        continue
                    }
                }

                // 场景 8B：关闭升级提示/青少年模式/开心收下等中性弹窗
                val neutralPopup = findHybrid(config.neutralDismissKeywords, ocrLines)
                if (neutralPopup != null) {
                    AppLogBus.info("关闭提示弹窗：「${neutralPopup.fullText}」")
                    host.performSmartTap(neutralPopup, "关闭弹窗:${neutralPopup.matchedKeyword}")
                    delay(1800L)
                    continue
                }

                // 场景 8C：关闭 SVIP 付费推销半屏弹窗 / 恭喜浮层 / 评分弹窗
                val promoPopup = findHybrid(config.promoBackKeywords, ocrLines)
                if (promoPopup != null) {
                    AppLogBus.info("检测到推销或恭喜浮层「${promoPopup.fullText}」，按返回关闭")
                    host.performGlobalBack("关闭推销/恭喜浮层:${promoPopup.matchedKeyword}")
                    delay(1800L)
                    continue
                }

                // 场景 8D：检查是否已拿满今日畅听或已完成设定轮数
                val alreadyUnlocked = findHybrid(
                    keywords = config.unlockedKeywords,
                    ocrLines = ocrLines,
                    excludeContaining = listOf("看视频", "看广告", "领时长")
                )
                if (alreadyUnlocked != null) {
                    publish(TaskPhase.COMPLETED, "今日畅听已就绪（${alreadyUnlocked.fullText}）")
                    AppLogBus.info("检测到「${alreadyUnlocked.fullText}」，任务完成")
                    return
                }

                if (completedAds >= config.maxRounds) {
                    // 在福利中心看够次数后，若底部/中部出现「立即解锁第X天畅听」按钮，顺手点一下激活再结束
                    val finalUnlockBtn = findHybrid(
                        keywords = listOf("立即解锁"),
                        ocrLines = ocrLines,
                        excludeContaining = listOf("元", "¥", "￥", "购买", "开通")
                    )
                    if (finalUnlockBtn != null) {
                        AppLogBus.info("看满 $completedAds 轮后发现「${finalUnlockBtn.fullText}」，点击激活畅听")
                        host.performSmartTap(finalUnlockBtn, "点击立即解锁畅听")
                        delay(2000L)
                    }
                    publish(TaskPhase.COMPLETED, "已完成 $completedAds/${config.maxRounds} 轮广告领取并恢复原音量")
                    AppLogBus.info("已完成全部 $completedAds 轮广告观看，畅听领取流程圆满结束")
                    return
                }

                // 场景 9：查找并点击「领时长 / 看视频领时长 / 立得全天畅听 / 看视频免费听」按钮
                val entryMatch = findBestEntryButton(config, ocrLines, screenH)
                if (entryMatch != null) {
                    unknownTicks = 0
                    welfareNavAttempts = 0
                    publish(TaskPhase.FINDING_ENTRY, "点击「${entryMatch.matchedKeyword}」开始第 ${completedAds + 1} 轮广告...")
                    AppLogBus.info("命中畅听入口[${entryMatch.source}]：「${entryMatch.fullText}」${entryMatch.bounds.toShortString()}")
                    host.performSmartTap(entryMatch, "点击入口:${entryMatch.matchedKeyword}")
                    activeAdStartMs = System.currentTimeMillis()
                    countedCurrentAd = false
                    closeAttemptsForCurrentAd = 0
                    delay(2800L)
                    continue
                }

                // 场景 10：当前在汽水音乐首页/播放页/我的页，点击底部导航栏「福利」进入福利中心
                val onWelfarePage = findHybrid(
                    keywords = listOf("福利中心", "免费听时长"),
                    ocrLines = ocrLines
                ) != null

                if (!onWelfarePage) {
                    val bottomBarFilter: (Rect) -> Boolean = { rect ->
                        rect.top >= (screenH * 0.84f).toInt() && rect.bottom <= screenH
                    }
                    val welfareTab = findHybrid(
                        keywords = config.welfareTabKeywords,
                        ocrLines = ocrLines,
                        regionPredicate = bottomBarFilter
                    )
                    if (welfareTab != null) {
                        welfareNavAttempts++
                        unknownTicks = 0
                        publish(TaskPhase.NAVIGATING_WELFARE, "正在切换到底部「福利」页...")
                        AppLogBus.info("点击底栏「${welfareTab.fullText}」进入福利中心 ${welfareTab.bounds.toShortString()}")
                        host.performSmartTap(
                            match = welfareTab,
                            reason = "切换底栏福利页",
                            preferNodeAction = welfareNavAttempts >= 2
                        )
                        delay(2600L)
                        continue
                    }

                    // 若识别到底栏「发现 / 听抖音 / 我的」但「福利」图标文字被动效遮挡，第 2 次起使用底栏福利槽位兜底
                    val hasBottomBar = findHybrid(
                        keywords = listOf("发现", "听抖音", "我的"),
                        ocrLines = ocrLines,
                        regionPredicate = bottomBarFilter
                    ) != null
                    if (hasBottomBar && welfareNavAttempts < 3) {
                        welfareNavAttempts++
                        val fallbackTabRect = Rect(
                            (screenW * 0.62f).toInt(),
                            (screenH * 0.91f).toInt(),
                            (screenW * 0.72f).toInt(),
                            (screenH * 0.965f).toInt()
                        )
                        publish(TaskPhase.NAVIGATING_WELFARE, "尝试点击底栏「福利」图标区域...")
                        host.performHumanTap(fallbackTabRect, "底栏福利坐标兜底")
                        delay(2600L)
                        continue
                    }
                }

                unknownTicks++
                AppLogBus.info("当前画面暂未匹配动作 (等待稳定 $unknownTicks/8)...")
                if (unknownTicks == 4 || unknownTicks == 6) {
                    host.performGlobalBack("未知子页面尝试返回上一层")
                } else if (unknownTicks >= 8) {
                    publish(
                        TaskPhase.ERROR,
                        "连续多次未找到「福利 / 领时长」按钮，请复制下方日志查看画面识别结果"
                    )
                    AppLogBus.warn("连续 $unknownTicks 轮未匹配到入口，自动安全停止")
                    return
                }
                delay(Humanizer.randomPollIntervalMs())
            }
        } catch (ce: CancellationException) {
            AppLogBus.info("任务协程已响应停止指令")
            throw ce
        } catch (t: Throwable) {
            AppLogBus.warn("状态机运行异常：${t.message}")
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
            AppLogBus.info("====== 任务收尾结束（系统媒体音量已复位）======")
        }
    }

    /**
     * 在福利中心或弹窗中匹配「领时长 / 看视频免费听 / 立即解锁」按钮，排除金币宝箱与付费购买项
     */
    private fun findBestEntryButton(
        config: RuleConfig,
        ocrLines: List<OcrLineRecord>,
        screenH: Int
    ): TargetMatch? {
        val excludeWords = listOf("金币", "宝箱", "元", "¥", "￥", "开通", "购买", "秒后")
        val contentArea: (Rect) -> Boolean = { rect ->
            rect.top >= (screenH * 0.12f).toInt() && rect.bottom <= (screenH * 0.86f).toInt()
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
