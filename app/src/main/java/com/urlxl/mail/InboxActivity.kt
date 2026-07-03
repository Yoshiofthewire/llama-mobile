package com.urlxl.mail

import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.tabs.TabLayout
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class InboxActivity : AppCompatActivity() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var keywordTabs: TabLayout
    private lateinit var bottomNav: BottomNavigationView
    private lateinit var loadingSpinner: ProgressBar
    private lateinit var inboxRoot: View
    private lateinit var inboxContent: View
    private lateinit var inboxTitle: TextView
    private lateinit var adapter: EmailAdapter
    private val ioExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var mailGateway: MailGateway
    private lateinit var keywordSettings: KeywordSettings
    private var currentFolder = "INBOX"
    private var lastAppliedThemeName: String = ""

    private var selectedTab = KeywordTabs.ALL
    private var allEmails: List<Email> = emptyList()

    private val refreshRunnable = object : Runnable {
        override fun run() {
            refreshInbox()
            scheduleNextRefresh()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_inbox)
        applyThemeToActivity(this)
        lastAppliedThemeName = getStoredThemeName(this)

        mailGateway = MailGateway.fromSettings(this)
        keywordSettings = KeywordSettings(this)

        initViews()
        applyTopInsetWithHeader(this, inboxContent)
        applyInboxThemeChrome()
        setupRecyclerView()
        setupTabs()
        setupBottomNav()
        setupSwipeGestures()
    }

    override fun onStart() {
        super.onStart()
        refreshInbox()
        scheduleNextRefresh()
    }

    override fun onResume() {
        super.onResume()

        val currentTheme = getStoredThemeName(this)
        if (currentTheme != lastAppliedThemeName) {
            recreate()
            return
        }

        applyThemeToActivity(this)
        applyInboxThemeChrome()
        adapter.notifyDataSetChanged()
        rebuildTabs(allEmails)
        renderFilteredEmails()
    }

    override fun onStop() {
        super.onStop()
        mainHandler.removeCallbacks(refreshRunnable)
    }

    override fun onDestroy() {
        super.onDestroy()
        ioExecutor.shutdownNow()
    }

    private fun initViews() {
        inboxRoot = findViewById(R.id.inboxRoot)
        inboxContent = findViewById(R.id.inboxContent)
        inboxTitle = findViewById(R.id.inboxTitle)
        recyclerView = findViewById(R.id.recyclerViewInbox)
        keywordTabs = findViewById(R.id.tabLayoutKeywords)
        bottomNav = findViewById(R.id.bottomNavigation)
        loadingSpinner = findViewById(R.id.loadingSpinner)
    }

    private fun applyInboxThemeChrome() {
        val palette = getStoredThemePalette(this)
        val bg = Color.parseColor(palette.bg)
        val panel = Color.parseColor(palette.panel)
        val ink = Color.parseColor(palette.ink)
        val inkStrong = Color.parseColor(palette.inkStrong)
        val accent = Color.parseColor(palette.accent)

        inboxRoot.setBackgroundColor(bg)
        inboxContent.setBackgroundColor(bg)
        inboxTitle.setTextColor(inkStrong)
        recyclerView.setBackgroundColor(bg)

        keywordTabs.setBackgroundColor(panel)
        keywordTabs.tabRippleColor = ColorStateList.valueOf(adjustAlpha(accent, 0.22f))
        keywordTabs.setTabTextColors(ink, inkStrong)
        keywordTabs.setSelectedTabIndicatorColor(accent)

        bottomNav.backgroundTintList = null
        bottomNav.setBackgroundColor(panel)
        val states = arrayOf(
            intArrayOf(android.R.attr.state_checked),
            intArrayOf()
        )
        val colors = intArrayOf(inkStrong, ink)
        val list = ColorStateList(states, colors)
        bottomNav.itemTextColor = list
        bottomNav.itemIconTintList = list
        bottomNav.itemRippleColor = ColorStateList.valueOf(adjustAlpha(accent, 0.20f))
        bottomNav.itemActiveIndicatorColor = ColorStateList.valueOf(adjustAlpha(accent, 0.30f))
    }

    private fun adjustAlpha(color: Int, factor: Float): Int {
        val alpha = (Color.alpha(color) * factor).toInt().coerceIn(0, 255)
        return Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))
    }

    private fun setupRecyclerView() {
        adapter = EmailAdapter(emptyList()) { email ->
            val intent = Intent(this, EmailDetailActivity::class.java)
            intent.putExtra("email_id", email.id)
            intent.putExtra("email_subject", email.subject)
            intent.putExtra("email_sender", email.sender)
            intent.putExtra("email_preview", email.preview)
            intent.putExtra("email_folder", currentFolder)
            startActivity(intent)
        }
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter
    }

    private fun setupTabs() {
        keywordTabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                selectedTab = tab.text?.toString().orEmpty().ifBlank { KeywordTabs.ALL }
                renderFilteredEmails()
            }

            override fun onTabUnselected(tab: TabLayout.Tab) = Unit
            override fun onTabReselected(tab: TabLayout.Tab) = Unit
        })

        rebuildTabs(emptyList())
    }

    private fun scheduleNextRefresh() {
        mainHandler.removeCallbacks(refreshRunnable)
        // ponytail: foreground best-effort cadence; upgrade path is server push + work resumption.
        mainHandler.postDelayed(refreshRunnable, REFRESH_INTERVAL_MS)
    }

    private fun refreshInbox() {
        loadingSpinner.visibility = android.view.View.VISIBLE
        ioExecutor.execute {
            try {
                val emails = mailGateway.fetchEmails(currentFolder)
                keywordSettings.rememberKeywords(emails.flatMap { it.keywords }.toSet())
                runOnUiThread {
                    loadingSpinner.visibility = android.view.View.GONE
                    allEmails = emails
                    rebuildTabs(emails)
                    renderFilteredEmails()
                }
            } catch (ex: Exception) {
                Log.e(TAG, "Failed to refresh inbox", ex)
                runOnUiThread {
                    loadingSpinner.visibility = android.view.View.GONE
                }
            }
        }
    }

    private fun rebuildTabs(emails: List<Email>) {
        val rawTabs = KeywordTabs.buildTabs(emails)
        val discoveredKeywords = rawTabs.drop(1).toSet()
        keywordSettings.rememberKeywords(discoveredKeywords)
        val visibleKeywords = keywordSettings.filterVisible(discoveredKeywords)
        val tabs = listOf(KeywordTabs.ALL) + rawTabs.drop(1).filter { visibleKeywords.contains(it) }

        val current = mutableListOf<String>()
        for (index in 0 until keywordTabs.tabCount) {
            current.add(keywordTabs.getTabAt(index)?.text?.toString().orEmpty())
        }
        if (tabs == current) {
            return
        }

        keywordTabs.removeAllTabs()
        tabs.forEach { keywordTabs.addTab(keywordTabs.newTab().setText(it)) }

        if (!tabs.contains(selectedTab)) {
            selectedTab = KeywordTabs.ALL
        }
        val selectedIndex = tabs.indexOf(selectedTab).coerceAtLeast(0)
        keywordTabs.getTabAt(selectedIndex)?.select()
    }

    private fun renderFilteredEmails() {
        val filtered = KeywordTabs.filterEmails(allEmails, selectedTab)
        adapter.updateEmails(filtered)
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menu?.add(0, MENU_KEYWORDS, 0, R.string.menu_keywords)
        menu?.add(0, MENU_SETTINGS, 1, R.string.menu_settings)
        menu?.add(0, MENU_THEMES, 2, R.string.menu_themes)
        return super.onCreateOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            MENU_KEYWORDS -> {
                startActivity(Intent(this, KeywordSettingsActivity::class.java))
                true
            }
            MENU_SETTINGS -> {
                startActivity(Intent(this, SettingsActivity::class.java))
                true
            }
            MENU_THEMES -> {
                startActivity(Intent(this, ThemesActivity::class.java))
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun setupBottomNav() {
        bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_compose -> {
                    startActivity(Intent(this, ComposeActivity::class.java))
                    true
                }
                R.id.nav_spam -> {
                    currentFolder = "Spam"
                    selectedTab = KeywordTabs.ALL
                    refreshInbox()
                    true
                }
                R.id.nav_trash -> {
                    currentFolder = "Trash"
                    selectedTab = KeywordTabs.ALL
                    refreshInbox()
                    true
                }
                R.id.nav_inbox -> {
                    currentFolder = "INBOX"
                    selectedTab = KeywordTabs.ALL
                    refreshInbox()
                    true
                }
                else -> false
            }
        }
        bottomNav.selectedItemId = R.id.nav_inbox
    }

    private fun setupSwipeGestures() {
        val itemTouchHelper = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
            0,
            ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT
        ) {
            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean = false

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                val position = viewHolder.adapterPosition
                if (position < 0 || position >= adapter.itemCount) return
                val email = adapter.getEmailAt(position)
                when (direction) {
                    ItemTouchHelper.LEFT -> {
                        ioExecutor.execute {
                            mailGateway.moveEmail(email, "[Gmail]/All Mail", currentFolder)
                            runOnUiThread {
                                allEmails = allEmails.filter { it.id != email.id }
                                renderFilteredEmails()
                            }
                        }
                    }
                    ItemTouchHelper.RIGHT -> {
                        ioExecutor.execute {
                            mailGateway.deleteEmail(email, currentFolder)
                            runOnUiThread {
                                allEmails = allEmails.filter { it.id != email.id }
                                renderFilteredEmails()
                            }
                        }
                    }
                }
            }
        })
        itemTouchHelper.attachToRecyclerView(recyclerView)
    }

    companion object {
        private const val TAG = "InboxActivity"
        private const val REFRESH_INTERVAL_MS = 90_000L
        private const val MENU_KEYWORDS = 0
        private const val MENU_SETTINGS = 1
        private const val MENU_THEMES = 2
    }
}