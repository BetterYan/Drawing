package com.zucky.drawing

import android.content.Context
import android.content.SharedPreferences
import android.os.LocaleList
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import java.util.Locale

/**
 * 应用内语言切换工具类。
 *
 * 使用 AppCompatDelegate.setApplicationLocales() 实现语言切换，
 * 这样可以自动处理 Activity 重建，无需手动调用 recreate()。
 *
 * 注意：AppCompat 1.6+ 的 autoStoreLocales 会自动持久化语言设置，
 * 但需要在 AndroidManifest 的 Activity 上配置 android:configChanges。
 * 这里我们用 SharedPreferences 手动持久化，并在 Application/Activity
 * 启动时恢复。
 */
object LocaleHelper {

    private const val PREFS_NAME = "app_locale_prefs"
    private const val KEY_LOCALE = "selected_locale"

    /** 支持的语言列表（locale tag → 显示名） */
    val SUPPORTED_LOCALES = listOf(
        "" to "🌐 System",           // 跟随系统
        "en" to "🇬🇧 English",
        "zh" to "🇨🇳 中文",
        "ja" to "🇯🇵 日本語",
        "ko" to "🇰🇷 한국어",
        "fr" to "🇫🇷 Français",
        "de" to "🇩🇪 Deutsch",
        "es" to "🇪🇸 Español",
        "it" to "🇮🇹 Italiano",
        "pt" to "🇧🇷 Português",
        "ru" to "🇷🇺 Русский",
        "fi" to "🇫🇮 Suomi"
    )

    /** 获取当前选择的语言 tag，空字符串表示跟随系统 */
    fun getSavedLocaleTag(context: Context): String {
        val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_LOCALE, "") ?: ""
    }

    /** 保存语言设置并应用 */
    fun applyLocale(context: Context, localeTag: String) {
        // 持久化
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_LOCALE, localeTag)
            .apply()

        // 使用 AppCompatDelegate 设置语言（会自动重建 Activity）
        val localeList = if (localeTag.isEmpty()) {
            LocaleListCompat.getEmptyLocaleList()
        } else {
            LocaleListCompat.create(Locale.forLanguageTag(localeTag))
        }
        AppCompatDelegate.setApplicationLocales(localeList)
    }

    /** 在 Application 或 Activity 启动时恢复保存的语言 */
    fun restoreLocale(context: Context) {
        val tag = getSavedLocaleTag(context)
        val current = AppCompatDelegate.getApplicationLocales()
        val currentTag = if (current.isEmpty) "" else current[0]?.toLanguageTag() ?: ""

        // 只有不一致时才设置，避免不必要的 Activity 重建
        if (currentTag != tag && !(tag.isEmpty() && current.isEmpty)) {
            val localeList = if (tag.isEmpty()) {
                LocaleListCompat.getEmptyLocaleList()
            } else {
                LocaleListCompat.create(Locale.forLanguageTag(tag))
            }
            AppCompatDelegate.setApplicationLocales(localeList)
        }
    }

    /** 获取当前语言的显示名 */
    fun getCurrentLocaleDisplayName(context: Context): String {
        val tag = getSavedLocaleTag(context)
        return SUPPORTED_LOCALES.find { it.first == tag }?.second ?: SUPPORTED_LOCALES.first().second
    }
}
