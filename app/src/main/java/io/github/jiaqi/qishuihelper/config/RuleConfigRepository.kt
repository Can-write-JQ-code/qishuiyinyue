package io.github.jiaqi.qishuihelper.config

import android.content.Context
import io.github.jiaqi.qishuihelper.model.RuleConfig

class RuleConfigRepository(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    init {
        val savedVersion = prefs.getInt(KEY_SCHEMA_VERSION, 1)
        if (savedVersion < CURRENT_SCHEMA_VERSION) {
            prefs.edit().clear().putInt(KEY_SCHEMA_VERSION, CURRENT_SCHEMA_VERSION).apply()
        }
    }

    fun load(): RuleConfig {
        val defaults = RuleConfig()
        return RuleConfig(
            targetPackage = prefs.getString(KEY_TARGET_PACKAGE, defaults.targetPackage)
                ?.trim()
                ?.ifEmpty { defaults.targetPackage }
                ?: defaults.targetPackage,
            maxRounds = prefs.getInt(KEY_MAX_ROUNDS, defaults.maxRounds).coerceIn(1, 50),
            infiniteUntilQuotaEnd = prefs.getBoolean(KEY_INFINITE_MODE, defaults.infiniteUntilQuotaEnd),
            singleAdTimeoutSec = prefs.getInt(KEY_SINGLE_TIMEOUT, defaults.singleAdTimeoutSec).coerceIn(30, 90),
            maxIdleRounds = prefs.getInt(KEY_MAX_IDLE_ROUNDS, defaults.maxIdleRounds).coerceIn(3, 12),
            autoMuteMedia = prefs.getBoolean(KEY_AUTO_MUTE, defaults.autoMuteMedia)
        )
    }

    fun save(config: RuleConfig) {
        prefs.edit()
            .putInt(KEY_SCHEMA_VERSION, CURRENT_SCHEMA_VERSION)
            .putString(KEY_TARGET_PACKAGE, config.targetPackage.trim().ifEmpty { RuleConfig.DEFAULT_TARGET_PACKAGE })
            .putInt(KEY_MAX_ROUNDS, config.maxRounds.coerceIn(1, 50))
            .putBoolean(KEY_INFINITE_MODE, config.infiniteUntilQuotaEnd)
            .putInt(KEY_SINGLE_TIMEOUT, config.singleAdTimeoutSec.coerceIn(30, 90))
            .putInt(KEY_MAX_IDLE_ROUNDS, config.maxIdleRounds.coerceIn(3, 12))
            .putBoolean(KEY_AUTO_MUTE, config.autoMuteMedia)
            .apply()
    }

    fun resetToDefault(): RuleConfig {
        prefs.edit().clear().putInt(KEY_SCHEMA_VERSION, CURRENT_SCHEMA_VERSION).apply()
        return RuleConfig()
    }

    companion object {
        private const val PREFS_NAME = "qishui_rule_config"
        private const val CURRENT_SCHEMA_VERSION = 5
        private const val KEY_SCHEMA_VERSION = "schema_version"
        private const val KEY_TARGET_PACKAGE = "target_package"
        private const val KEY_MAX_ROUNDS = "max_rounds"
        private const val KEY_INFINITE_MODE = "infinite_until_quota_end"
        private const val KEY_SINGLE_TIMEOUT = "single_timeout_sec"
        private const val KEY_MAX_IDLE_ROUNDS = "max_idle_rounds"
        private const val KEY_AUTO_MUTE = "auto_mute_media"
    }
}
