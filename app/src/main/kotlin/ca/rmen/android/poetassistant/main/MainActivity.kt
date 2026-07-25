/*
 * Copyright (c) 2016-2017 Carmen Alvarez
 *
 * This file is part of Poet Assistant.
 *
 * Poet Assistant is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Poet Assistant is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Poet Assistant.  If not, see <http://www.gnu.org/licenses/>.
 */

package ca.rmen.android.poetassistant.main

import android.app.ActivityManager
import android.app.SearchManager
import android.content.Context
import android.content.Intent
import androidx.databinding.DataBindingUtil
import android.media.AudioManager
import android.net.Uri
import android.os.Bundle
import androidx.annotation.MainThread
import androidx.annotation.WorkerThread
import androidx.viewpager.widget.ViewPager
import androidx.appcompat.app.AppCompatActivity
import android.text.TextUtils
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.view.Window
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import androidx.activity.OnBackPressedCallback
import androidx.core.view.updatePadding
import androidx.lifecycle.ViewModelProvider
import ca.rmen.android.poetassistant.BuildConfig
import ca.rmen.android.poetassistant.Constants
import ca.rmen.android.poetassistant.Favorites
import ca.rmen.android.poetassistant.FontScale
import ca.rmen.android.poetassistant.R
import ca.rmen.android.poetassistant.Threading
import ca.rmen.android.poetassistant.about.AboutActivity
import ca.rmen.android.poetassistant.databinding.ActivityMainBinding
import ca.rmen.android.poetassistant.getInsets
import ca.rmen.android.poetassistant.main.dictionaries.ResultListFragment
import ca.rmen.android.poetassistant.main.dictionaries.dictionary.Dictionary
import ca.rmen.android.poetassistant.main.dictionaries.rt.OnWordClickListener
import ca.rmen.android.poetassistant.main.dictionaries.rt.Rhymer
import ca.rmen.android.poetassistant.main.dictionaries.rt.Thesaurus
import ca.rmen.android.poetassistant.main.dictionaries.search.Search
import ca.rmen.android.poetassistant.settings.SettingsActivity
import ca.rmen.android.poetassistant.settings.SettingsPrefs
import ca.rmen.android.poetassistant.widget.CABEditText
import dagger.hilt.android.AndroidEntryPoint
import java.io.Serializable
import java.util.Locale
import javax.inject.Inject

// Split into separate impl and base class to get full code coverage stats:
// https://medium.com/livefront/dagger-hilt-testing-injected-android-components-with-code-coverage-30089a1f6872

@AndroidEntryPoint
class MainActivity : MainActivityImpl()

open class MainActivityImpl : AppCompatActivity(), OnWordClickListener, WarningNoSpaceDialogFragment.WarningNoSpaceDialogListener, CABEditText.ImeListener {
    companion object {
        private val TAG = Constants.TAG + MainActivity::class.java.simpleName
        private const val DIALOG_TAG = "dialog"
        private const val STATE_HISTORY = "nav_history"
        private const val STATE_CURRENT = "nav_current"
    }

    /** One step of navigation history: a word viewed in a particular tool (tab). */
    private data class NavState(val word: String, val tab: Tab) : Serializable

    private lateinit var mSearch: Search
    private lateinit var mBinding: ActivityMainBinding
    private lateinit var mPagerAdapter: PagerAdapter
    @Inject lateinit var mPrefs: SettingsPrefs
    @Inject lateinit var mRhymer: Rhymer
    @Inject lateinit var mThesaurus: Thesaurus
    @Inject lateinit var mDictionary: Dictionary
    @Inject lateinit var mFavorites: Favorites
    @Inject lateinit var mThreading: Threading

    // Back navigation walks through the (word, tool) pairs the user actually looked at, so
    // e.g. "slob"/dictionary -> "slob"/rhymer -> "bob"/rhymer -> "blob"/rhymer -> "blob"/thesaurus.
    // mCurrent is the state on screen now; mHistory holds the earlier states, newest last.
    private val mHistory = ArrayDeque<NavState>()
    private var mCurrent: NavState? = null
    // Set while we drive the pager/search ourselves (a tap, a Back, a restore) so those changes
    // aren't mistaken for user navigation and recorded again.
    private var mSuppressHistory = false
    private lateinit var mBackCallback: OnBackPressedCallback

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(FontScale.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        Log.d(TAG, "onCreate: savedInstanceState = $savedInstanceState")
        if (BuildConfig.DEBUG && ActivityManager.isUserAMonkey()) {
            requestWindowFeature(Window.FEATURE_NO_TITLE)
            window.setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN)
        }
        super.onCreate(savedInstanceState)
        mBinding = DataBindingUtil.setContentView(this, R.layout.activity_main)
        setSupportActionBar(mBinding.toolbar)
        mPagerAdapter = PagerAdapter(this, supportFragmentManager, intent)

