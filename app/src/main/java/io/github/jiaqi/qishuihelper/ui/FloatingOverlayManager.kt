package io.github.jiaqi.qishuihelper.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Rect
import android.provider.Settings
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import io.github.jiaqi.qishuihelper.R
import io.github.jiaqi.qishuihelper.databinding.ViewFloatingCapsuleBinding
import io.github.jiaqi.qishuihelper.model.TaskStatus
import io.github.jiaqi.qishuihelper.util.AppLogBus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.abs

class FloatingOverlayManager(
    context: Context,
    private val onStartClicked: () -> Unit,
    private val onStopClicked: () -> Unit
) {
    private val appContext = context.applicationContext
    private val windowManager = appContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    private var binding: ViewFloatingCapsuleBinding? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private var isAdded = false
    private var keepPinnedWhenIdle = false

    fun isShowing(): Boolean = isAdded

    @SuppressLint("ClickableViewAccessibility")
    fun showIfPermitted(pinnedWhenIdle: Boolean = keepPinnedWhenIdle) {
        keepPinnedWhenIdle = keepPinnedWhenIdle || pinnedWhenIdle
        if (isAdded || !Settings.canDrawOverlays(appContext)) return

        runCatching {
            val themedContext = ContextThemeWrapper(appContext, R.style.Theme_QishuiAdHelper)
            val vb = ViewFloatingCapsuleBinding.inflate(LayoutInflater.from(themedContext))

            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.END
                x = 18
                y = 300
            }

            vb.btnCapsuleStart.setOnClickListener {
                onStartClicked.invoke()
            }

            vb.btnCapsuleStop.setOnClickListener {
                onStopClicked.invoke()
            }

            vb.btnCapsuleClose.setOnClickListener {
                keepPinnedWhenIdle = false
                onStopClicked.invoke()
                dismiss()
            }

            var initialX = 0
            var initialY = 0
            var downRawX = 0f
            var downRawY = 0f
            var dragging = false

            vb.rootCapsule.setOnTouchListener { _, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = params.x
                        initialY = params.y
                        downRawX = event.rawX
                        downRawY = event.rawY
                        dragging = false
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = (downRawX - event.rawX).toInt()
                        val dy = (event.rawY - downRawY).toInt()
                        if (abs(dx) > 6 || abs(dy) > 6) {
                            dragging = true
                            params.x = (initialX + dx).coerceAtLeast(0)
                            params.y = (initialY + dy).coerceAtLeast(60)
                            if (isAdded) {
                                windowManager.updateViewLayout(vb.root, params)
                            }
                        }
                        dragging
                    }
                    else -> dragging
                }
            }

            windowManager.addView(vb.root, params)
            binding = vb
            layoutParams = params
            isAdded = true
            updateStatus(AppLogBus.status.value)
        }.onFailure { err ->
            AppLogBus.warn("显示悬浮胶囊失败：${err.message}")
        }
    }

    fun updateStatus(status: TaskStatus) {
        val vb = binding ?: return
        val running = status.phase.isRunning
        vb.btnCapsuleStart.visibility = if (running) View.GONE else View.VISIBLE
        vb.btnCapsuleStop.visibility = if (running) View.VISIBLE else View.GONE
        vb.btnCapsuleClose.visibility = if (running) View.GONE else View.VISIBLE

        val roundInfo = if (status.currentRound > 0 || running) {
            "[${status.currentRound}/${status.maxRounds}] "
        } else {
            ""
        }
        val timerInfo = if (running && status.roundElapsedSec > 0) {
            " ${status.roundElapsedSec}s"
        } else {
            ""
        }
        vb.tvCapsuleStatus.text = "$roundInfo${status.phase.label}$timerInfo"
    }

    fun onTaskFinished() {
        if (!keepPinnedWhenIdle) {
            dismiss()
        } else {
            updateStatus(AppLogBus.status.value)
        }
    }

    fun getBoundsOnScreen(): Rect? {
        val root = binding?.root ?: return null
        if (!isAdded || root.visibility != View.VISIBLE) return null
        val loc = IntArray(2)
        root.getLocationOnScreen(loc)
        return Rect(loc[0], loc[1], loc[0] + root.width, loc[1] + root.height)
    }

    /**
     * 截屏 OCR 前短暂隐藏悬浮胶囊，避免遮挡汽水音乐按钮或识别进自身文字
     */
    suspend fun <T> withTemporarilyHidden(block: suspend () -> T): T {
        val root = binding?.root
        if (!isAdded || root == null) {
            return block()
        }
        withContext(Dispatchers.Main) {
            root.alpha = 0f
        }
        delay(55L)
        return try {
            block()
        } finally {
            withContext(Dispatchers.Main) {
                binding?.root?.alpha = 1f
            }
        }
    }

    fun dismiss() {
        keepPinnedWhenIdle = false
        val vb = binding ?: return
        if (isAdded) {
            runCatching { windowManager.removeView(vb.root) }
        }
        binding = null
        layoutParams = null
        isAdded = false
    }
}
