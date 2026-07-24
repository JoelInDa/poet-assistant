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
import ca.rmen.rhymer.RhymeResult
import dagger.hilt.android.EntryPointAccessors

class RhymerLiveData(context: Context, val query: String) : ResultListLiveData<ResultListData<RTEntryViewModel>>(context) {

    companion object {
        private val TAG = Constants.TAG + RhymerLiveData::class.java.simpleName
    }

    private val mPrefs: SettingsPrefs
    private val mRhymer: Rhymer
    private val mFavorites: Favorites

    init {
        val entryPoint = EntryPointAccessors.fromApplication(context.applicationContext, NonAndroidEntryPoint::class.java)
        mRhymer = entryPoint.rhymer()
        mPrefs = entryPoint.prefs()
        mFavorites = entryPoint.favorites()
    }

    override fun loadInBackground(): ResultListData<RTEntryViewModel> {
        Log.d(TAG, "loadInBackground: query=$query")
        val before = System.currentTimeMillis()

        val data = ArrayList<RTEntryViewModel>()
        if (TextUtils.isEmpty(query)) return emptyResult()

        val rhymeResults = mRhymer.getRhymingWords(query, Constants.MAX_RESULTS)
                ?: return emptyResult()

        val layout = SettingsPrefs.getLayout(mPrefs)
        // The favorite set is still used to flag matching words (their pills get the gold
        // border), but favorites are no longer duplicated into their own section here: they
        // live only on the dedicated favorites tab.
        val favorites = mFavorites.getFavorites()
        rhymeResults.forEach {
            // Add the word variant, if there are multiple pronunciations.
            if (rhymeResults.size > 1) {
                val heading = query + " (" + (it.variantNumber + 1) + ")"
                data.add(RTEntryViewModel(context, RTEntryViewModel.Type.HEADING, heading))
            }
            addResultSection(favorites, data, R.string.rhyme_section_stress_syllables, it.strictRhymes, layout)
            addResultSection(favorites, data, R.string.rhyme_section_three_syllables, it.threeSyllableRhymes, layout)
            addResultSection(favorites, data, R.string.rhyme_section_two_syllables, it.twoSyllableRhymes, layout)
            addResultSection(favorites, data, R.string.rhyme_section_one_syllable, it.oneSyllableRhymes, layout)
        }
        val result = ResultListData(query, data)
        val after = System.currentTimeMillis()
        Log.d(TAG, "loadInBackground finished in ${(after - before)} ms")
        return result
    }

    private fun emptyResult(): ResultListData<RTEntryViewModel> {
        return ResultListData(query, emptyList())
    }

    private fun addResultSection(favorites: Set<String>, results: MutableList<RTEntryViewModel>, sectionHeadingResId: Int, rhymes: Array<String>, layout: ca.rmen.android.poetassistant.settings.SettingsPrefs.Layout) {
        if (rhymes.isNotEmpty()) {
            val wordsWithDefinitions = if (mPrefs.isAllRhymesEnabled) mRhymer.getWordsWithDefinitions(rhymes) else null
            results.add(RTEntryViewModel(context, RTEntryViewModel.Type.SUBHEADING, context.getString(sectionHeadingResId)))
            rhymes.forEach { rhyme ->
                val hasDefinition = wordsWithDefinitions == null || wordsWithDefinitions.contains(rhyme)
                results.add(RTEntryViewModel(
                        context,
                        RTEntryViewModel.Type.WORD,
                        rhyme,
                        favorites.contains(rhyme),
                        hasDefinition,
                        layout == SettingsPrefs.Layout.EFFICIENT))
            }
            if (results.size >= Constants.MAX_RESULTS) {
                results.add(RTEntryViewModel(
                        context,
                        RTEntryViewModel.Type.SUBHEADING,
                        context.getString(R.string.max_results, Constants.MAX_RESULTS)))
            }
        }
    }

}
