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

package ca.rmen.android.poetassistant.main.dictionaries.search

import android.app.Activity
import android.util.Log
import androidx.viewpager.widget.ViewPager
import ca.rmen.android.poetassistant.Constants
import ca.rmen.android.poetassistant.R
import ca.rmen.android.poetassistant.Threading
import ca.rmen.android.poetassistant.main.PagerAdapter
import ca.rmen.android.poetassistant.main.Tab
import ca.rmen.android.poetassistant.main.dictionaries.ResultListFragment
import ca.rmen.android.poetassistant.main.dictionaries.dictionary.Dictionary
import ca.rmen.android.poetassistant.widget.ViewShownScheduler
import com.google.android.material.search.SearchView
import java.util.Locale

/**
 * Glue between the fragments, activity, and view pager, for executing searches.
 * <p/>
 * The activity calls this class to perform searches.  This class retrieves the fragments from
 * the Viewpager, and calls the fragments (which call the adapters) to perform the search and
 * display the search results.
 */
class Search constructor(
    private val searchableActivity: Activity,
    private val viewPager: ViewPager,
    private val dictionary: Dictionary,
    private val threading: Threading,
    ) {
    companion object {
        private val TAG = Constants.TAG + Search::class.java.simpleName
        private val LOOKUP_TABS = listOf(Tab.RHYMER, Tab.THESAURUS, Tab.DICTIONARY)
    }

    private val mPagerAdapter: PagerAdapter

    // Bumped on every search so a stale "now load the other tabs" callback from a previous,
    // superseded search does nothing (the word changed under it).
    private var mSearchGeneration = 0

    init {
        mPagerAdapter = viewPager.adapter as PagerAdapter
    }

    /**
     * @param onSearch invoked with the entered term when the user submits a search. The caller
     * (MainActivity) performs the navigation so it can record it in the back-stack.
     */
    fun setSearchView(searchView: SearchView, onSearch: (String) -> Unit) {
        searchView.hint =
            searchableActivity.getString(R.string.search_hint) // To hopefully prevent some crashes (!!) :(
        // Handle when the user taps enter from the search widget
        searchView.editText.setOnEditorActionListener { _, _, _ ->
            val searchTerm = searchView.editText.text.toString()
            searchView.hide()
            if (searchTerm.isNotBlank()) onSearch(searchTerm)
            false
        }
    }

    /**
     * Search for the given word in all lookup tools. [priorityTab] is the tool the user is about
     * to see: we load it first and, once it has results, load the others - so the visible tab isn't
     * slowed by the other two competing for the single database connection, while swiping between
     * tools stays instant. MainActivity picks the tool to show and records the move.
     */
    fun search(word: String, priorityTab: Tab) {
        Log.d(TAG, "search called with $word, priority=$priorityTab")
        val wordTrimmed = word.trim().lowercase(Locale.US)
        val generation = ++mSearchGeneration

        selectTabForSearch()
        ViewShownScheduler.runWhenShown(viewPager) {
            if (generation != mSearchGeneration) return@runWhenShown
            // priorityTab first, then the remaining lookup tabs (a non-lookup tab like favorites
            // just falls back to the natural order, rhymer first).
            val ordered = (listOf(priorityTab) + LOOKUP_TABS).distinct().filter { it in LOOKUP_TABS }
            val first = fragmentFor(ordered.first())
            val rest = ordered.drop(1)
            if (first == null) {
                rest.forEach { fragmentFor(it)?.query(wordTrimmed) }
                return@runWhenShown
            }
            first.query(wordTrimmed) {
                // Only fan out to the other tabs if this is still the current search.
                if (generation == mSearchGeneration) {
                    rest.forEach { fragmentFor(it)?.query(wordTrimmed) }
                }
            }
        }
    }

    private fun fragmentFor(tab: Tab): ResultListFragment<*>? =
        mPagerAdapter.getFragment(viewPager, tab) as ResultListFragment<*>?

    /**
     * Stay in the current lookup mode if we're already in one, so that searching a new word
     * keeps the lens the user chose. Only jump to the rhymer if we're somewhere that has no
     * query of its own (the favorites list).
     */
    private fun selectTabForSearch() {
        val currentTab = mPagerAdapter.getTabForPosition(viewPager.currentItem)
        if (currentTab != Tab.RHYMER && currentTab != Tab.THESAURUS && currentTab != Tab.DICTIONARY) {
            viewPager.setCurrentItem(mPagerAdapter.getPositionForTab(Tab.RHYMER), false)
        }
    }

    /**
     * Pick a random word in the background and hand it back to [onWord] on the main thread. The
     * caller navigates to it (in the dictionary), so the move is recorded in the back-stack.
     */
    fun lookupRandom(onWord: (String) -> Unit) {
        Log.d(TAG, "lookupRandom")
        threading.execute(
                { dictionary.getRandomEntry() },
                { entry -> entry?.let { onWord(it.word) } }
        )
    }
}
