package io.github.jiaqi.qishuihelper.service

import android.graphics.Rect
import io.github.jiaqi.qishuihelper.model.PctRegion
import io.github.jiaqi.qishuihelper.model.RuleConfig
import io.github.jiaqi.qishuihelper.model.TaskPhase
import io.github.jiaqi.qishuihelper.model.TaskStatus
import io.github.jiaqi.qishuihelper.ocr.ScreenOcrHelper
import io.github.jiaqi.qishuihelper.util.AppLogBus
import io.github.jiaqi.qishuihelper.util.Humanizer
import io.github.jiaqi.qishuihelper.util.VolumeGuard
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlin.math.min

/**
 * 畅听自动化 v2.0 核心引擎（完整吸收 wei6718189/qishuiAScriptProject 的五阶段精简架构）：
 *   阶段一：启动汽水音乐 + 右上角局部极速识别跳过开屏广告
 *   阶段二：轻量清理底部续费会员弹窗（「元开通」）
 *   阶段三：自动点进首条广告（若未命中则不报错停机，无缝等待用户手点第 1 个广告后自动接管）
 *   阶段四：等待倒计时结束（支持视频广告 + 直播间广告关叉后拿奖励 + 中途「继续观看」恢复）
 *   阶段五：确定性两步领取（Step 1 点右上角「领取成功」 -> Step 2 紧盯中间弹窗点「领取奖励」直接进下一条；满轮数点「坚持退出」）
 */
