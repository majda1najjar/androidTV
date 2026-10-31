package com.xtreamtv.ui

import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.MotionEvent
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import com.xtreamtv.App
import com.xtreamtv.R
import com.xtreamtv.ui.live.LiveTvFragment
import com.xtreamtv.ui.series.SeriesFragment
import com.xtreamtv.ui.vod.MoviesFragment

/**
 * Three-tab dashboard: Live TV / Movies / TV Shows. Tabs sit in a top rail and
 * switch on OK press (not on focus — D-pad focus can land on any tab while
 * traversing, and switching content under the user is disorienting). Fragments
 * are kept alive with show/hide so tab switches don't refetch the catalog.
 */
class MainActivity : FragmentActivity() {

    private lateinit var tabs: List<TextView>
    private var currentIndex = -1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (!App.from(this).session.isLoggedIn()) {
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
            return
        }

        setContentView(R.layout.activity_main)

        tabs = listOf(
            findViewById(R.id.tab_live),
            findViewById(R.id.tab_movies),
            findViewById(R.id.tab_series)
        )
        tabs.forEachIndexed { index, tab ->
            tab.setOnClickListener { selectTab(index) }
        }

        val restoredIndex = savedInstanceState?.getInt(STATE_TAB, 0) ?: 0
        selectTab(restoredIndex)
        if (savedInstanceState == null) {
            tabs[restoredIndex].requestFocus()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(STATE_TAB, if (currentIndex >= 0) currentIndex else 0)
    }

    private fun selectTab(index: Int) {
        if (index == currentIndex) return
        currentIndex = index
        tabs.forEachIndexed { i, tab -> tab.isSelected = i == index }

        val fm = supportFragmentManager
        val transaction = fm.beginTransaction()
        FRAGMENT_TAGS.forEachIndexed { i, tag ->
            var fragment = fm.findFragmentByTag(tag)
            if (i == index) {
                if (fragment == null) {
                    fragment = createFragment(i)
                    transaction.add(R.id.content_frame, fragment, tag)
                }
                transaction.show(fragment)
            } else if (fragment != null) {
                transaction.hide(fragment)
            }
        }
        transaction.commit()
    }

    private fun createFragment(index: Int): Fragment = when (index) {
        0 -> LiveTvFragment()
        1 -> MoviesFragment()
        else -> SeriesFragment()
    }

    /** The Live tab hides the rail while its player is fullscreen. */
    fun setTabRailVisible(visible: Boolean) {
        findViewById<android.view.View>(R.id.tab_rail).visibility =
            if (visible) android.view.View.VISIBLE else android.view.View.GONE
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        liveFragment()?.onUserActivity()
        return super.dispatchKeyEvent(event)
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        liveFragment()?.onUserActivity()
        return super.dispatchTouchEvent(event)
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        liveFragment()?.onUserActivity()
        return super.dispatchGenericMotionEvent(event)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_PROG_YELLOW -> {
                val live = liveFragment()
                if (live != null && live.isVisible && live.handleYellowKey()) return true
            }
            KeyEvent.KEYCODE_PROG_GREEN -> {
                val live = liveFragment()
                if (live != null && live.isVisible && live.handleGreenKey()) return true
            }
            KeyEvent.KEYCODE_PROG_BLUE -> {
                // Blue jumps to the Favorites row of Live TV from anywhere.
                selectTab(0)
                // The add() transaction is async; without this, the first Blue
                // press from another tab after activity recreation finds no
                // fragment and the jump is silently dropped.
                supportFragmentManager.executePendingTransactions()
                liveFragment()?.jumpToFavorites()
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun liveFragment(): LiveTvFragment? =
        supportFragmentManager.findFragmentByTag(FRAGMENT_TAGS[0]) as? LiveTvFragment

    companion object {
        private const val STATE_TAB = "selected_tab"
        private val FRAGMENT_TAGS = listOf("tab_live", "tab_movies", "tab_series")
    }
}
