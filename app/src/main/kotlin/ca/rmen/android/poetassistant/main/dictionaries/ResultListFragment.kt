/*
 * Copyright (c) 2016-2018 Carmen Alvarez
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

package ca.rmen.android.poetassistant.main.dictionaries

import android.content.Context
import android.os.Bundle
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.util.Log
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import androidx.core.view.updateLayoutParams
import androidx.databinding.DataBindingUtil
import androidx.fragment.app.Fragment
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.flexbox.FlexboxLayoutManager
import com.google.android.flexbox.FlexWrap
import com.google.android.flexbox.JustifyContent
import ca.rmen.android.poetassistant.Constants
import ca.rmen.android.poetassistant.Favorite
import ca.rmen.android.poetassistant.R
import ca.rmen.android.poetassistant.compat.VectorCompat
import ca.rmen.android.poetassistant.databinding.BindingCallbackAdapter
import ca.rmen.android.poetassistant.databinding.FragmentResultListBinding
import ca.rmen.android.poetassistant.getInsets
import ca.rmen.android.poetassistant.main.AppBarLayoutHelper
import ca.rmen.android.poetassistant.main.Tab
import ca.rmen.android.poetassistant.main.dictionaries.rt.RTEntryViewModel
import ca.rmen.android.poetassistant.settings.SettingsPrefs

class ResultListFragment<out T: Any> : Fragment() {
    companion object {
        private val TAG = Constants.TAG + ResultListFragment::class.java.simpleName
        const val EXTRA_TAB = "tab"
        const val EXTRA_QUERY = "query"
    }

    private lateinit var mBinding: FragmentResultListBinding
    private lateinit var mViewModel: ResultListViewModel<T>
    private lateinit var mHeaderViewModel: ResultListHeaderViewModel

    private var mTab: Tab? = null

    // Fired once, on the next result after a query() call, so the caller can load the visible
    // tab first and the others only once it has results (see Search.search).
    private var mPendingOnLoaded: (() -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        Log.v(TAG, "onCreate")
        super.onCreate(savedInstanceState)
        setHasOptionsMenu(true)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        mTab = arguments?.getSerializable(EXTRA_TAB) as Tab
        mTab?.let {
            Log.v(TAG, "$mTab onCreateView")
            mBinding = DataBindingUtil.inflate(inflater, R.layout.fragment_result_list, container, false)
            // Rhymer / thesaurus / favorites flow their words as wrapping pills (Flexbox).
            // The dictionary is prose, so it stays a plain vertical list.
            mBinding.recyclerView.layoutManager = if (it == Tab.DICTIONARY) {
                LinearLayoutManager(activity, RecyclerView.VERTICAL, false)
            } else {
                FlexboxLayoutManager(activity).apply {
                    flexWrap = FlexWrap.WRAP
                    justifyContent = JustifyContent.FLEX_START
                }
            }
            // Flexbox item sizes vary, so we can't promise fixed sizes.
            mBinding.recyclerView.setHasFixedSize(it == Tab.DICTIONARY)
            getInsets(mBinding.recyclerView) { view, insets ->
                view.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                    leftMargin = insets.left
                    bottomMargin = insets.bottom
                    rightMargin = insets.right
                }
            }
            mBinding.recyclerView.id = ResultListFactory.getRecyclerViewId(it)
            @Suppress("UNCHECKED_CAST")
            mViewModel = ResultListFactory.createViewModel(it, this) as ResultListViewModel<T>
            mBinding.viewModel = mViewModel
            mViewModel.showHeader.observe(this, mShowHeaderChanged)
            mViewModel.usedQueryWord.observe(this, mUsedQueryWordChanged)
            mViewModel.emptyText.observe(this, mEmptyTextObserver)
            mViewModel.isDataAvailable.addOnPropertyChangedCallback(mDataAvailableChanged)
            mHeaderViewModel = ViewModelProvider(this).get(ResultListHeaderViewModel::class.java)
            var headerFragment = childFragmentManager.findFragmentById(R.id.result_list_header)
            if (headerFragment == null) {
                headerFragment = ResultListHeaderFragment.newInstance(it)
                childFragmentManager.beginTransaction().replace(R.id.result_list_header, headerFragment).commit()
            }
            mViewModel.favoritesLiveData.observe(this, mFavoritesObserver)
            mViewModel.resultListDataLiveData.observe(this, Observer { data ->
                mViewModel.setData(data)
                mPendingOnLoaded?.let { it(); mPendingOnLoaded = null }
            })
            return mBinding.root
        }
        return super.onCreateView(inflater, container, savedInstanceState)
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        super.onActivityCreated(savedInstanceState)
        Log.d(TAG, "$mTab: onActivityCreated: savedInstanceState=$savedInstanceState")
        activity?.let {
            val tab = mTab
            if (tab != null) {
                @Suppress("UNCHECKED_CAST")
                val adapter = ResultListFactory.createAdapter(it, tab) as ResultListAdapter<T>
                mViewModel.setAdapter(adapter)
                mBinding.recyclerView.adapter = adapter
            }
        }
    }

    override fun onStart() {
        Log.v(TAG, "$mTab : onStart")
        super.onStart()
        queryFromArguments()
        Log.v(TAG, "$mTab: onStart: invalidateOptionsMenu")
        activity?.invalidateOptionsMenu()
    }

    override fun onDestroyView() {
        Log.v(TAG, "$mTab onDestroyView")
        mViewModel.isDataAvailable.removeOnPropertyChangedCallback(mDataAvailableChanged)
        super.onDestroyView()
    }

    private fun queryFromArguments() {
        Log.v(TAG, "$mTab: queryFromArguments: $arguments")
        arguments?.let {
            if (it.containsKey(EXTRA_QUERY)) {
                mViewModel.setQueryParams(ResultListViewModel.QueryParams(it.getString(EXTRA_QUERY)))
            }
        }
    }

    fun query(query: String, onLoaded: (() -> Unit)? = null) {
        Log.d(TAG, "$mTab : query: $query")
        // Navigating to a word always resets the rhymer to perfect mode. The near toggle re-runs
        // through reload() rather than query(), so it isn't affected.
        if (mTab == Tab.RHYMER && ::mHeaderViewModel.isInitialized) {
            mHeaderViewModel.resetRhymeModeToPerfect()
        }
        if (userVisibleHint) {
            AppBarLayoutHelper.disableAutoHide(activity)
            AppBarLayoutHelper.forceExpandAppBarLayout(activity)
        }
        mPendingOnLoaded = onLoaded
        mViewModel.setQueryParams(ResultListViewModel.QueryParams(query))
        Log.v(TAG, "$mTab: query: invalidate options menu")
        activity?.invalidateOptionsMenu()
    }

    /**
     * Enable auto-hiding the toolbar only if not all items in the list are visible.
     */
    fun enableAutoHideIfNeeded() {
        Log.v(TAG, "$mTab: enableAutoHideIfNeeded")
        if (mTab != null && mBinding.recyclerView.adapter != null) {
            val lastVisibleItemPosition = when (val lm = mBinding.recyclerView.layoutManager) {
                is LinearLayoutManager -> lm.findLastVisibleItemPosition()
                is FlexboxLayoutManager -> lm.findLastVisibleItemPosition()
                else -> RecyclerView.NO_POSITION
            }
            @Suppress("UNCHECKED_CAST")
            val itemCount = (mBinding.recyclerView.adapter as ResultListAdapter<T>).itemCount
            Log.v(TAG, "$mTab: enableAutoHideIfNeeded: last visibleItem $lastVisibleItemPosition, item count $itemCount")
            if (itemCount > 0 && lastVisibleItemPosition < itemCount - 1) {
                AppBarLayoutHelper.enableAutoHide(activity)
            } else {
                AppBarLayoutHelper.disableAutoHide(activity)
            }
            AppBarLayoutHelper.forceExpandAppBarLayout(activity)
        }
    }

    private val mRecyclerViewLayoutListener = object : View.OnLayoutChangeListener {
        override fun onLayoutChange(view: View, left: Int, top: Int, right: Int, bottom: Int, oldLeft: Int, oldTop: Int, oldRight: Int, oldBottom: Int) {
            if (userVisibleHint) {
                enableAutoHideIfNeeded()
            }
            mBinding.recyclerView.removeOnLayoutChangeListener(this)
        }
    }

    private val mDataAvailableChanged = BindingCallbackAdapter(object : BindingCallbackAdapter.Callback {
        override fun onChanged() {
            mBinding.recyclerView.addOnLayoutChangeListener(mRecyclerViewLayoutListener)
            Log.v(TAG, "$mTab: dataAvailableChanged: invalidateOptionsMenu")
            activity?.invalidateOptionsMenu()

            // Hide the keyboard
            mBinding.recyclerView.requestFocus()
            (activity?.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager?)?.
                    hideSoftInputFromWindow(mBinding.recyclerView.windowToken, 0)
        }
    })

    private val mShowHeaderChanged = Observer<Boolean> { showHeader -> mHeaderViewModel.showHeader.set(showHeader == true) }

    // When favorites change:
    //  - On the Favorites tab, the whole list *is* the favorites, so it must reload to add/remove
    //    words.
    //  - On the lookup tabs (rhymer/thesaurus/dictionary) we only need to repaint the gold border,
    //    so we flip the affected pills' isFavorite flag in place. Reloading those would re-run the
    //    (slow) rhyme lookup and make the list fade out and back in on every favorite toggle.
    private val mFavoritesObserver = Observer<List<Favorite>> { favorites ->
        if (mTab == Tab.FAVORITES) {
            reload()
            return@Observer
        }
        val favoriteWords = HashSet<String>(favorites.size)
        favorites.forEach { favoriteWords.add(it.getWord()) }
        (mBinding.recyclerView.adapter as? ResultListAdapter<*>)?.getAll()?.forEach { item ->
            if (item is RTEntryViewModel && item.type == RTEntryViewModel.Type.WORD) {
                val shouldBeFavorite = favoriteWords.contains(item.text)
                // set() only notifies (and only triggers the save-back callback) on a real change,
                // so items already in sync are a no-op and there's no feedback loop.
                if (item.isFavorite.get() != shouldBeFavorite) item.isFavorite.set(shouldBeFavorite)
            }
        }
    }

    private val mUsedQueryWordChanged = Observer<String> { usedQueryWord ->
        mHeaderViewModel.query.set(usedQueryWord)
        mTab?.let {
            mHeaderViewModel.isMatchedWordSelectable.set(ResultListFactory.getMatchedWordSelectability(it, usedQueryWord))
        }
    }

    private val mEmptyTextObserver = Observer<EmptyText> { emptyText ->
        mBinding.empty.text = when (emptyText) {
            is EmptyTextNoQuery -> getNoQueryEmptyText()
            is EmptyTextNoResults -> getNoResultsEmptyText(emptyText.query)
            is EmptyTextHidden -> null
        }
    }

    private fun reload() {
        Log.v(TAG, "$mTab: reload: query=${mHeaderViewModel.query.get()}")
        mViewModel.setQueryParams(ResultListViewModel.QueryParams(mHeaderViewModel.query.get()))
    }

    /** Re-run the current query after the perfect/near rhyme mode was toggled in the header. */
    fun reloadForModeChange() = reload()

    // If we have an empty list because the user didn't enter any search term,
    // we'll show a text to tell them to search.
    private fun getNoQueryEmptyText(): CharSequence {
        val emptySearch = getString(R.string.empty_list_without_query)
        val imageSpan = VectorCompat.createVectorImageSpan(requireActivity(), R.drawable.ic_action_search_dark)
        val ssb = SpannableStringBuilder(emptySearch)
        val iconIndex = emptySearch.indexOf("%s")
        ssb.setSpan(imageSpan, iconIndex, iconIndex + 2, Spannable.SPAN_INCLUSIVE_INCLUSIVE)
        return ssb
    }

    // If the user entered a query and there are no matches, show the normal "no results" text.
    private fun getNoResultsEmptyText(query: String): CharSequence {
        return mTab?.let { ResultListFactory.getEmptyListText(requireContext(), it, query) } ?: ""
    }

}
