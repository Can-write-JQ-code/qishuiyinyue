package io.github.jiaqi.qishuihelper.service

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.graphics.Rect
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
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
        overlayManager = FloatingOverlayManager(this) {
            stopCurrentTask("已通过悬浮胶囊紧急停止")
        }
        stateMachine = TaskStateMachine(
            host = this,
            ocrHelper = ocrHelper,
            volumeGuard = volumeGuard
        )
        instance = this
        AppLogBus.info("无障碍引擎已连接就绪（空闲零功耗待命）")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // 空闲状态不做任何节点遍历，仅在收到窗口切换事件时轻量记录包名与顶层类名
        val ev = event ?: return
        if (ev.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val pkg = ev.packageName?.toString().orEmpty()
            val cls = ev.className?.toString().orEmpty()
            if (pkg.isNotEmpty() && pkg != "com.android.systemui") {
                latestPackageName = pkg
            }
            if (cls.isNotEmpty() &&
                !cls.startsWith("android.widget.") &&
                !cls.startsWith("android.view.") &&
                !cls.startsWith("androidx.")
            ) {
                latestActivityClass = cls
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

    fun startAutomationTask() {
        if (runningJob?.isActive == true) {
            AppLogBus.warn("任务已在运行中，无需重复启动")
            return
        }
        val config = configRepo.load()
        overlayManager.showIfPermitted()
        KeepAliveForegroundService.startOrUpdate(this, "正在启动自动领畅听任务...")

        runningJob = serviceScope.launch {
            try {
                stateMachine.execute(config)
            } finally {
                // 任务结束后保留悬浮胶囊显示结果 3 秒再自动收起
                delay(3000L)
                if (runningJob?.isActive != true) {
                    overlayManager.dismiss()
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
        if (::overlayManager.isInitialized) {
            overlayManager.dismiss()
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
        val activePkg = rootInActiveWindow?.packageName?.toString().orEmpty()
        return activePkg.ifEmpty { latestPackageName }
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
            AppLogBus.warn("拉起应用($targetPackage)失败：${err.message}")
            false
        }
    }

    /**
     * 遍历当前活动窗口无障碍节点树匹配关键词
     */
    internal fun findNodeMatch(
        keywords: List<String>,
        excludeContaining: List<String> = emptyList(),
        regionPredicate: ((Rect) -> Boolean)? = null
    ): TargetMatch? {
        val root = rootInActiveWindow ?: return null
        val nodes = ArrayList<AccessibilityNodeInfo>(128)
        collectVisibleNodes(root, nodes)

        val rect = Rect()
        for (keyword in keywords) {
            val cleanKey = keyword.replace(" ", "")
            if (cleanKey.isEmpty()) continue

            for (node in nodes) {
                val candidates = listOfNotNull(
                    node.text?.toString()?.trim(),
                    node.contentDescription?.toString()?.trim()
                )
                for (raw in candidates) {
                    if (raw.isEmpty()) continue
                    val normalized = raw.replace(" ", "")
                    if (excludeContaining.any { ex -> normalized.contains(ex) }) continue
                    if (cleanKey.length == 1 && normalized.length > 4) continue
                    if (!normalized.contains(cleanKey)) continue

                    node.getBoundsInScreen(rect)
                    if (rect.width() <= 6 || rect.height() <= 6) continue
                    val copyRect = Rect(rect)
                    if (regionPredicate != null && !regionPredicate(copyRect)) continue

                    return TargetMatch(
                        matchedKeyword = keyword,
                        fullText = raw,
                        bounds = copyRect,
                        source = MatchSource.NODE
                    )
                }
            }
        }
        return null
    }

    /**
     * 查找激励广告右上角纯图标关闭按钮节点（无文字的 × ImageView）
     */
    internal fun findTopRightCloseIconNode(screenW: Int, screenH: Int): Rect? {
        val root = rootInActiveWindow ?: return null
        val nodes = ArrayList<AccessibilityNodeInfo>(96)
        collectVisibleNodes(root, nodes)

        val rect = Rect()
        val minLeft = (screenW * 0.78f).toInt()
        val maxBottom = (screenH * 0.16f).toInt()

        for (node in nodes) {
            if (!node.isClickable) continue
            node.getBoundsInScreen(rect)
            if (rect.left >= minLeft &&
                rect.top >= 10 &&
                rect.bottom <= maxBottom &&
                rect.width() in 24..180 &&
                rect.height() in 24..160
            ) {
                return Rect(rect)
            }
        }
        return null
    }

    private fun collectVisibleNodes(
        node: AccessibilityNodeInfo,
        out: MutableList<AccessibilityNodeInfo>
    ) {
        if (out.size >= 400) return
        if (node.isVisibleToUser) {
            out.add(node)
        }
        val count = node.childCount
        for (i in 0 until count) {
            val child = node.getChild(i) ?: continue
            collectVisibleNodes(child, out)
        }
    }

    internal suspend fun captureScreenOcrLines(): List<OcrLineRecord> {
        return overlayManager.withTemporarilyHidden {
            ocrHelper.captureAndRecognize(
                service = this@AdHelperAccessibilityService,
                excludeRect = overlayManager.getBoundsOnScreen()
            )
        }
    }

    internal suspend fun performHumanTap(bounds: Rect, reason: String): Boolean {
        val waitMs = Humanizer.randomReactionDelayMs()
        AppLogBus.info("准备点击[$reason]（拟人等待 ${waitMs}ms）")
        delay(waitMs)

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
            AppLogBus.warn("手势点击被系统取消[$reason]（请检查开发者选项「USB调试(安全设置)」）")
        }
        return dispatched
    }

    internal suspend fun performGlobalBack(reason: String) {
        delay(Humanizer.randomBackDelayMs())
        val ok = performGlobalAction(GLOBAL_ACTION_BACK)
        AppLogBus.info("执行全局返回[$reason] -> result=$ok")
    }

    companion object {
        @Volatile
        var instance: AdHelperAccessibilityService? = null
            private set

        fun isServiceActive(): Boolean = instance != null

        fun requestStartTask(): Boolean {
            val svc = instance ?: return false
            svc.startAutomationTask()
            return true
        }

        fun requestStopTask(reason: String = "用户手动停止") {
            instance?.stopCurrentTask(reason)
        }
    }
}
