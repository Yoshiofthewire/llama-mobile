package com.urlxl.mail

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.ListView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

class ThemesActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_themes)
        setTitle(R.string.themes_title)
        applyThemeToActivity(this)

        val root = findViewById<View>(R.id.themesRoot)
        applyTopInsetWithHeader(this, root)

        val listView = findViewById<ListView>(R.id.themeList)
        val adapter = object : ArrayAdapter<String>(
            this,
            android.R.layout.simple_list_item_single_choice,
            THEME_OPTIONS,
        ) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val row = super.getView(position, convertView, parent)
                val palette = getStoredThemePalette(this@ThemesActivity)
                row.setBackgroundColor(android.graphics.Color.parseColor(palette.panel))
                (row as TextView).setTextColor(android.graphics.Color.parseColor(palette.inkStrong))
                return row
            }
        }
        listView.adapter = adapter
        listView.choiceMode = ListView.CHOICE_MODE_SINGLE

        val selectedTheme = getStoredThemeName(this)
        val selectedIndex = THEME_OPTIONS.indexOf(selectedTheme).coerceAtLeast(0)
        listView.setItemChecked(selectedIndex, true)

        listView.setOnItemClickListener { _, _, position, _ ->
            val chosen = THEME_OPTIONS[position]
            saveThemeName(this, chosen)
            applyThemeToActivity(this)
            (listView.adapter as ArrayAdapter<*>).notifyDataSetChanged()
        }
    }

    private fun actionBarSize(): Int {
        val typedArray = theme.obtainStyledAttributes(intArrayOf(android.R.attr.actionBarSize))
        return try {
            typedArray.getDimensionPixelSize(0, 0)
        } finally {
            typedArray.recycle()
        }
    }

    override fun onResume() {
        super.onResume()
        applyThemeToActivity(this)
    }
}
