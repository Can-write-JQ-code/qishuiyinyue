package io.github.jiaqi.qishuihelper.service

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import io.github.jiaqi.qishuihelper.config.RuleConfigRepository
import io.github.jiaqi.qishuihelper.model.MatchSource
import io.github.jiaqi.qishuihelper.model.PctRegion
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

    @Volatile
    private var latestActivityClass: String = ""

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
        AppLogBus.info("无障碍引擎 v2.0 已就绪（局部极速裁剪 OCR + 确定性两步连看内核）")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val ev = event ?: return
        if (ev.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val pkg = ev.packageName?.toString().orEmpty()
            val cls = ev.className?.toString().orEmpty()
            if (pkg.isNotEmpty() && pkg != "com.android.systemui" && pkg != packageName) {
                latestPackageName = pkg
            }
            if (cls.isNotEmpty() && pkg != packageName && cls.endsWith("Activity")) {
                latestActivityClass = cls
            }
        }
    }

    override fun onInterrupt() {
        AppLogBus.warn("无障碍服务收到中断通知")
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
        KeepAliveForegroundService.startOrUpdate(this, "正在运行两步连看循环...")

        runningJob = serviceScope.launch {
            try {
                stateMachine.execute(config, startedFromOverlay = fromFloatingButton)
            } finally {
                delay(2000L)
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
        AppLogBus.updateStatus(
            TaskStatus(
                phase = TaskPhase.STOPPED,
                detail = reason
            )
        )
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
            AppLogBus.warn("启动目标应用失败：${err.message}")
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
        if (out.size >= 500) return
        if (node.isVisibleToUser) {
            out.add(node)
        }
        val count = node.childCount
        for (i in 0 until count) {
            val child = node.getChild(i) ?: continue
            collectVisibleNodesRecursive(child, out)
        }
    }

    /**
     * 在指定百分比区域内检索无障碍节点（耗时 <2ms）
     */
    internal fun findNodeInRegion(
        region: PctRegion,
        keywords: List<String>,
        excludeContaining: List<String> = emptyList()
    ): TargetMatch? {
        val nodes = collectAllVisibleNodes()
        if (nodes.isEmpty()) return null
        val (screenW, screenH) = getScreenSize()
        val regionRect = region.toScreenRect(screenW, screenH)

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
                    if (cleanKey.length == 1 && normalized.length > 3) continue
                    if (!normalized.contains(cleanKey)) continue

                    node.getBoundsInScreen(rect)
                    if (rect.width() <= 4 || rect.height() <= 4) continue
                    if (!regionRect.contains(rect.centerX(), rect.centerY())) continue

                    return TargetMatch(
                        matchedKeyword = keyword,
                        fullText = raw,
                        bounds = Rect(rect),
                        source = MatchSource.NODE,
                        clickableNode = findClickableSelfOrAncestor(node, screenW, screenH)
                    )
                }
            }
        }
        return null
    }

    /**
     * 区域双保险查找：先查区域内无障碍节点（<2ms），未命中则只裁剪该局部区域跑极速 OCR（~35ms）
     */
    internal suspend fun findInRegion(
        region: PctRegion,
        keywords: List<String>,
        excludeContaining: List<String> = emptyList()
    ): TargetMatch? {
        findNodeInRegion(region, keywords, excludeContaining)?.let { return it }

        val (screenW, screenH) = getScreenSize()
        val targetRect = region.toScreenRect(screenW, screenH)
        val lines = overlayManager.withHiddenIfOverlaps(targetRect) {
            ocrHelper.captureAndRecognizeRegion(
                service = this@AdHelperAccessibilityService,
                region = region,
                excludeRect = overlayManager.getBoundsOnScreen()
            )
        }
        return ocrHelper.findMatchInLines(lines, keywords, excludeContaining)
    }

    /**
     * 抓取单帧完整 Bitmap，供同一个循环周期内按需裁剪多个小区域（避免重复截屏耗费限频额度）
     */
    internal suspend fun captureFrameBitmap(): Bitmap? {
        return overlayManager.withHiddenIfOverlaps(null) {
            ocrHelper.captureScreenBitmap(this@AdHelperAccessibilityService)
        }
    }

    internal suspend fun ocrFindInFrame(
        frame: Bitmap,
        region: PctRegion,
        keywords: List<String>,
        excludeContaining: List<String> = emptyList()
    ): TargetMatch? {
        val lines = ocrHelper.recognizeInRegion(
            fullBitmap = frame,
            region = region,
            excludeRect = overlayManager.getBoundsOnScreen()
        )
        return ocrHelper.findMatchInLines(lines, keywords, excludeContaining)
    }

    /**
     * 查找右上角直播间/激励广告的「关闭 / ×」控件节点（对齐 qishuiAScriptProject 的 Selector("关闭") 兜底）
     */
    internal fun findTopRightCloseNode(rewardBounds: Rect? = null): TargetMatch? {
        val nodes = collectAllVisibleNodes()
        if (nodes.isEmpty()) return null
        val (screenW, screenH) = getScreenSize()
        val rect = Rect()

        // 1) 优先找 label="领取成功，关闭" 或 desc="关闭"
        for (node in nodes) {
            val desc = node.contentDescription?.toString().orEmpty()
            val text = node.text?.toString().orEmpty()
            val combined = "$desc $text"
            if ((combined.contains("关闭") || combined.contains("跳过")) && !combined.contains("秒后")) {
                node.getBoundsInScreen(rect)
                if (rect.left >= (screenW * 0.55f).toInt() && rect.bottom <= (screenH * 0.16f).toInt() && rect.width() > 8) {
                    return TargetMatch(
                        matchedKeyword = "关闭节点",
                        fullText = combined.trim(),
                        bounds = Rect(rect),
                        source = MatchSource.NODE,
                        clickableNode = findClickableSelfOrAncestor(node, screenW, screenH)
                    )
                }
            }
        }

        // 2) 若已知「领取成功」文字框，找其右侧同高度小图标
        if (rewardBounds != null) {
            for (node in nodes) {
                node.getBoundsInScreen(rect)
                if (rect.width() in 14..150 &&
                    rect.height() in 14..140 &&
                    rect.left >= rewardBounds.right - 16 &&
                    abs(rect.centerY() - rewardBounds.centerY()) <= 70 &&
                    (node.isClickable || node.childCount == 0)
                ) {
                    return TargetMatch(
                        matchedKeyword = "右侧×节点",
                        fullText = "领取成功右侧×",
                        bounds = Rect(rect),
                        source = MatchSource.NODE,
                        clickableNode = findClickableSelfOrAncestor(node, screenW, screenH)
                    )
                }
            }
        }

        // 3) 右上角可点击小图标
        for (node in nodes) {
            node.getBoundsInScreen(rect)
            val cls = node.className?.toString().orEmpty()
            if ((node.isClickable || cls.endsWith("ImageView") || cls.endsWith("Button")) &&
                rect.left >= (screenW * 0.76f).toInt() &&
                rect.top >= 12 &&
                rect.bottom <= (screenH * 0.14f).toInt() &&
                rect.width() in 20..160 &&
                rect.height() in 20..150
            ) {
                return TargetMatch(
                    matchedKeyword = "右上角×图标",
                    fullText = cls.substringAfterLast('.'),
                    bounds = Rect(rect),
                    source = MatchSource.NODE,
                    clickableNode = findClickableSelfOrAncestor(node, screenW, screenH)
                )
            }
        }
        return null
    }

    /**
     * 专用于直播间的右上角关闭按钮查找：严格只匹配 desc/text="关闭" 且位于屏幕最右侧 85% 以外（绝不触碰主播「关注」或观众头像）
     */
    internal fun findLiveRoomCloseNode(): TargetMatch? {
        val nodes = collectAllVisibleNodes()
        if (nodes.isEmpty()) return null
        val (screenW, screenH) = getScreenSize()
        val rect = Rect()

        for (node in nodes) {
            val desc = node.contentDescription?.toString()?.trim().orEmpty()
            val text = node.text?.toString()?.trim().orEmpty()
            val combined = "$desc $text"
            if (combined.contains("关注")) continue
            if (desc == "关闭" || text == "关闭" || combined.contains("关闭直播")) {
                node.getBoundsInScreen(rect)
                if (rect.left >= (screenW * 0.84f).toInt() &&
                    rect.bottom <= (screenH * 0.15f).toInt() &&
                    rect.width() in 16..150
                ) {
                    return TargetMatch(
                        matchedKeyword = "关闭",
                        fullText = combined.trim(),
                        bounds = Rect(rect),
                        source = MatchSource.NODE,
                        clickableNode = findClickableSelfOrAncestor(node, screenW, screenH)
                    )
                }
            }
        }
        return null
    }

    internal fun findTopRightGoldCoinNode(): TargetMatch? {
        val nodes = collectAllVisibleNodes()
        if (nodes.isEmpty()) return null
        val (screenW, screenH) = getScreenSize()
        val rect = Rect()

        for (node in nodes) {
            node.getBoundsInScreen(rect)
            if (rect.left >= (screenW * 0.75f).toInt() &&
                rect.right <= (screenW * 0.88f).toInt() &&
                rect.top >= (screenH * 0.035f).toInt() &&
                rect.bottom <= (screenH * 0.095f).toInt() &&
                rect.width() in 24..160 &&
                rect.height() in 24..160
            ) {
                return TargetMatch(
                    matchedKeyword = "右上角金币图标",
                    fullText = "顶部金币入口",
                    bounds = Rect(rect),
                    source = MatchSource.NODE,
                    clickableNode = findClickableSelfOrAncestor(node, screenW, screenH)
                )
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

    internal suspend fun safeTap(
        match: TargetMatch,
        desc: String,
        preDelayMs: Long = 220L
    ): Boolean {
        if (preDelayMs > 0L) delay(preDelayMs)
        val ok = dispatchHumanTapAtBounds(match.bounds, desc)
        if (!ok && match.clickableNode != null) {
            val fallback = runCatching {
                match.clickableNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }.getOrDefault(false)
            AppLogBus.info("手势回退节点点击 [$desc] -> $fallback")
            return fallback
        }
        return ok
    }

    internal suspend fun safeTapRect(
        bounds: Rect,
        desc: String,
        preDelayMs: Long = 220L
    ): Boolean {
        if (preDelayMs > 0L) delay(preDelayMs)
        return dispatchHumanTapAtBounds(bounds, desc)
    }

    private suspend fun dispatchHumanTapAtBounds(bounds: Rect, desc: String): Boolean {
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
            AppLogBus.info("点击 [$desc] -> (${point.x.toInt()}, ${point.y.toInt()})")
        } else {
            AppLogBus.warn("点击未生效 [$desc]（请检查开发者选项「USB调试(安全设置)」）")
        }
        return dispatched
    }

    internal suspend fun performGlobalBack(reason: String) {
        delay(260L)
        val ok = performGlobalAction(GLOBAL_ACTION_BACK)
        AppLogBus.info("全局返回 [$reason] -> $ok")
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
