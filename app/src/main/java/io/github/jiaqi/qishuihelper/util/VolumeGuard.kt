package io.github.jiaqi.qishuihelper.util

import android.content.Context
import android.media.AudioManager

class VolumeGuard(context: Context) {

    private val appContext = context.applicationContext
    private val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun muteIfNeeded(enabled: Boolean) {
        if (!enabled) return
        runCatching {
            val current = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
            if (current > 0) {
                prefs.edit()
                    .putInt(KEY_BACKUP_VOLUME, current)
                    .putBoolean(KEY_MUTED_BY_HELPER, true)
                    .apply()
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0)
                AppLogBus.info("音量守护：已备份媒体音量($current)并切换为系统静音")
            } else {
                AppLogBus.info("音量守护：当前媒体音量已为 0，保持静音")
            }
        }.onFailure { err ->
            AppLogBus.warn("静音设置失败（可能受系统勿扰策略限制）：${err.message}")
        }
    }

    fun restoreIfNeeded() {
        val mutedByHelper = prefs.getBoolean(KEY_MUTED_BY_HELPER, false)
        if (!mutedByHelper) return

        runCatching {
            val backup = prefs.getInt(KEY_BACKUP_VOLUME, -1)
            val current = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
            if (backup > 0 && current == 0) {
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, backup, 0)
                AppLogBus.info("音量守护：已恢复原媒体音量($backup)")
            } else if (current > 0) {
                AppLogBus.info("音量守护：检测到手动调节音量($current)，保留当前音量")
            }
        }.onFailure { err ->
            AppLogBus.warn("恢复媒体音量失败：${err.message}")
        }.also {
            prefs.edit().putBoolean(KEY_MUTED_BY_HELPER, false).apply()
        }
    }

    companion object {
        private const val PREFS_NAME = "qishui_volume_guard"
        private const val KEY_BACKUP_VOLUME = "backup_music_volume"
        private const val KEY_MUTED_BY_HELPER = "muted_by_helper"
    }
}
