package io.github.jiaqi.qishuihelper.service

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.graphics.Rect
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import io.github.jiaqi.qishuihelper.config.RuleConfigRepository
import io.github.jiaqi.qishuihelper.model.MatchSource
import io.github.jiaqi.qishuihelper.model.TargetMatch
import io.github.jiaqi.qishuihelper.model.TaskPhase
import io.github.jiaqi.qishuihelper.model.TaskStatus
import io.github.jiaqi.qishuihelper.ocr.OcrLineRecord
import io.github.jiaqi.qishuihelper.ocr.ScreenOcrHelper
import io.github.jiaqi.qishuihelper.ui.FloatingOverlayManager
import io.github.jiaqi.qishuihelper.util.AppLogBus
import io.github.jiaqi.qishuihelper.util.Humanizer
import io.github.jiaqi.qishuihelper.util.VolumeGuard
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.math.abs

class AdHelperAccessibilityService : AccessibilityService() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var runningJob: Job? = null

    private lateinit var configRepo: RuleConfigRepository
    private lateinit var volumeGuard: VolumeGuard
    private lateinit var ocrHelper: ScreenOcrHelper
    private lateinit var overlayManager: FloatingOverlayManager
    private lateinit var stateMachine: TaskStateMachine

    @Volatile
    private var latestPackageName: String = ""

    /**
     * 仅记录真正的 Activity 类名（防止 VolumeDialog / CommonLoadingDialog / KeyboardMonitor 覆盖真实顶层 Activity）
     */
    @Volatile
    private var latestActivityClass: String = ""

    @Volatile
    private var latestWindowClass: String = ""

    override fun onServiceConnected() {
        super.onServiceConnected()
        configRepo = RuleConfigRepository(this)
        volumeGuard = VolumeGuard(this)
        ocrHelper = ScreenOcrHelper()
        overlayManager = FloatingOverlayManager(
            context = this,
            onStartClicked = {
                startAutomationTask(fromFloatingButton = true)
            },
            onStopClicked = {
                stopCurrentTask("已通过悬浮胶囊停止")
            }
        )
        stateMachine = TaskStateMachine(
            host = this,
            ocrHelper = ocrHelper,
            volumeGuard = volumeGuard
        )
        instance = this
        AppLogBus.info("无障碍引擎 v1.2 已就绪（极速节点快检 + 广告内无缝连看）")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val ev = event ?: return
        if (ev.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val pkg = ev.packageName?.toString().orEmpty()
            val cls = ev.className?.toString().orEmpty()
            if (pkg.isNotEmpty() && pkg != "com.android.systemui" && pkg != packageName) {
                latestPackageName = pkg
            }
            if (cls.isNotEmpty() && pkg != packageName) {
                latestWindowClass = cls
                if (cls.endsWith("Activity")) {
                    latestActivityClass = cls
                }
            }
        }
    }

    override fun onInterrupt() {
        AppLogBus.warn("无障碍服务收到系统中断通知")
    }

    override fun onDestroy() {
        stopCurrentTask("无障碍服务已关闭")
        if (::ocrHelper.isInitialized) {
            ocrHelper.close()
        }
        if (::overlayManager.isInitialized) {
            overlayManager.dismiss()
        }
        serviceScope.cancel()
        if (instance === this) {
            instance = null
        }
        super.onDestroy()
    }

    fun showPinnedFloatingCapsule() {
        if (::overlayManager.isInitialized) {
            overlayManager.showIfPermitted(pinnedWhenIdle = true)
        }
    }

    fun startAutomationTask(fromFloatingButton: Boolean = false) {
        if (runningJob?.isActive == true) {
            AppLogBus.warn("任务已在运行中，无需重复启动")
            return
        }
        val config = configRepo.load()
        overlayManager.showIfPermitted(pinnedWhenIdle = fromFloatingButton)
        KeepAliveForegroundService.startOrUpdate(this, "正在执行极速连看任务...")

        runningJob = serviceScope.launch {
            try {
                stateMachine.execute(config, startedFromOverlay = fromFloatingButton)
            } finally {
                delay(2200L)
                if (runningJob?.isActive != true) {
                    overlayManager.onTaskFinished()
                }
            }
        }
    }

    fun stopCurrentTask(reason: String) {
        val wasRunning = runningJob?.isActive == true
        runningJob?.cancel()
        runningJob = null
        if (::volumeGuard.isInitialized) {
            volumeGuard.restoreIfNeeded()
        }
        KeepAliveForegroundService.stop(this)
        if (wasRunning) {
            AppLogBus.info("已停止任务：$reason")
        }
        val stoppedStatus = TaskStatus(
            phase = TaskPhase.STOPPED,
            detail = reason
        )
        AppLogBus.updateStatus(stoppedStatus)
        if (::overlayManager.isInitialized) {
            overlayManager.onTaskFinished()
        }
    }

    internal fun onStatusUpdated(status: TaskStatus) {
        AppLogBus.updateStatus(status)
        overlayManager.updateStatus(status)
        if (status.phase.isRunning) {
            KeepAliveForegroundService.startOrUpdate(
                this,
                "${status.phase.label} · ${status.detail}"
            )
        }
    }

    internal fun getCurrentPackageName(): String {
        val appRoots = getTargetWindowRoots()
        val windowPkg = appRoots.firstOrNull()?.packageName?.toString().orEmpty()
        if (windowPkg.isNotEmpty()) return windowPkg
        val activePkg = rootInActiveWindow?.packageName?.toString().orEmpty()
        if (activePkg.isNotEmpty() && activePkg != packageName && activePkg != "com.android.systemui") {
            return activePkg
        }
        return latestPackageName
    }

    internal fun getCurrentActivityClass(): String = latestActivityClass

    internal fun getLatestWindowClass(): String = latestWindowClass

    internal fun getScreenSize(): Pair<Int, Int> {
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val bounds = wm.currentWindowMetrics.bounds
        return bounds.width() to bounds.height()
    }

    internal fun launchTargetApp(targetPackage: String): Boolean {
        val launchIntent = packageManager.getLaunchIntentForPackage(targetPackage)?.apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        } ?: return false

        return runCatching {
            startActivity(launchIntent)
            true
        }.getOrElse { err ->
            AppLogBus.warn("拉起应用($targetPackage)失败：${err.message}")
            false
        }
    }

    private fun getTargetWindowRoots(): List<AccessibilityNodeInfo> {
        val result = ArrayList<AccessibilityNodeInfo>(4)
        runCatching {
            for (win in windows) {
                if (win.type != AccessibilityWindowInfo.TYPE_APPLICATION) continue
                val root = win.root ?: continue
                val pkg = root.packageName?.toString().orEmpty()
                if (pkg == packageName || pkg == "com.android.systemui") continue
                result.add(root)
            }
        }
        if (result.isEmpty()) {
            val active = rootInActiveWindow
            val pkg = active?.packageName?.toString().orEmpty()
            if (active != null && pkg != packageName && pkg != "com.android.systemui") {
                result.add(active)
            }
        }
        return result
    }

    private fun collectAllVisibleNodes(): List<AccessibilityNodeInfo> {
        val roots = getTargetWindowRoots()
        if (roots.isEmpty()) return emptyList()
        val nodes = ArrayList<AccessibilityNodeInfo>(200)
        for (root in roots) {
            collectVisibleNodesRecursive(root, nodes)
        }
        return nodes
    }

    private fun collectVisibleNodesRecursive(
        node: AccessibilityNodeInfo,
        out: MutableList<AccessibilityNodeInfo>
    ) {
        if (out.size >= 600) return
        if (node.isVisibleToUser) {
            out.add(node)
        }
        val count = node.childCount
        for (i in 0 until count) {
            val child = node.getChild(i) ?: continue
            collectVisibleNodesRecursive(child, out)
        }
    }

    internal fun collectNodeTextPreview(maxItems: Int = 8): List<String> {
        val nodes = collectAllVisibleNodes()
        val texts = LinkedHashSet<String>()
        for (node in nodes) {
            node.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { texts.add(it.take(18)) }
            node.contentDescription?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { texts.add(it.take(18)) }
            if (texts.size >= maxItems) break
        }
        return texts.toList()
    }

    internal fun findNodeMatch(
        keywords: List<String>,
        excludeContaining: List<String> = emptyList(),
        regionPredicate: ((Rect) -> Boolean)? = null
    ): TargetMatch? {
        val nodes = collectAllVisibleNodes()
        if (nodes.isEmpty()) return null
        val (screenW, screenH) = getScreenSize()

        val rect = Rect()
        for (keyword in keywords) {
            val cleanKey = ScreenOcrHelper.normalizeText(keyword)
            if (cleanKey.isEmpty()) continue

            for (node in nodes) {
                val candidates = listOfNotNull(
                    node.text?.toString()?.trim(),
                    node.contentDescription?.toString()?.trim()
                )
                for (raw in candidates) {
                    val normalized = ScreenOcrHelper.normalizeText(raw)
                    if (normalized.isEmpty()) continue
                    if (excludeContaining.any { ex -> normalized.contains(ScreenOcrHelper.normalizeText(ex)) }) {
                        continue
                    }
                    if (cleanKey.length == 1 && normalized.length > 4) continue
                    if (!normalized.contains(cleanKey)) continue

                    node.getBoundsInScreen(rect)
                    if (rect.width() <= 4 || rect.height() <= 4) continue
                    val copyRect = Rect(rect)
                    if (regionPredicate != null && !regionPredicate(copyRect)) continue

                    val clickable = findClickableSelfOrAncestor(node, screenW, screenH)
                    return TargetMatch(
                        matchedKeyword = keyword,
                        fullText = raw,
                        bounds = copyRect,
                        source = MatchSource.NODE,
                        clickableNode = clickable
                    )
                }
            }
        }
        return null
    }

    private fun findClickableSelfOrAncestor(
        node: AccessibilityNodeInfo,
        screenW: Int,
        screenH: Int
    ): AccessibilityNodeInfo? {
        var cur: AccessibilityNodeInfo? = node
        val r = Rect()
        var depth = 0
        while (cur != null && depth < 5) {
            if (cur.isClickable) {
                cur.getBoundsInScreen(r)
                if (r.width() < (screenW * 0.92f).toInt() && r.height() < (screenH * 0.45f).toInt()) {
                    return cur
                }
            }
            cur = cur.parent
            depth++
        }
        return null
    }

    internal fun findAdCloseNodeTarget(
        rewardBounds: Rect?,
        screenW: Int,
        screenH: Int
    ): TargetMatch? {
        val nodes = collectAllVisibleNodes()
        if (nodes.isEmpty()) return null
        val rect = Rect()

        for (node in nodes) {
            val label = (node.contentDescription?.toString() ?: "") + " " + (node.text?.toString() ?: "")
            if (label.contains("领取成功") && label.contains("关闭") && !label.contains("秒后")) {
                node.getBoundsInScreen(rect)
                if (rect.width() > 6 && rect.height() > 6) {
                    return TargetMatch(
                        matchedKeyword = "领取成功，关闭",
                        fullText = label.trim(),
                        bounds = Rect(rect),
                        source = MatchSource.NODE,
                        clickableNode = findClickableSelfOrAncestor(node, screenW, screenH)
                    )
                }
            }
        }

        if (rewardBounds != null) {
            for (node in nodes) {
                node.getBoundsInScreen(rect)
                if (rect.width() in 14..150 &&
                    rect.height() in 14..140 &&
                    rect.left >= rewardBounds.right - 16 &&
                    abs(rect.centerY() - rewardBounds.centerY()) <= 70
                ) {
                    if (node.isClickable || node.childCount == 0) {
                        return TargetMatch(
                            matchedKeyword = "领取成功右侧×",
                            fullText = "右侧关闭按钮",
                            bounds = Rect(rect),
                            source = MatchSource.NODE,
                            clickableNode = findClickableSelfOrAncestor(node, screenW, screenH)
                        )
                    }
                }
            }
        }

        val minLeft = (screenW * 0.75f).toInt()
        val maxBottom = (screenH * 0.16f).toInt()
        for (node in nodes) {
            node.getBoundsInScreen(rect)
            val className = node.className?.toString().orEmpty()
            val isSmallIcon = (node.isClickable || className.endsWith("ImageView")) &&
                rect.left >= minLeft &&
                rect.top >= 12 &&
                rect.bottom <= maxBottom &&
                rect.width() in 20..160 &&
                rect.height() in 20..150
            if (isSmallIcon) {
                return TargetMatch(
                    matchedKeyword = "右上角×节点",
                    fullText = className.substringAfterLast('.'),
                    bounds = Rect(rect),
                    source = MatchSource.NODE,
                    clickableNode = findClickableSelfOrAncestor(node, screenW, screenH)
                )
            }
        }
        return null
    }

    internal suspend fun captureScreenOcrLines(): List<OcrLineRecord> {
        return overlayManager.withTemporarilyHidden {
            ocrHelper.captureAndRecognize(
                service = this@AdHelperAccessibilityService,
                excludeRect = overlayManager.getBoundsOnScreen()
            )
        }
    }

    internal suspend fun performSmartTap(
        match: TargetMatch,
        reason: String,
        delayMs: Long,
        preferNodeAction: Boolean = false
    ): Boolean {
        if (delayMs > 0L) {
            delay(delayMs)
        }

        if (preferNodeAction && match.clickableNode != null) {
            val nodeClicked = runCatching {
                match.clickableNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }.getOrDefault(false)
            if (nodeClicked) {
                AppLogBus.info("已通过节点快速点击[$reason]")
                return true
            }
        }

        val dispatched = dispatchHumanTapAtBounds(match.bounds, reason)
        if (!dispatched && match.clickableNode != null) {
            val fallbackOk = runCatching {
                match.clickableNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }.getOrDefault(false)
            AppLogBus.info("手势被拦截，已回退无障碍节点点击[$reason] -> result=$fallbackOk")
            return fallbackOk
        }
        return dispatched
    }

    internal suspend fun performHumanTap(bounds: Rect, reason: String, delayMs: Long): Boolean {
        if (delayMs > 0L) {
            delay(delayMs)
        }
        return dispatchHumanTapAtBounds(bounds, reason)
    }

    private suspend fun dispatchHumanTapAtBounds(bounds: Rect, reason: String): Boolean {
        val (screenW, screenH) = getScreenSize()
        val point = Humanizer.computeTapPoint(bounds, screenW, screenH)
        val gesture = Humanizer.buildHumanTapGesture(point)

        val dispatched = suspendCancellableCoroutine { cont ->
            val accepted = dispatchGesture(
                gesture,
                object : GestureResultCallback() {
                    override fun onCompleted(gestureDescription: android.accessibilityservice.GestureDescription?) {
                        if (cont.isActive) cont.resume(true)
                    }

                    override fun onCancelled(gestureDescription: android.accessibilityservice.GestureDescription?) {
                        if (cont.isActive) cont.resume(false)
                    }
                },
                null
            )
            if (!accepted && cont.isActive) {
                cont.resume(false)
            }
        }
        if (dispatched) {
            AppLogBus.info("已点击[$reason] -> (${point.x.toInt()}, ${point.y.toInt()})")
        } else {
            AppLogBus.warn("手势点击未生效[$reason]（请检查开发者选项「USB调试(安全设置)」）")
        }
        return dispatched
    }

    internal suspend fun performGlobalBack(reason: String, fastMode: Boolean = true) {
        delay(Humanizer.randomBackDelayMs(fastMode))
        val ok = performGlobalAction(GLOBAL_ACTION_BACK)
        AppLogBus.info("执行全局返回[$reason] -> result=$ok")
    }

    companion object {
        @Volatile
        var instance: AdHelperAccessibilityService? = null
            private set

        fun isServiceActive(): Boolean = instance != null

        fun requestShowPinnedOverlay(): Boolean {
            val svc = instance ?: return false
            svc.showPinnedFloatingCapsule()
            return true
        }

        fun requestStartTask(): Boolean {
            val svc = instance ?: return false
            svc.startAutomationTask(fromFloatingButton = false)
            return true
        }

        fun requestStopTask(reason: String = "用户手动停止") {
            instance?.stopCurrentTask(reason)
        }
    }
}
