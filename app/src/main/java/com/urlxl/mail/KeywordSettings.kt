package com.urlxl.mail

import android.content.Context
import android.content.SharedPreferences

class KeywordSettings(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getAllKeywords(): Set<String> = prefs.getStringSet(KEY_ALL_KEYWORDS, emptySet()) ?: emptySet()

    fun rememberKeywords(keywords: Set<String>) {
        if (keywords.isEmpty()) return
        val cleaned = keywords.map { it.trim() }.filter { it.isNotBlank() }.toSet()
        if (cleaned.isEmpty()) return
        val merged = getAllKeywords().toMutableSet().apply { addAll(cleaned) }
        prefs.edit().putStringSet(KEY_ALL_KEYWORDS, merged).apply()
    }

    fun isKeywordVisible(keyword: String): Boolean = prefs.getBoolean(keyForVisibility(keyword), true)

    fun setKeywordVisible(keyword: String, visible: Boolean) {
        prefs.edit().putBoolean(keyForVisibility(keyword), visible).apply()
    }

    fun filterVisible(keywords: Set<String>): Set<String> {
        return keywords.filter { isKeywordVisible(it) }.toSet()
    }

    private fun keyForVisibility(keyword: String): String = "keyword_visible_$keyword"

    companion object {
        private const val PREFS_NAME = "com.urlxl.mail.keyword_settings"
        private const val KEY_ALL_KEYWORDS = "all_keywords"
    }
}