class TaskStateMachine(
    private val host: AdHelperAccessibilityService,
    private val ocrHelper: ScreenOcrHelper,
    private val volumeGuard: VolumeGuard
) {

    private enum class WaitAdOutcome {
        READY_TO_CLAIM,
        NO_MORE_ADS,
        TIMEOUT
    }

    private enum class ClaimOutcome {
        CHAINED_NEXT_AD,
        FINISHED_ALL_ROUNDS,
        NO_MORE_ADS,
        FAILED
    }

    suspend fun execute(config: RuleConfig, startedFromOverlay: Boolean = false) {
        var successCount = 0

        fun updateUi(phase: TaskPhase, detail: String, elapsedSec: Int = 0) {
            host.onStatusUpdated(
                TaskStatus(
                    phase = phase,
                    currentRound = successCount,
                    maxRounds = config.maxRounds,
                    infiniteMode = config.infiniteUntilQuotaEnd,
                    roundElapsedSec = elapsedSec,
                    detail = detail
                )
            )
        }

        try {
            val modeDesc = if (config.infiniteUntilQuotaEnd) "无限循环至当日上限" else "连看 ${config.maxRounds} 轮"
            AppLogBus.info("==========================================")
            AppLogBus.info("🎵 畅听助手 v2.0 启动（模式：$modeDesc）")
            volumeGuard.muteIfNeeded(config.autoMuteMedia)

            // 阶段一：启动 App + 局部极速跳过开屏广告
            val initialPkg = host.getCurrentPackageName()
            if (!startedFromOverlay || initialPkg != config.targetPackage) {
                updateUi(TaskPhase.STAGE1_SPLASH, "正在启动汽水音乐并监控开屏跳过...")
                if (!host.launchTargetApp(config.targetPackage)) {
                    updateUi(TaskPhase.ERROR, "无法启动 ${config.targetPackage}，请检查是否已安装")
                    return
                }
                delay(900L)
                skipSplashAd(timeoutMs = 5500L)
            } else {
                AppLogBus.info("检测到已在汽水音乐前台，直接检查当前画面")
            }

            // 阶段二 & 三：若尚未在广告页内，清理续费弹窗并自动点击进入第 1 条广告
            if (!isCurrentlyInAd()) {
                closeMembershipPopupIfPresent()
                updateUi(TaskPhase.STAGE3_ENTER_AD, "正在自动进入第 1 条广告...")
                val entered = tryEnterFirstAd(config.targetPackage, timeoutMs = 14_000L)
                if (!entered) {
                    AppLogBus.info("⚠️ 未自动触发首条广告入口，已进入接管循环：请手动点开任意一条激励广告，后续全程自动连看！")
                }
            } else {
                AppLogBus.info("当前已在激励广告界面内，直接进入倒计时接管！")
            }

            // 核心广告连看主循环（对齐 qishuiAScriptProject.run_ad_loop）
            var roundIdx = 0
            var idleCount = 0

            while (true) {
                roundIdx++
                val targetLabel = if (config.infiniteUntilQuotaEnd) "∞" else config.maxRounds.toString()
                AppLogBus.info("---- 第 $roundIdx 轮循环（已成功领取 $successCount/$targetLabel 次）----")

                // 阶段四：等待广告倒计时结束（或关闭直播间后出现「领取成功」）
                val waitOutcome = waitAdCountdown(
                    config = config,
                    displayRound = successCount + 1,
                    onTick = { secLeftOrElapsed ->
                        updateUi(
                            TaskPhase.STAGE4_COUNTDOWN,
                            "等待广告倒计时结束...",
                            secLeftOrElapsed
                        )
                    }
                )

                when (waitOutcome) {
                    WaitAdOutcome.NO_MORE_ADS -> {
                        AppLogBus.info("检测到当日广告额度已耗尽（「当前无新视频」），自动收尾退出")
                        break
                    }
                    WaitAdOutcome.TIMEOUT -> {
                        idleCount++
                        AppLogBus.warn("等待「领取成功」超时（连续空闲 $idleCount/${config.maxIdleRounds} 轮）")
                        if (idleCount >= config.maxIdleRounds) {
                            AppLogBus.info("连续 ${config.maxIdleRounds} 轮未检测到广告，当日额度可能已用完，自动退出")
                            break
                        }
                        // 若退回到了福利抽屉/首页，顺手尝试点一次入口拉起广告
                        tryQuickClickEntryOnce()
                        delay(1500L)
                        continue
                    }
                    WaitAdOutcome.READY_TO_CLAIM -> {
                        // 判断本轮领完后是否还需要继续下一轮连看
                        val nextCountAfterThis = successCount + 1
                        val shouldChainNext = config.infiniteUntilQuotaEnd || (nextCountAfterThis < config.maxRounds)

                        updateUi(
                            TaskPhase.STAGE5_CLAIM,
                            if (shouldChainNext) "两步领取并无缝进下一轮..." else "正在完成最后一轮并退出..."
                        )

                        // 阶段五：确定性两步走（点右上角「领取成功」 -> 点中间弹窗「领取奖励」或「坚持退出」）
                        when (val claimRes = claimRewardTwoStep(shouldChainNext)) {
                            ClaimOutcome.CHAINED_NEXT_AD -> {
                                successCount++
                                idleCount = 0
                                AppLogBus.info("✅ 第 $successCount 次领取成功！已无缝进入下一条广告")
                            }
                            ClaimOutcome.FINISHED_ALL_ROUNDS -> {
                                successCount++
                                AppLogBus.info("🎉 已完成全部 $successCount 次广告领取并退出广告页！")
                                break
                            }
                            ClaimOutcome.NO_MORE_ADS -> {
                                successCount++
                                AppLogBus.info("✅ 第 $successCount 次领取完成，后续提示无新视频，正常结束")
                                break
                            }
                            ClaimOutcome.FAILED -> {
                                idleCount++
                                AppLogBus.warn("本轮两步领取未完全命中（连续异常 $idleCount/${config.maxIdleRounds}）")
                                if (idleCount >= config.maxIdleRounds) break
                            }
                        }
                    }
                }
                delay(600L)
            }

            val doneMsg = if (successCount > 0) {
                "运行结束，共成功领取 $successCount 次畅听奖励"
            } else {
                "未检测到可领取的激励广告（可能今日额度已满）"
            }
            updateUi(if (successCount > 0) TaskPhase.COMPLETED else TaskPhase.STOPPED, doneMsg)
            AppLogBus.info("==========================================")
            AppLogBus.info("✅ $doneMsg")
        } catch (ce: CancellationException) {
            AppLogBus.info("任务已手动停止（本次累计领取 $successCount 次）")
            throw ce
        } catch (t: Throwable) {
            AppLogBus.warn("运行异常：${t.message}")
            updateUi(TaskPhase.ERROR, "运行异常：${t.message ?: "未知错误"}")
        } finally {
            volumeGuard.restoreIfNeeded()
            KeepAliveForegroundService.stop(host)
        }
    }

    /**
     * 阶段一B：只在右上角 SPLASH_SKIP 小区域高频扫描「跳过」，极速跳过开屏广告
     */
    private suspend fun skipSplashAd(timeoutMs: Long): Boolean {
        AppLogBus.info("阶段一B：右上角小区域扫描开屏「跳过」...")
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val hit = host.findInRegion(
                region = PctRegion.SPLASH_SKIP,
                keywords = listOf("跳过广告", "跳过"),
                excludeContaining = listOf("奖励已领取")
            )
            if (hit != null && hit.fullText.length <= 8) {
                AppLogBus.info("命中开屏跳过 [${hit.source}] '${hit.fullText}' -> 立即点击")
                host.safeTap(hit, "开屏跳过", preDelayMs = 120L)
                delay(700L)
                return true
            }
            // 若已经看到播放页「模式选择」，说明无开屏广告，直接放行！
            val onMain = host.findNodeInRegion(
                region = PctRegion(0.0f, 0.02f, 0.5f, 0.15f),
                keywords = listOf("模式选择")
            )
            if (onMain != null) {
                return false
            }
            delay(280L)
        }
        return false
    }

    /**
     * 阶段二：轻量检测底部「元开通 / 续费会员」半屏弹窗，点击上方遮罩区关闭
     */
    private suspend fun closeMembershipPopupIfPresent(): Boolean {
        val payHit = host.findInRegion(
            region = PctRegion.MEMBERSHIP_PAY_BOTTOM,
            keywords = listOf("元开通", "开通会员", "购买汽水会员")
        ) ?: return false

        AppLogBus.info("[续费会员弹窗] 检测到底部 '${payHit.fullText}'，点击上方遮罩关闭")
        val (w, h) = host.getScreenSize()
        val maskTapRect = Rect((w * 0.45f).toInt(), (h * 0.28f).toInt(), (w * 0.55f).toInt(), (h * 0.32f).toInt())
        host.safeTapRect(maskTapRect, "关闭续费弹窗遮罩", preDelayMs = 150L)
        delay(900L)
        return true
    }

    /**
     * 阶段三：自动进入第 1 条广告（兼容抽屉「提前领/立即解锁」、福利页「领时长」、播放页「免费听X天」与右上角金币图标）
     */
    private suspend fun tryEnterFirstAd(targetPackage: String, timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        var tappedGoldCoin = false

        while (System.currentTimeMillis() < deadline) {
            if (isCurrentlyInAd()) return true

            // 确保目标应用在前台
            val pkg = host.getCurrentPackageName()
            if (pkg.isNotEmpty() && pkg != targetPackage && pkg != host.packageName) {
                host.launchTargetApp(targetPackage)
                delay(1200L)
            }

            // 1) 优先点击直接拉起广告的按钮：「提前领 / 领时长 / 立即解锁 / 立即领取 / 看视频免费听」
            val directHit = host.findInRegion(
                region = PctRegion.FIRST_AD_ENTRY_AREA,
                keywords = listOf("提前领", "领时长", "立即解锁", "立即领取", "看视频免费听", "看视频领时长"),
                excludeContaining = listOf("金币", "宝箱", "元", "¥", "￥", "开通", "购买")
            )
            if (directHit != null) {
                AppLogBus.info("阶段三命中进广告按钮 [${directHit.source}] '${directHit.fullText}' -> 点击")
                host.safeTap(directHit, "首条广告入口:${directHit.matchedKeyword}", preDelayMs = 200L)
                delay(1300L)
                if (isCurrentlyInAd()) return true
                continue
            }

            // 2) 检查播放页右下角「免费听 2天 / 免费听」胶囊按钮（点开即出「提前领」抽屉或直接进广告）
            val freePillHit = host.findInRegion(
                region = PctRegion(0.58f, 0.74f, 0.94f, 0.91f),
                keywords = listOf("免费听"),
                excludeContaining = listOf("免广告", "开通", "明日", "日免费听", "时长", "VIP")
            )
            if (freePillHit != null) {
                AppLogBus.info("阶段三命中播放页胶囊 '${freePillHit.fullText}' -> 点击唤起畅听页")
                host.safeTap(freePillHit, "播放页免费听胶囊", preDelayMs = 200L)
                delay(1200L)
                continue
            }

            // 3) 兜底点击播放页右上角（搜索放大镜左侧）金色金币图标打开「领时长」页
            if (!tappedGoldCoin) {
                tappedGoldCoin = true
                val coinNode = host.findTopRightGoldCoinNode()
                if (coinNode != null) {
                    AppLogBus.info("阶段三点击右上角金币节点进入福利页")
                    host.safeTap(coinNode, "右上角金币图标", preDelayMs = 200L)
                } else {
                    val (w, h) = host.getScreenSize()
                    val coinRect = Rect((w * 0.795f).toInt(), (h * 0.052f).toInt(), (w * 0.842f).toInt(), (h * 0.076f).toInt())
                    AppLogBus.info("阶段三点击右上角金币坐标进入福利页")
                    host.safeTapRect(coinRect, "右上角金币坐标", preDelayMs = 200L)
                }
                delay(1400L)
                continue
            }

            closeMembershipPopupIfPresent()
            delay(500L)
        }
        return isCurrentlyInAd()
    }

    private suspend fun tryQuickClickEntryOnce() {
        val hit = host.findInRegion(
            region = PctRegion.FIRST_AD_ENTRY_AREA,
            keywords = listOf("提前领", "领时长", "立即解锁", "看视频免费听"),
            excludeContaining = listOf("金币", "宝箱", "元", "开通")
        )
        if (hit != null) {
            AppLogBus.info("重新点击入口 '${hit.fullText}' 拉起广告")
            host.safeTap(hit, "重入广告:${hit.matchedKeyword}", preDelayMs = 220L)
            delay(1300L)
        }
    }

    private suspend fun isCurrentlyInAd(): Boolean {
        val topAct = host.getCurrentActivityClass()
        if (topAct.contains("ExcitingVideo", true) ||
            topAct.contains("LunaRewardActivity", true) ||
            topAct.contains("TTReward", true) ||
            topAct.contains("Stub_Standard", true)
        ) {
            return true
        }
        val topHit = host.findInRegion(
            region = PctRegion(0.45f, 0.02f, 0.99f, 0.14f),
            keywords = listOf("反馈", "秒后", "秒可领", "领取成功", "奖励已领取")
        )
        return topHit != null
    }

    /**
     * 阶段四：等待广告倒计时结束（完整复刻 qishuiAScriptProject.wait_ad_countdown）
     * 轮询优先级：
     *   1) 右上角「领取成功 / 奖励已领取」 -> 立即返回 READY_TO_CLAIM，交给阶段五执行两步点击！
     *   2) 直播间广告（「更多直播」或 LunaDefaultLivePlayerActivity） -> 点右上角关闭(×)，关闭后右上角即出现「领取成功」！
     *   3) 中间意外出现「继续观看」（倒计时未完时误触） -> 点击恢复播放
     *   4) 「当前无新视频」 -> 返回 NO_MORE_ADS
     *   5) 倒计时文案 -> 更新悬浮窗秒数（仅秒数变化时打印，清爽不刷屏）
     */
    private suspend fun waitAdCountdown(
        config: RuleConfig,
        displayRound: Int,
        onTick: (Int) -> Unit
    ): WaitAdOutcome {
        val startMs = System.currentTimeMillis()
        val deadline = startMs + config.singleAdTimeoutSec * 1000L
        var lastLoggedSec = -1
        var liveRoomLogged = false

        while (System.currentTimeMillis() < deadline) {
            volumeGuard.muteIfNeeded(config.autoMuteMedia)
            val elapsedSec = ((System.currentTimeMillis() - startMs) / 1000L).toInt()
            onTick(elapsedSec)

            // 守护外部跳转（摇一摇跳出淘宝/浏览器/桌面或落地页左上角「完成」）
            val fgPkg = host.getCurrentPackageName()
            if (fgPkg.isNotEmpty() &&
                fgPkg != config.targetPackage &&
                fgPkg != host.packageName &&
                fgPkg != "com.android.systemui"
            ) {
                if (fgPkg.contains("launcher", true) || fgPkg.contains("home", true)) {
                    host.launchTargetApp(config.targetPackage)
                } else {
                    host.performGlobalBack("拦截外跳应用:$fgPkg")
                }
                delay(1000L)
                continue
            }

            // 1) 节点零延迟快检右上角「领取成功」（耗时 <2ms）
            val fastClaimNode = host.findNodeInRegion(
                region = PctRegion.AD_CLAIM_TOP,
                keywords = listOf("领取成功", "奖励已领取"),
                excludeContaining = listOf("秒后", "秒可领")
            )
            if (fastClaimNode != null) {
                AppLogBus.info("  [节点命中] 右上角 '${fastClaimNode.fullText}' -> 进入两步领取")
                return WaitAdOutcome.READY_TO_CLAIM
            }

            // 节点零延迟快检右上角倒计时（若节点直接能读到倒计时且不在直播间，免截屏！）
            val topAct = host.getCurrentActivityClass()
            val isLiveAct = topAct.contains("LivePlayer", true)
            val fastCountdownNode = if (!isLiveAct) {
                host.findNodeInRegion(
                    region = PctRegion.AD_CLAIM_TOP,
                    keywords = listOf("秒后", "秒可领")
                )
            } else {
                null
            }
            if (fastCountdownNode != null) {
                val sec = extractFirstNumber(fastCountdownNode.fullText)
                if (sec != null && sec != lastLoggedSec && sec % 5 == 0) {
                    AppLogBus.info("  第 $displayRound 轮倒计时中... ${fastCountdownNode.fullText.take(12)}")
                    lastLoggedSec = sec
                }
                delay(650L)
                continue
            }

            // 单次截屏，按需裁剪局部小区域（一次截屏同时喂给右上角区和直播检测区，极速省电）
            val frame = host.captureFrameBitmap()
            if (frame == null) {
                delay(500L)
                continue
            }

            try {
                // 1) 最高优先：局部裁剪右上角 AD_CLAIM_TOP 识别「领取成功 / 奖励已领取」
                val claimHit = host.ocrFindInFrame(
                    frame = frame,
                    region = PctRegion.AD_CLAIM_TOP,
                    keywords = listOf("领取成功", "奖励已领取"),
                    excludeContaining = listOf("秒后", "秒可领")
                )
                if (claimHit != null) {
                    AppLogBus.info("  [OCR命中] 右上角 '${claimHit.fullText}' -> 进入两步领取")
                    return WaitAdOutcome.READY_TO_CLAIM
                }

                // 2) 次高优先：检测直播间广告（「更多直播」或 LivePlayerActivity 或「退出不看了」）
                val liveConfirm = host.ocrFindInFrame(
                    frame = frame,
                    region = PctRegion.LIVE_CENTER_BOTTOM,
                    keywords = listOf("退出不看了", "退出直播")
                )
                if (liveConfirm != null) {
                    AppLogBus.info("  [直播间] 点击二次确认 '${liveConfirm.fullText}'")
                    host.safeTap(liveConfirm, "退出不看了", preDelayMs = 150L)
                    delay(1000L)
                    continue
                }

                val moreLiveHit = host.ocrFindInFrame(
                    frame = frame,
                    region = PctRegion.LIVE_MORE_TOP_RIGHT,
                    keywords = listOf("更多直播")
                )
                if (isLiveAct || moreLiveHit != null) {
                    if (!liveRoomLogged) {
                        AppLogBus.info("  检测到直播间广告 -> 仅点击最右上角关闭按钮（绝不触碰关注）")
                        liveRoomLogged = true
                    }
                    val closeNode = host.findLiveRoomCloseNode()
                    if (closeNode != null) {
                        host.safeTap(closeNode, "关闭直播间(节点)", preDelayMs = 180L)
                    } else {
                        val closeOcr = host.ocrFindInFrame(
                            frame = frame,
                            region = PctRegion.LIVE_CLOSE_TOP_RIGHT,
                            keywords = listOf("关闭", "×"),
                            excludeContaining = listOf("关注")
                        )
                        if (closeOcr != null) {
                            host.safeTap(closeOcr, "关闭直播间(OCR)", preDelayMs = 180L)
                        } else {
                            // 严格点最右上角 × 槽位 (92%~96.5% W)，远离左侧关注区
                            val (w, h) = host.getScreenSize()
                            val fixedClose = Rect((w * 0.92f).toInt(), (h * 0.058f).toInt(), (w * 0.965f).toInt(), (h * 0.086f).toInt())
                            host.safeTapRect(fixedClose, "关闭直播间(最右上×)", preDelayMs = 180L)
                        }
                    }
                    delay(1200L)
                    continue
                }

                // 3) 检查中间区是否因误触弹出「继续观看」（严格只查中间 AD_REWARD_MODAL，绝不碰底部下载诱导！）
                val midContinue = host.ocrFindInFrame(
                    frame = frame,
                    region = PctRegion.AD_REWARD_MODAL,
                    keywords = listOf("继续观看")
                )
                if (midContinue != null) {
                    AppLogBus.info("  检测到中途暂停弹窗，点击中间区「继续观看」恢复广告")
                    host.safeTap(midContinue, "恢复广告:继续观看", preDelayMs = 180L)
                    delay(1200L)
                    continue
                }

                // 4) 检查是否提示「当前无新视频 / 暂无视频」
                val noVideo = host.ocrFindInFrame(
                    frame = frame,
                    region = PctRegion.AD_REWARD_MODAL,
                    keywords = listOf("当前无新视频", "暂无视频", "今日次数已达上限")
                )
                if (noVideo != null) {
                    return WaitAdOutcome.NO_MORE_ADS
                }

                // 5) 第三方落地页左上角「完成」关闭兜底
                val topDone = host.ocrFindInFrame(
                    frame = frame,
                    region = PctRegion.LANDING_DONE_TOP_LEFT,
                    keywords = listOf("完成")
                )
                if (topDone != null && topDone.fullText.length <= 4) {
                    AppLogBus.info("  检测到落地页左上角「完成」，点击返回广告")
                    host.safeTap(topDone, "关闭落地页:完成", preDelayMs = 180L)
                    delay(900L)
                    continue
                }

                // 6) 读取右上角倒计时秒数（仅每 5 秒打印一次，清爽直观）
                val cdHit = host.ocrFindInFrame(
                    frame = frame,
                    region = PctRegion.AD_CLAIM_TOP,
                    keywords = listOf("秒后", "秒可领")
                )
                if (cdHit != null) {
                    val sec = extractFirstNumber(cdHit.fullText)
                    if (sec != null && sec != lastLoggedSec && sec % 5 == 0) {
                        AppLogBus.info("  第 $displayRound 轮倒计时中... ${cdHit.fullText.take(14)}")
                        lastLoggedSec = sec
                    }
                }
            } finally {
                if (!frame.isRecycled) {
                    frame.recycle()
                }
            }

            delay(650L)
        }
        return WaitAdOutcome.TIMEOUT
    }

    /**
     * 阶段五：确定性两步走领取（完整复刻 qishuiAScriptProject.claim_reward，无多余杂支！）
     *   步骤 1/2：点击右上角「领取成功 ×」
     *   步骤 2/2：等待 0.55s 动画后，紧盯中间弹窗区 AD_REWARD_MODAL 高频轮询：
     *            - 若 shouldChainNext=true  -> 点击「领取奖励 / 再看一个」直接进入下一轮广告！
     *            - 若 shouldChainNext=false -> 点击「坚持退出」干净收尾退出！
     */
    private suspend fun claimRewardTwoStep(shouldChainNext: Boolean): ClaimOutcome {
        val (screenW, screenH) = host.getScreenSize()

        // ========== 步骤 1/2：定位并点击右上角「领取成功 ×」 ==========
        AppLogBus.info("  [步骤 1/2] 点击右上角「领取成功 ×」...")
        val claimHit = host.findInRegion(
            region = PctRegion.AD_CLAIM_TOP,
            keywords = listOf("领取成功", "奖励已领取", "跳过"),
            excludeContaining = listOf("秒后", "秒可领")
        )

        val closeNode = host.findTopRightCloseNode(claimHit?.bounds)
        when {
            closeNode != null -> {
                host.safeTap(closeNode, "右上角领取成功(${closeNode.matchedKeyword})", preDelayMs = Humanizer.randomAdActionDelayMs(true))
            }
            claimHit != null -> {
                // 对准「领取成功 ×」胶囊偏右侧 × 处点击，触发率最高
                val rightEdgeLeft = claimHit.bounds.right - 12
                val rightEdgeRight = min(screenW - 14, claimHit.bounds.right + 56)
                val tapRect = if (rightEdgeRight > rightEdgeLeft + 14) {
                    Rect(rightEdgeLeft, claimHit.bounds.top, rightEdgeRight, claimHit.bounds.bottom)
                } else {
                    claimHit.bounds
                }
                host.safeTapRect(tapRect, "右上角领取成功×", preDelayMs = Humanizer.randomAdActionDelayMs(true))
            }
            else -> {
                AppLogBus.warn("  [步骤 1/2] 未重新定位到右上角「领取成功」，本轮重试")
                return ClaimOutcome.FAILED
            }
        }

        // ========== 步骤 2/2：留 0.55s 弹窗动画时间，随后每 0.4s 紧盯中间弹窗区 AD_REWARD_MODAL ==========
        delay(550L)
        val step2Goal = if (shouldChainNext) "领取奖励(连看下一轮)" else "坚持退出(完成收尾)"
        AppLogBus.info("  [步骤 2/2] 紧盯中间弹窗等待「$step2Goal」...")

        val deadline2 = System.currentTimeMillis() + 12_000L
        var checks2 = 0
        while (System.currentTimeMillis() < deadline2) {
            checks2++

            // 检查是否提示当日额度已满
            val noMore = host.findInRegion(
                region = PctRegion.AD_REWARD_MODAL,
                keywords = listOf("当前无新视频", "暂无视频", "今日次数已达上限")
            )
            if (noMore != null) {
                return ClaimOutcome.NO_MORE_ADS
            }

            if (shouldChainNext) {
                // 连看模式：只找中间弹窗的「领取奖励 / 再看一个 / 继续观看」
                val rewardBtn = host.findInRegion(
                    region = PctRegion.AD_REWARD_MODAL,
                    keywords = listOf("领取奖励", "再看一个", "再看1个", "继续观看"),
                    excludeContaining = listOf("秒后", "秒可领")
                )
                if (rewardBtn != null) {
                    AppLogBus.info("  [命中] 中间弹窗 '${rewardBtn.fullText}' (轮询 $checks2 次) -> 点击直接进下一条广告！")
                    host.safeTap(rewardBtn, "弹窗连看:${rewardBtn.matchedKeyword}", preDelayMs = 260L)
                    delay(1400L)
                    return ClaimOutcome.CHAINED_NEXT_AD
                }

                // 几何双保险：若 OCR 先认出了白色卡片上的「视频提前得」或「坚持退出」，绿色「领取奖励」必在其正位
                val exitAnchor = host.findInRegion(
                    region = PctRegion.AD_REWARD_MODAL,
                    keywords = listOf("坚持退出")
                )
                if (exitAnchor != null) {
                    val greenCenterY = (exitAnchor.bounds.centerY() - screenH * 0.067f).toInt()
                    val greenRect = Rect(
                        (screenW * 0.32f).toInt(),
                        greenCenterY - (screenH * 0.022f).toInt(),
                        (screenW * 0.68f).toInt(),
                        greenCenterY + (screenH * 0.022f).toInt()
                    )
                    AppLogBus.info("  [锚点命中] 按「坚持退出」上方锁定绿色「领取奖励」 -> 点击进下一条广告！")
                    host.safeTapRect(greenRect, "弹窗绿色领取奖励", preDelayMs = 260L)
                    delay(1400L)
                    return ClaimOutcome.CHAINED_NEXT_AD
                }
            } else {
                // 最后一轮收尾模式：点击「坚持退出」离开广告容器
                val exitBtn = host.findInRegion(
                    region = PctRegion.AD_REWARD_MODAL,
                    keywords = listOf("坚持退出", "残忍离开", "放弃奖励")
                )
                if (exitBtn != null) {
                    AppLogBus.info("  [命中] 已达目标轮数，点击 '${exitBtn.fullText}' 结束循环")
                    host.safeTap(exitBtn, "完成退出:${exitBtn.matchedKeyword}", preDelayMs = 260L)
                    delay(1200L)
                    return ClaimOutcome.FINISHED_ALL_ROUNDS
                }
            }

            // 兼容极少数广告点完右上角 × 直接自动关回福利抽屉（未弹中间框）的情况
            val backOnSheet = host.findNodeInRegion(
                region = PctRegion.FIRST_AD_ENTRY_AREA,
                keywords = listOf("提前领", "领时长", "今日畅听")
            )
            if (backOnSheet != null) {
                return if (shouldChainNext) {
                    AppLogBus.info("  广告已返回福利抽屉，直接点击 '${backOnSheet.fullText}' 续接下一条")
                    host.safeTap(backOnSheet, "抽屉续看", preDelayMs = 240L)
                    delay(1400L)
                    ClaimOutcome.CHAINED_NEXT_AD
                } else {
                    ClaimOutcome.FINISHED_ALL_ROUNDS
                }
            }

            // 若第 4 次轮询（约 2s）还没弹出中间框，说明第一次右上角 × 可能没点实，补点一次右上角
            if (checks2 == 4) {
                AppLogBus.info("  弹窗尚未出现，补点一次右上角「领取成功 ×」...")
                val retryClose = host.findTopRightCloseNode()
                if (retryClose != null) {
                    host.safeTap(retryClose, "补点右上角×", preDelayMs = 150L)
                } else {
                    val topRightFallback = Rect(
                        (screenW * 0.88f).toInt(),
                        (screenH * 0.050f).toInt(),
                        (screenW * 0.948f).toInt(),
                        (screenH * 0.075f).toInt()
                    )
                    host.safeTapRect(topRightFallback, "补点右上角×坐标", preDelayMs = 150L)
                }
            }

            delay(380L)
        }

        return if (!shouldChainNext) ClaimOutcome.FINISHED_ALL_ROUNDS else ClaimOutcome.FAILED
    }

    private fun extractFirstNumber(text: String): Int? {
        val m = NUMBER_REGEX.find(text) ?: return null
        return m.value.toIntOrNull()
    }

    companion object {
        private val NUMBER_REGEX = Regex("\\d+")
    }
}
