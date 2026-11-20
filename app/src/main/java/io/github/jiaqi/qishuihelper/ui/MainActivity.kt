package io.github.jiaqi.qishuihelper.ui

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.view.accessibility.AccessibilityManager
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.github.jiaqi.qishuihelper.R
import io.github.jiaqi.qishuihelper.config.RuleConfigRepository
import io.github.jiaqi.qishuihelper.databinding.ActivityMainBinding
import io.github.jiaqi.qishuihelper.model.RuleConfig
import io.github.jiaqi.qishuihelper.model.TaskPhase
import io.github.jiaqi.qishuihelper.service.AdHelperAccessibilityService
import io.github.jiaqi.qishuihelper.util.AppLogBus
import io.github.jiaqi.qishuihelper.util.VolumeGuard
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var configRepo: RuleConfigRepository

    private val notificationPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* 通知权限可选 */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(binding.rootScroll) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(
                view.paddingLeft,
                systemBars.top + 16,
                view.paddingRight,
                systemBars.bottom + 24
            )
            insets
        }

        configRepo = RuleConfigRepository(this)
        bindConfigToInputs(configRepo.load())
        setupClickListeners()
        observeTaskAndLogs()
        requestNotificationPermissionIfNeeded()
    }

    override fun onResume() {
        super.onResume()
        refreshPermissionChecklist()
    }

    private fun setupClickListeners() {
        binding.btnRestrictedGuide.setOnClickListener {
            showRestrictedSettingsGuide()
        }

        binding.btnPermAccessibility.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        binding.btnPermOverlay.setOnClickListener {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
        }

        binding.btnPermBattery.setOnClickListener {
            requestIgnoreBatteryOptimizations()
        }

        binding.btnSaveConfig.setOnClickListener {
            val saved = readConfigFromInputs()
            configRepo.save(saved)
            AppLogBus.info("已保存配置（连看=${saved.maxRounds}轮, 极速=${saved.fastMode}, 仅今日=${saved.stopOnTomorrow}）")
            Toast.makeText(this, "规则配置已保存", Toast.LENGTH_SHORT).show()
        }

        binding.btnResetConfig.setOnClickListener {
            val defaults = configRepo.resetToDefault()
            bindConfigToInputs(defaults)
            AppLogBus.info("已重置为 v1.2 默认极速连看配置")
            Toast.makeText(this, "已恢复默认配置", Toast.LENGTH_SHORT).show()
        }

        binding.btnStartTask.setOnClickListener {
            val latestConfig = readConfigFromInputs()
            configRepo.save(latestConfig)

            if (!ensureAccessibilityOrPrompt()) return@setOnClickListener

            val started = AdHelperAccessibilityService.requestStartTask()
            if (!started) {
                Toast.makeText(
                    this,
                    "无障碍服务正在重连，请去系统无障碍列表重新开关一次本服务",
                    Toast.LENGTH_LONG
                ).show()
            }
        }

        binding.btnShowOverlay.setOnClickListener {
            val latestConfig = readConfigFromInputs()
            configRepo.save(latestConfig)

            if (!Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "请先授予悬浮窗权限", Toast.LENGTH_SHORT).show()
                startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                )
                return@setOnClickListener
            }
            if (!ensureAccessibilityOrPrompt()) return@setOnClickListener

            val ok = AdHelperAccessibilityService.requestShowPinnedOverlay()
            if (ok) {
                Toast.makeText(this, "悬浮窗已开启，现在可打开汽水音乐点「▶ 启动」", Toast.LENGTH_LONG).show()
                packageManager.getLaunchIntentForPackage(latestConfig.targetPackage)?.let {
                    it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
                    runCatching { startActivity(it) }
                }
            } else {
                Toast.makeText(this, "无障碍实例尚未就绪，请重新开关一次无障碍服务", Toast.LENGTH_LONG).show()
            }
        }

        binding.btnStopTask.setOnClickListener {
            AdHelperAccessibilityService.requestStopTask("已在控制台手动停止")
            VolumeGuard(this).restoreIfNeeded()
            Toast.makeText(this, "已停止并恢复原音量", Toast.LENGTH_SHORT).show()
        }

        binding.btnCopyLogs.setOnClickListener {
            val text = AppLogBus.logs.value.joinToString("\n")
            if (text.isEmpty()) {
                Toast.makeText(this, "暂无日志可复制", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("qishui_helper_logs", text))
            Toast.makeText(this, "运行日志已复制到剪贴板", Toast.LENGTH_SHORT).show()
        }

        binding.btnClearLogs.setOnClickListener {
            AppLogBus.clearLogs()
        }
    }

    private fun ensureAccessibilityOrPrompt(): Boolean {
        if (isAccessibilityEnabled()) return true
        MaterialAlertDialogBuilder(this)
            .setTitle("请先开启无障碍服务")
            .setMessage("需要开启「${getString(R.string.accessibility_service_label)}」才能执行极速连看。\n\n若开关呈灰色提示「受限设置」，请先点「受限设置说明」。")
            .setPositiveButton("去开启") { _, _ ->
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
            .setNeutralButton("受限设置说明") { _, _ ->
                showRestrictedSettingsGuide()
            }
            .setNegativeButton("取消", null)
            .show()
        return false
    }

    private fun observeTaskAndLogs() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    AppLogBus.status.collect { status ->
                        binding.tvTaskBadge.text = status.phase.label
                        val badgeColor = when (status.phase) {
                            TaskPhase.COMPLETED -> R.color.ok_green
                            TaskPhase.ERROR -> R.color.danger_red
                            TaskPhase.STOPPED -> R.color.warn_amber
                            else -> R.color.brand_primary
                        }
                        binding.tvTaskBadge.setTextColor(ContextCompat.getColor(this@MainActivity, badgeColor))
                        binding.tvTaskDetail.text = status.detail
                        binding.btnStartTask.isEnabled = !status.phase.isRunning
                    }
                }

                launch {
                    AppLogBus.logs.collect { lines ->
                        binding.tvLogs.text = if (lines.isEmpty()) {
                            "等待任务启动..."
                        } else {
                            lines.joinToString("\n")
                        }
                        binding.scrollLogs.post {
                            binding.scrollLogs.fullScroll(View.FOCUS_DOWN)
                        }
                    }
                }
            }
        }
    }

    private fun refreshPermissionChecklist() {
        val accOk = isAccessibilityEnabled()
        binding.tvPermAccessibility.text = if (accOk) {
            "无障碍服务：✅ 已启用就绪"
        } else {
            "无障碍服务：❌ 未开启（必选）"
        }
        binding.btnPermAccessibility.text = if (accOk) "已开启" else "去开启"

        val overlayOk = Settings.canDrawOverlays(this)
        binding.tvPermOverlay.text = if (overlayOk) {
            "悬浮窗胶囊：✅ 已授权（支持随时启停）"
        } else {
            "悬浮窗胶囊：⚠️ 未授权（强烈建议开启）"
        }
        binding.btnPermOverlay.text = if (overlayOk) "已授权" else "去授权"

        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        val batteryOk = pm.isIgnoringBatteryOptimizations(packageName)
        binding.tvPermBattery.text = if (batteryOk) {
            "后台省电白名单：✅ 已加入白名单"
        } else {
            "后台省电白名单：⚠️ 未加入（防看广告中途被杀）"
        }
        binding.btnPermBattery.text = if (batteryOk) "已设置" else "设白名单"
    }

    private fun isAccessibilityEnabled(): Boolean {
        if (AdHelperAccessibilityService.isServiceActive()) return true
        val am = getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        val enabledList = am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
        val expectedComponent = ComponentName(this, AdHelperAccessibilityService::class.java)
        return enabledList.any { info ->
            val resolved = info.resolveInfo?.serviceInfo
            resolved != null &&
                resolved.packageName == expectedComponent.packageName &&
                resolved.name == expectedComponent.className
        }
    }

    @SuppressLint("BatteryLife")
    private fun requestIgnoreBatteryOptimizations() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (pm.isIgnoringBatteryOptimizations(packageName)) {
            Toast.makeText(this, "已在省电白名单中", Toast.LENGTH_SHORT).show()
            return
        }
        runCatching {
            val intent = Intent(
                Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
        }.onFailure {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }

    private fun showRestrictedSettingsGuide() {
        MaterialAlertDialogBuilder(this)
            .setTitle("Android 13/14/15 解锁「受限设置」与防坑指南")
            .setMessage(
                "1. 若无障碍开关变灰提示「受限设置」：\n" +
                    "   • 先在无障碍列表点一下灰色开关触发系统提示；\n" +
                    "   • 点击下方「打开本应用详情页」；\n" +
                    "   • 点页面右上角「⋮」菜单 -> 选择「允许受限设置」；\n" +
                    "   • 返回无障碍设置即可正常开启。\n\n" +
                    "2. 小米/澎湃/vivo/OPPO 点击无效防坑：\n" +
                    "   • 开发者选项中请同时开启「USB 调试（安全设置）」或「禁止权限监控」。\n\n" +
                    "3. 防摇一摇外跳建议：\n" +
                    "   • 在系统应用管理中，将汽水音乐的「设备动作与方向」与「打开其他应用」权限设为拒绝。"
            )
            .setPositiveButton("打开本应用详情页") { _, _ ->
                val intent = Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:$packageName")
                )
                startActivity(intent)
            }
            .setNegativeButton("知道了", null)
            .show()
    }

    private fun bindConfigToInputs(config: RuleConfig) {
        binding.etTargetPackage.setText(config.targetPackage)
        binding.etMaxRounds.setText(config.maxRounds.toString())
        binding.etSingleTimeoutSec.setText(config.singleAdTimeoutSec.toString())
        binding.switchFastMode.isChecked = config.fastMode
        binding.switchAutoMute.isChecked = config.autoMuteMedia
        binding.switchStopOnTomorrow.isChecked = config.stopOnTomorrow
        binding.etEntryKeywords.setText(RuleConfigRepository.joinPipeList(config.entryKeywords))
        binding.etUnlockedKeywords.setText(RuleConfigRepository.joinPipeList(config.unlockedKeywords))
        binding.etRewardDoneKeywords.setText(RuleConfigRepository.joinPipeList(config.rewardDoneKeywords))
        binding.etNextAdKeywords.setText(RuleConfigRepository.joinPipeList(config.nextAdKeywords))
        binding.etNoVideoKeywords.setText(RuleConfigRepository.joinPipeList(config.noVideoKeywords))
    }

    private fun readConfigFromInputs(): RuleConfig {
        val defaults = RuleConfig()
        val pkg = binding.etTargetPackage.text?.toString()?.trim().orEmpty()
            .ifEmpty { defaults.targetPackage }
        val rounds = binding.etMaxRounds.text?.toString()?.toIntOrNull()?.coerceIn(1, 30)
            ?: defaults.maxRounds
        val timeout = binding.etSingleTimeoutSec.text?.toString()?.toIntOrNull()?.coerceIn(45, 120)
            ?: defaults.singleAdTimeoutSec
        val fastMode = binding.switchFastMode.isChecked
        val autoMute = binding.switchAutoMute.isChecked
        val stopOnTomorrow = binding.switchStopOnTomorrow.isChecked

        val entry = RuleConfigRepository.parsePipeInput(binding.etEntryKeywords.text?.toString().orEmpty())
            .ifEmpty { defaults.entryKeywords }
        val unlocked = RuleConfigRepository.parsePipeInput(binding.etUnlockedKeywords.text?.toString().orEmpty())
            .ifEmpty { defaults.unlockedKeywords }
        val done = RuleConfigRepository.parsePipeInput(binding.etRewardDoneKeywords.text?.toString().orEmpty())
            .ifEmpty { defaults.rewardDoneKeywords }
        val next = RuleConfigRepository.parsePipeInput(binding.etNextAdKeywords.text?.toString().orEmpty())
            .ifEmpty { defaults.nextAdKeywords }
        val noVideo = RuleConfigRepository.parsePipeInput(binding.etNoVideoKeywords.text?.toString().orEmpty())
            .ifEmpty { defaults.noVideoKeywords }

        return RuleConfig(
            targetPackage = pkg,
            maxRounds = rounds,
            singleAdTimeoutSec = timeout,
            autoMuteMedia = autoMute,
            fastMode = fastMode,
            stopOnTomorrow = stopOnTomorrow,
            entryKeywords = entry,
            unlockedKeywords = unlocked,
            rewardDoneKeywords = done,
            nextAdKeywords = next,
            noVideoKeywords = noVideo
        )
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                notificationPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }
}
