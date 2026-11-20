package io.github.jiaqi.qishuihelper.config

import android.content.Context
import io.github.jiaqi.qishuihelper.model.RuleConfig

class RuleConfigRepository(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    init {
        migrateIfNeeded()
    }

    private fun migrateIfNeeded() {
        val savedVersion = prefs.getInt(KEY_SCHEMA_VERSION, 1)
        if (savedVersion < CURRENT_SCHEMA_VERSION) {
            // 升级到 v3 极速连看规则集：补齐「提前领」、开启广告内无缝连看与极速响应
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
            maxRounds = prefs.getInt(KEY_MAX_ROUNDS, defaults.maxRounds).coerceIn(1, 30),
            singleAdTimeoutSec = prefs.getInt(KEY_SINGLE_TIMEOUT, defaults.singleAdTimeoutSec).coerceIn(45, 120),
            autoMuteMedia = prefs.getBoolean(KEY_AUTO_MUTE, defaults.autoMuteMedia),
            fastMode = prefs.getBoolean(KEY_FAST_MODE, defaults.fastMode),
            stopOnTomorrow = prefs.getBoolean(KEY_STOP_ON_TOMORROW, defaults.stopOnTomorrow),
            entryKeywords = parsePipeList(prefs.getString(KEY_ENTRY_KEYWORDS, null), defaults.entryKeywords),
            unlockedKeywords = parsePipeList(prefs.getString(KEY_UNLOCKED_KEYWORDS, null), defaults.unlockedKeywords),
            rewardDoneKeywords = parsePipeList(prefs.getString(KEY_REWARD_DONE_KEYWORDS, null), defaults.rewardDoneKeywords),
            nextAdKeywords = parsePipeList(prefs.getString(KEY_NEXT_AD_KEYWORDS, null), defaults.nextAdKeywords),
            noVideoKeywords = parsePipeList(prefs.getString(KEY_NO_VIDEO_KEYWORDS, null), defaults.noVideoKeywords)
        )
    }

    fun save(config: RuleConfig) {
        prefs.edit()
            .putInt(KEY_SCHEMA_VERSION, CURRENT_SCHEMA_VERSION)
            .putString(KEY_TARGET_PACKAGE, config.targetPackage.trim().ifEmpty { RuleConfig.DEFAULT_TARGET_PACKAGE })
            .putInt(KEY_MAX_ROUNDS, config.maxRounds.coerceIn(1, 30))
            .putInt(KEY_SINGLE_TIMEOUT, config.singleAdTimeoutSec.coerceIn(45, 120))
            .putBoolean(KEY_AUTO_MUTE, config.autoMuteMedia)
            .putBoolean(KEY_FAST_MODE, config.fastMode)
            .putBoolean(KEY_STOP_ON_TOMORROW, config.stopOnTomorrow)
            .putString(KEY_ENTRY_KEYWORDS, joinPipeList(config.entryKeywords))
            .putString(KEY_UNLOCKED_KEYWORDS, joinPipeList(config.unlockedKeywords))
            .putString(KEY_REWARD_DONE_KEYWORDS, joinPipeList(config.rewardDoneKeywords))
            .putString(KEY_NEXT_AD_KEYWORDS, joinPipeList(config.nextAdKeywords))
            .putString(KEY_NO_VIDEO_KEYWORDS, joinPipeList(config.noVideoKeywords))
            .apply()
    }

    fun resetToDefault(): RuleConfig {
        prefs.edit().clear().putInt(KEY_SCHEMA_VERSION, CURRENT_SCHEMA_VERSION).apply()
        return RuleConfig()
    }

    companion object {
        private const val PREFS_NAME = "qishui_rule_config"
        private const val CURRENT_SCHEMA_VERSION = 4
        private const val KEY_SCHEMA_VERSION = "schema_version"
        private const val KEY_TARGET_PACKAGE = "target_package"
        private const val KEY_MAX_ROUNDS = "max_rounds"
        private const val KEY_SINGLE_TIMEOUT = "single_timeout_sec"
        private const val KEY_AUTO_MUTE = "auto_mute_media"
        private const val KEY_FAST_MODE = "fast_mode"
        private const val KEY_STOP_ON_TOMORROW = "stop_on_tomorrow"
        private const val KEY_ENTRY_KEYWORDS = "entry_keywords"
        private const val KEY_UNLOCKED_KEYWORDS = "unlocked_keywords"
        private const val KEY_REWARD_DONE_KEYWORDS = "reward_done_keywords"
        private const val KEY_NEXT_AD_KEYWORDS = "next_ad_keywords"
        private const val KEY_NO_VIDEO_KEYWORDS = "no_video_keywords"

        fun joinPipeList(items: List<String>): String {
            return items.map { it.trim() }.filter { it.isNotEmpty() }.joinToString("|")
        }

        fun parsePipeInput(raw: String): List<String> {
            return raw.split("|", "｜")
                .map { it.trim() }
                .filter { it.isNotEmpty() }
        }

        private fun parsePipeList(saved: String?, fallback: List<String>): List<String> {
            if (saved.isNullOrBlank()) return fallback
            val list = parsePipeInput(saved)
            return list.ifEmpty { fallback }
        }
    }
}
