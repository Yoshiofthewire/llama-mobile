package com.urlxl.mail

import android.os.Bundle
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

class KeywordSettingsActivity : AppCompatActivity() {

    private lateinit var keywordSettings: KeywordSettings

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        keywordSettings = KeywordSettings(this)
        setTitle(R.string.keyword_settings_title)

        val scrollView = ScrollView(this)
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
        }

        applyTopInsetWithHeader(this, scrollView)

        val intro = TextView(this).apply {
            text = getString(R.string.keyword_settings_intro)
            textSize = 14f
            setPadding(0, 0, 0, 16)
        }
        container.addView(intro)

        val allKeywords = keywordSettings.getAllKeywords().sorted()
        if (allKeywords.isEmpty()) {
            val emptyView = TextView(this).apply {
                text = getString(R.string.keyword_settings_empty)
                textSize = 14f
            }
            container.addView(emptyView)
        } else {
            allKeywords.forEach { keyword ->
                val checkbox = CheckBox(this).apply {
                    text = keyword
                    isChecked = keywordSettings.isKeywordVisible(keyword)
                    textSize = 15f
                    setOnCheckedChangeListener { _, isChecked ->
                        keywordSettings.setKeywordVisible(keyword, isChecked)
                    }
                }
                container.addView(checkbox)
            }
        }

        scrollView.addView(container)
        setContentView(scrollView)
        applyThemeToActivity(this)
    }
}