        // Set up the ViewPager with the sections adapter. The mode indicator is the
        // PagerTitleStrip inside the pager (see activity_main.xml) - there is no TabLayout.
        mBinding.viewPager.adapter = mPagerAdapter
        mBinding.viewPager.offscreenPageLimit = 5
        mBinding.viewPager.addOnPageChangeListener(mOnPageChangeListener)

        val savedTab = SettingsPrefs.getTab(mPrefs)
        if (savedTab != null && savedTab.ordinal < mPagerAdapter.count) {
            mBinding.viewPager.currentItem = savedTab.ordinal
        }

        // Back walks through the (word, tool) history; when it's empty, Back falls through to exit.
        mBackCallback = object : OnBackPressedCallback(false) {
            override fun handleOnBackPressed() = goBack()
        }
        onBackPressedDispatcher.addCallback(this, mBackCallback)
        restoreHistory(savedInstanceState)

        // If the app was launched with a query for the a particular tab, focus on that tab.
        if (intent.data?.host != null) {
            val tab = Tab.parse(intent.data!!.host!!) ?: Tab.DICTIONARY
            mBinding.viewPager.setCurrentItem(mPagerAdapter.getPositionForTab(tab), false)
            // Seed the current state so a later swipe is recorded relative to the launch word.
            if (mCurrent == null) {
                intent.data?.lastPathSegment?.let { mCurrent = NavState(it.trim().lowercase(Locale.US), tab) }
            }
        }

