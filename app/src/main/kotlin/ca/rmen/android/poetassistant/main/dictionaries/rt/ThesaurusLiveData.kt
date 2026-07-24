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
import ca.rmen.android.poetassistant.settings.SettingsPrefs
import dagger.hilt.android.EntryPointAccessors
import java.util.Locale

class ThesaurusLiveData constructor(context: Context, private val query: String) : ResultListLiveData<ResultListData<RTEntryViewModel>>(context) {
    companion object {
        private val TAG = Constants.TAG + ThesaurusLiveData::class.java.simpleName
    }

    private val mThesaurus: Thesaurus
    private val mPrefs: SettingsPrefs
    private val mFavorites: Favorites

    init {
        val entryPoint = EntryPointAccessors.fromApplication(context.applicationContext, NonAndroidEntryPoint::class.java)
        mThesaurus = entryPoint.thesaurus()
        mPrefs = entryPoint.prefs()
        mFavorites = entryPoint.favorites()
    }

    override fun loadInBackground(): ResultListData<RTEntryViewModel> {
        Log.d(TAG, "loadInBackground: query=$query")

        val data = ArrayList<RTEntryViewModel>()
        if (TextUtils.isEmpty(query)) return emptyResult()
        // Reverse lookup is always on now (it used to be a setting): also surface words that list
        // this word as one of their synonyms, not just the synonyms in this word's own entry.
        val result = mThesaurus.lookup(query, true)
        val entries = result.entries
        if (entries.isEmpty()) return emptyResult()

        val favorites = mFavorites.getFavorites()
        entries.forEach {
            data.add(RTEntryViewModel(context, RTEntryViewModel.Type.HEADING, it.wordType.name.lowercase(Locale.US)))
            addResultSection(favorites, data, R.string.thesaurus_section_synonyms, it.synonyms)
            addResultSection(favorites, data, R.string.thesaurus_section_antonyms, it.antonyms)
        }
        return ResultListData(result.word, data)
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
