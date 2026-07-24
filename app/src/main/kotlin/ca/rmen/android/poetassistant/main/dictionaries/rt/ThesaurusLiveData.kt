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

package ca.rmen.android.poetassistant.main.dictionaries.rt

import android.content.Context
import android.text.TextUtils
import android.util.Log
import androidx.annotation.StringRes
import ca.rmen.android.poetassistant.Constants
import ca.rmen.android.poetassistant.Favorites
import ca.rmen.android.poetassistant.R
import ca.rmen.android.poetassistant.di.NonAndroidEntryPoint
import ca.rmen.android.poetassistant.main.dictionaries.ResultListData
import ca.rmen.android.poetassistant.main.dictionaries.ResultListLiveData
import dagger.hilt.android.EntryPointAccessors
import java.util.Locale

class ThesaurusLiveData constructor(context: Context, private val query: String) : ResultListLiveData<ResultListData<RTEntryViewModel>>(context) {
    companion object {
        private val TAG = Constants.TAG + ThesaurusLiveData::class.java.simpleName
    }

    private val mThesaurus: Thesaurus
    private val mFavorites: Favorites

    init {
        val entryPoint = EntryPointAccessors.fromApplication(context.applicationContext, NonAndroidEntryPoint::class.java)
        mThesaurus = entryPoint.thesaurus()
        mFavorites = entryPoint.favorites()
    }

    override fun loadInBackground(): ResultListData<RTEntryViewModel> {
        Log.d(TAG, "loadInBackground: query=$query")

        if (TextUtils.isEmpty(query)) return emptyResult()
        val favorites = mFavorites.getFavorites()

        // Phase 1: the word's own synonyms/antonyms (an indexed "word=?" lookup). Publish it
        // immediately so results appear right away.
        val forward = mThesaurus.lookup(query, false)
        val forwardData = buildEntries(forward, favorites)
        if (forwardData.isNotEmpty()) publishProgress(ResultListData(forward.word, forwardData))

        // If the user has already moved to another word, skip the reverse lookup - nobody would
        // see its result.
        if (isCanceled) return ResultListData(forward.word, forwardData)

        // Phase 2: reverse lookup is always on now (it used to be a setting): also surface words
        // that list this word as one of their synonyms. It reads the precomputed thesaurus_reverse
        // index (see tools/rhymedb/build_db.py), so it's fast; its results get injected into the
        // already-visible list. The forward block is a prefix of this full result, so the diff
        // keeps the fast chips in place and just inserts the reverse ones.
        val full = mThesaurus.lookup(query, true)
        val fullData = buildEntries(full, favorites)
        if (fullData.isEmpty()) return emptyResult()
        return ResultListData(full.word, fullData)
    }

    private fun buildEntries(result: ThesaurusEntry, favorites: Set<String>): List<RTEntryViewModel> {
        val data = ArrayList<RTEntryViewModel>()
        result.entries.forEach {
            data.add(RTEntryViewModel(context, RTEntryViewModel.Type.HEADING, it.wordType.name.lowercase(Locale.US)))
            addResultSection(favorites, data, R.string.thesaurus_section_synonyms, it.synonyms)
            addResultSection(favorites, data, R.string.thesaurus_section_antonyms, it.antonyms)
        }
        return data
    }

    private fun emptyResult(): ResultListData<RTEntryViewModel> {
        return ResultListData(query, emptyList())
    }

    private fun addResultSection(favorites: Set<String>, results: MutableList<RTEntryViewModel>, @StringRes sectionHeadingResId: Int, words: List<String>) {
        if (words.isNotEmpty()) {
            results.add(RTEntryViewModel(context, RTEntryViewModel.Type.SUBHEADING, context.getString(sectionHeadingResId)))
            words.forEach { word ->
                results.add(RTEntryViewModel(
                        context,
                        RTEntryViewModel.Type.WORD,
                        word,
                        favorites.contains(word)
                ))
            }
        }
    }

}