        mSearch = Search(this, mBinding.viewPager, dictionary = mDictionary, threading = mThreading)
        // Load our dictionaries when the activity starts, so that the first search can already be fast.
        mThreading.execute({ loadDatabase() },
                {
                    onDatabaseLoadResult(it)
                    if (Intent.ACTION_SEARCH == intent.action) {
                        handleSearchIntent(intent)
                    }
                })
        volumeControlStream = AudioManager.STREAM_MUSIC
        getInsets(mBinding.toolbar) { view, insets ->
            view.updatePadding(
                left = insets.left,
                right = insets.right,
            )
        }
        getInsets(mBinding.appBarLayout) { view, insets ->
            view.updatePadding(
                left = insets.left,
                right = insets.right,
                top = insets.top,
            )
        }
        mSearch.setSearchView(mBinding.searchView) { navigateToWord(it) }
    }

    @Suppress("UNCHECKED_CAST")
    private fun restoreHistory(savedInstanceState: Bundle?) {
        savedInstanceState ?: return
        (savedInstanceState.getSerializable(STATE_HISTORY) as? ArrayList<NavState>)?.let {
            mHistory.addAll(it)
        }
        mCurrent = savedInstanceState.getSerializable(STATE_CURRENT) as? NavState
        mBackCallback.isEnabled = mHistory.isNotEmpty()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putSerializable(STATE_HISTORY, ArrayList(mHistory))
        outState.putSerializable(STATE_CURRENT, mCurrent)
    }

    override fun onResume() {
        super.onResume()
        Log.v(TAG, "onResume")
        // Weird bug that I don't understand :(
        // Open about (or settings) and come back to the main activity:
        // the AppBarLayout is hidden (even if it wasn't hidden before).
        // We'll force it to be shown again here.
        AppBarLayoutHelper.forceExpandAppBarLayout(mBinding.appBarLayout)
    }

    override fun onPause() {
        Log.v(TAG, "onPause")
        super.onPause()
    }

    @WorkerThread
    private fun loadDatabase(): Boolean {
        return mRhymer.isLoaded() && mThesaurus.isLoaded() && mDictionary.isLoaded()
    }

    @MainThread
    private fun onDatabaseLoadResult(databaseIsLoaded: Boolean) {
        val warningNoSpaceDialogFragment = supportFragmentManager.findFragmentByTag(DIALOG_TAG)
        if (!databaseIsLoaded && warningNoSpaceDialogFragment == null) {
            supportFragmentManager.beginTransaction().add(WarningNoSpaceDialogFragment(), DIALOG_TAG).commit()
        } else if (databaseIsLoaded && warningNoSpaceDialogFragment != null) {
            supportFragmentManager.beginTransaction().remove(warningNoSpaceDialogFragment).commit()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        Log.d(TAG, "onNewIntent: intent=$intent")
        setIntent(intent)
        when (intent.action) {
        // The user entered a search term either by typing or by voice
            Intent.ACTION_SEARCH -> {
                handleSearchIntent(intent)
            }
        // We got here from a deep link
            Intent.ACTION_VIEW -> {
                handleDeepLink(intent.data)
            }
        }
    }

    private fun handleSearchIntent(intent: Intent) {
        var query = intent.dataString
        if (TextUtils.isEmpty(query)) {
            query = intent.getStringExtra(SearchManager.QUERY)
        }
        if (TextUtils.isEmpty(query)) {
            val userQuery = intent.getCharSequenceExtra(SearchManager.USER_QUERY)
            if (!userQuery.isNullOrEmpty()) query = userQuery.toString()
        }
        if (TextUtils.isEmpty(query)) return
        navigateToWord(query!!)
    }
    private fun handleDeepLink(uri: Uri?) {
        Log.d(TAG, "handleDeepLink, uri=$uri")
        if (uri == null) return
        val word = uri.lastPathSegment ?: return
        if (Constants.DEEP_LINK_QUERY == uri.host) {
            navigateTo(word, Tab.DICTIONARY)
        } else if (uri.host != null) {
            Tab.parse(uri.host!!)?.let { navigateTo(word, it) }
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        Log.d(TAG, "onCreateOptionsMenu, menu=$menu")
        menuInflater.inflate(R.menu.menu_main, menu)
        return super.onCreateOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_about -> {
                startActivity(Intent(this, AboutActivity::class.java))
                return true
            }
            R.id.action_random_word -> {
                mSearch.lookupRandom { navigateTo(it, Tab.DICTIONARY) }
                return true
            }
            R.id.action_settings -> {
                startActivity(Intent(this, SettingsActivity::class.java))
                return true
            }
        }
        return super.onOptionsItemSelected(item)
    }

    override fun onImeClosed() {
        // When the user taps back to close the soft keyboard, show the AppBarLayout again, or else
        // the only way to reach the search bar would be to swipe left or right to another fragment.
        AppBarLayoutHelper.forceExpandAppBarLayout(mBinding.appBarLayout)
    }

    override fun onWordClick(word: String, tab: Tab) {
        Log.v(TAG, "onWordClick: word=$word, tab=$tab")
        navigateTo(word, tab)
    }

    /**
     * Show [word] in [tab]. Every lookup tab is loaded with the word (so swiping between tools
     * always shows the current word), and [tab] is brought to the front. Records a history step.
     */
    private fun navigateTo(word: String, tab: Tab) {
        val trimmed = word.trim().lowercase(Locale.US)
        if (trimmed.isEmpty()) return
        mSuppressHistory = true
        mSearch.search(trimmed, priorityTab = tab)
        mBinding.viewPager.setCurrentItem(mPagerAdapter.getPositionForTab(tab), false)
        mSuppressHistory = false
        pushHistory(NavState(trimmed, tab))
    }

    /** Show [word], staying in whatever tool the search lands on. Records a history step. */
    private fun navigateToWord(word: String) {
        val trimmed = word.trim().lowercase(Locale.US)
        if (trimmed.isEmpty()) return
        mSuppressHistory = true
        mSearch.search(trimmed, priorityTab = currentTab())
        mSuppressHistory = false
        pushHistory(NavState(trimmed, currentTab()))
    }

    private fun currentTab(): Tab = mPagerAdapter.getTabForPosition(mBinding.viewPager.currentItem)

    /** Record a move to [next], unless we're driving navigation ourselves or nothing changed. */
    private fun pushHistory(next: NavState) {
        if (mSuppressHistory || next == mCurrent) return
        mCurrent?.let { mHistory.addLast(it) }
        mCurrent = next
        mBackCallback.isEnabled = mHistory.isNotEmpty()
    }

    /** Restore the previous (word, tool) without recording it. */
    private fun goBack() {
        val prev = mHistory.removeLastOrNull()
        if (prev == null) {
            mBackCallback.isEnabled = false
            return
        }
        mSuppressHistory = true
        mSearch.search(prev.word, priorityTab = prev.tab)
        mBinding.viewPager.setCurrentItem(mPagerAdapter.getPositionForTab(prev.tab), false)
        mSuppressHistory = false
        mCurrent = prev
        mBackCallback.isEnabled = mHistory.isNotEmpty()
    }

    override fun onWarningNoSpaceDialogDismissed() {
        Log.v(TAG, "onWarningNoSpaceDialogDismissed")
        finish()
    }

    private val mOnPageChangeListener = object : ViewPager.SimpleOnPageChangeListener() {
        override fun onPageSelected(position: Int) {
            super.onPageSelected(position)
            val tab = mPagerAdapter.getTabForPosition(position)

            // Hide the keyboard whenever we swipe to another lookup mode.
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager?
            imm?.hideSoftInputFromWindow(mBinding.viewPager.windowToken, 0)

            val fragment = mPagerAdapter.getFragment(mBinding.viewPager, tab)
            (fragment as? ResultListFragment<*>)?.enableAutoHideIfNeeded()

            AppBarLayoutHelper.forceExpandAppBarLayout(mBinding.appBarLayout)
            mPrefs.tab = tab.name

            // A user swipe to another tool, keeping the current word, is its own history step.
            mCurrent?.word?.let { word -> pushHistory(NavState(word, tab)) }
        }
    }
}
