/*
 * Copyright (c) 2016 - 2017 Carmen Alvarez
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

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Parcelable
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.FragmentPagerAdapter
import android.util.Log
import android.view.ViewGroup
import ca.rmen.android.poetassistant.Constants
import ca.rmen.android.poetassistant.R
import ca.rmen.android.poetassistant.main.dictionaries.ResultListFactory
import ca.rmen.android.poetassistant.main.dictionaries.ResultListFragment
import java.util.Locale

/**
 * A {@link FragmentPagerAdapter} that returns a fragment corresponding to
 * one of the sections/tabs/pages.
 *
 * Every page is a ResultListFragment and every Tab is always present, so the pager
 * position is simply the Tab ordinal. Upstream's "extra tab" machinery existed only to
 * show/hide the optional pattern and word-of-the-day tabs, both of which are gone.
 */
class PagerAdapter(context: Context, fm: FragmentManager, intent: Intent) : FragmentPagerAdapter(fm) {
    companion object {
        private val TAG = Constants.TAG + PagerAdapter::class.java.simpleName
    }

    private val mContext: Context = context
    private var mInitialRhymeQuery: String? = null
    private var mInitialThesaurusQuery: String? = null
    private var mInitialDictionaryQuery: String? = null

    override fun getItem(position: Int): Fragment {
        Log.v(TAG, "getItem $position")
        return when (val tab = getTabForPosition(position)) {
            Tab.RHYMER -> ResultListFactory.createListFragment(tab, mInitialRhymeQuery)
            Tab.THESAURUS -> ResultListFactory.createListFragment(tab, mInitialThesaurusQuery)
            Tab.DICTIONARY -> ResultListFactory.createListFragment(tab, mInitialDictionaryQuery)
            Tab.FAVORITES -> ResultListFactory.createListFragment(tab, null)
        }
    }

    override fun getItemPosition(obj: Any): Int {
        Log.v(TAG, "getItemPosition $obj")
        if (obj is ResultListFragment<*>) {
            val arguments = obj.arguments
            if (arguments != null) {
                val tab = arguments.getSerializable(ResultListFragment.EXTRA_TAB) as Tab
                return getPositionForTab(tab)
            }
        }
        return androidx.viewpager.widget.PagerAdapter.POSITION_NONE
    }

    override fun getCount(): Int = Tab.entries.size

    override fun getPageTitle(position: Int): CharSequence {
        val tab = getTabForPosition(position)
        return ResultListFactory.getTabName(mContext, tab).uppercase(Locale.getDefault())
    }

    fun getFragment(viewGroup: ViewGroup, tab: Tab): Fragment? {
        Log.v(TAG, "getFragment: tab=$tab")
        val position = getPositionForTab(tab)
        if (position < 0) return null
        // Not intuitive: instantiateItem will actually return an existing Fragment, whereas getItem() will always instantiate a new Fragment.
        // We want to retrieve the existing fragment.
        return instantiateItem(viewGroup, position) as Fragment
    }

    override fun getItemId(position: Int): Long = getTabForPosition(position).ordinal.toLong()

    fun getTabForPosition(position: Int): Tab = Tab.entries[position]

    fun getPositionForTab(tab: Tab): Int = tab.ordinal

    init {
        Log.v(TAG, "Constructor: intent = $intent")
        val initialQuery = intent.data
        if (initialQuery?.host != null) {
            val tab = Tab.parse(initialQuery.host!!)
            when {
                tab == Tab.RHYMER -> mInitialRhymeQuery = initialQuery.lastPathSegment
                tab == Tab.THESAURUS -> mInitialThesaurusQuery = initialQuery.lastPathSegment
                tab == Tab.DICTIONARY -> mInitialDictionaryQuery = initialQuery.lastPathSegment
                Constants.DEEP_LINK_QUERY == initialQuery.host -> {
                    mInitialRhymeQuery = initialQuery.lastPathSegment
                    mInitialThesaurusQuery = initialQuery.lastPathSegment
                    mInitialDictionaryQuery = initialQuery.lastPathSegment
                }
            }
        }
    }
}
