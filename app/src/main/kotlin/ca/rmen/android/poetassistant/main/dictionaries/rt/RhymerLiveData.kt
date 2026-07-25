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
import ca.rmen.android.poetassistant.Constants
import ca.rmen.android.poetassistant.Favorites
import ca.rmen.android.poetassistant.R
import ca.rmen.android.poetassistant.di.NonAndroidEntryPoint
import ca.rmen.android.poetassistant.main.dictionaries.ResultListData
import ca.rmen.android.poetassistant.main.dictionaries.ResultListLiveData
import ca.rmen.android.poetassistant.settings.SettingsPrefs
import dagger.hilt.android.EntryPointAccessors

class RhymerLiveData(context: Context, val query: String) : ResultListLiveData<ResultListData<RTEntryViewModel>>(context) {

    companion object {
        private val TAG = Constants.TAG + RhymerLiveData::class.java.simpleName
    }

    private val mRhymer: Rhymer
    private val mFavorites: Favorites
    private val mPrefs: SettingsPrefs

    init {
        val entryPoint = EntryPointAccessors.fromApplication(context.applicationContext, NonAndroidEntryPoint::class.java)
        mRhymer = entryPoint.rhymer()
        mFavorites = entryPoint.favorites()
        mPrefs = entryPoint.prefs()
    }

    override fun loadInBackground(): ResultListData<RTEntryViewModel> {
        Log.d(TAG, "loadInBackground: query=$query")
        val before = System.currentTimeMillis()

        if (TextUtils.isEmpty(query)) return emptyResult()
        val results = if (mPrefs.isNearRhymes) mRhymer.getNearRhymingWords(query) else mRhymer.getRhymingWords(query)
        if (results.isEmpty()) return emptyResult()

        // The favorite set flags matching words (their pills get the gold border); favorites are no
        // longer duplicated into their own section (that lives on the dedicated favorites tab).
        val favorites = mFavorites.getFavorites()
        val data = ArrayList<RTEntryViewModel>()
        results.forEach { result ->
            // Only label pronunciations when the query word has more than one (e.g. "read (1)").
            if (results.size > 1) {
                data.add(RTEntryViewModel(context, RTEntryViewModel.Type.HEADING, "$query (${result.variant + 1})"))
            }
            result.sections.forEach { section ->
                // syllables == 0 is the near-rhyme flat list: best-first, no syllable heading.
                if (section.syllables > 0) {
                    data.add(RTEntryViewModel(context, RTEntryViewModel.Type.SUBHEADING,
                            context.resources.getQuantityString(R.plurals.rhyme_syllables, section.syllables, section.syllables)))
                }
                section.words.forEach { rhyme ->
                    data.add(RTEntryViewModel(context, RTEntryViewModel.Type.WORD, rhyme.word,
                            favorites.contains(rhyme.word), true, rhyme.chipAlpha))
                }
            }
        }
        Log.d(TAG, "loadInBackground finished in ${System.currentTimeMillis() - before} ms")
        return ResultListData(query, data)
    }

    private fun emptyResult(): ResultListData<RTEntryViewModel> = ResultListData(query, emptyList())
}
