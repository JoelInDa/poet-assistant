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

package ca.rmen.android.poetassistant.main.dictionaries.rt

import android.text.TextUtils
import android.util.Log
import ca.rmen.android.poetassistant.Constants
import ca.rmen.android.poetassistant.main.dictionaries.EmbeddedDb
import ca.rmen.android.poetassistant.main.dictionaries.textprocessing.WordSimilarities
import java.util.EnumSet
import javax.inject.Inject

class Thesaurus @Inject constructor(private val embeddedDb: EmbeddedDb) {
    companion object {
        private val TAG = Constants.TAG + Thesaurus::class.java.simpleName
    }

    private enum class RelationType(val columnName: String) {
        SYNONYM("synonyms"),
        ANTONYM("antonyms")
    }

    fun isLoaded(): Boolean = embeddedDb.isLoaded()

    fun lookup(word: String, includeReverseLookup: Boolean): ThesaurusEntry = lookup(word, EnumSet.allOf(RelationType::class.java), includeReverseLookup)

    private fun lookup(word: String, relationTypes: Set<RelationType>, includeReverseLookup: Boolean): ThesaurusEntry {
        val projection = arrayOf("word_type", "synonyms", "antonyms")
        val selection = "word=?"
        var selectionArgs = arrayOf(word)
        var lookupWord = word
        var cursor = embeddedDb.query("thesaurus", projection, selection, selectionArgs)

        if (cursor != null && cursor.count == 0) {
            val closestWord = WordSimilarities().findClosestWord(word, embeddedDb)
            if (closestWord != null) {
                lookupWord = closestWord
                cursor.close()
                selectionArgs = arrayOf(lookupWord)
                cursor = embeddedDb.query("thesaurus", projection, selection, selectionArgs)
            }
        }
        if (cursor != null) {
            val result = ArrayList<ThesaurusEntry.ThesaurusEntryDetails>()
            try {
                val forwardSynonyms = ArrayList<String>()
                val forwardAntonyms = ArrayList<String>()
                while (cursor.moveToNext()) {
                    val wordType = ThesaurusEntry.WordType.valueOf(cursor.getString(0))
                    var synonyms = emptyList<String>()
                    var antonyms = emptyList<String>()
                    if (relationTypes.contains(RelationType.SYNONYM)) {
                        val synonymsList = cursor.getString(1)
                        synonyms = split(synonymsList)
                        forwardSynonyms.addAll(synonyms)
                    }
                    if (relationTypes.contains(RelationType.ANTONYM)) {
                        val antonymsList = cursor.getString(2)
                        antonyms = split(antonymsList)
                        forwardAntonyms.addAll(antonyms)
                    }
                    result.add(ThesaurusEntry.ThesaurusEntryDetails(wordType, synonyms, antonyms))
                }
                if (includeReverseLookup) {
                    if (relationTypes.contains(RelationType.SYNONYM)) {
                        result.addAll(lookupReverseRelatedWords(RelationType.SYNONYM, lookupWord, forwardSynonyms))
                    }
                    if (relationTypes.contains(RelationType.ANTONYM)) {
                        result.addAll(lookupReverseRelatedWords(RelationType.ANTONYM, lookupWord, forwardAntonyms))
                    }
                }
                return ThesaurusEntry(lookupWord, result)
            } finally {
                cursor.close()
            }
        }
        return ThesaurusEntry(word, emptyList())
    }

    /**
     * @param relationType        whether we want to look up synonyms or antonyms
     * @param word                the word for which we want to find synonyms or antonyms
     * @param excludeRelatedWords words from this collection will be excluded from the result
     * @return words which have an entry in the thesaurus table containing the given word as a synonym or antonym.
     *
     * This reads the precomputed `thesaurus_reverse` table (built by tools/rhymedb/build_db.py):
     * an inverted copy of `thesaurus` where a row (word=X, word_type=T, synonyms=...) lists the
     * words of type T that have X among *their* synonyms. So the reverse lookup is an indexed
     * `word = ?` query instead of the old leading-wildcard LIKE that scanned the whole table
     * (~3.5s on the Palm). The referring words are stored in the same order the old scan produced
     * them, so results are unchanged.
     */
    private fun lookupReverseRelatedWords(relationType: RelationType, word: String, excludeRelatedWords: Collection<String>): List<ThesaurusEntry.ThesaurusEntryDetails> {
        Log.v(TAG, "lookupReverseRelatedWords: relationType=$relationType, word=$word")
        val exclude = excludeRelatedWords.toHashSet()
        val projection = arrayOf("word_type", relationType.columnName)
        val cursor = embeddedDb.query("thesaurus_reverse", projection, "word=?", arrayOf(word))
        cursor?.use {
            val reverseRelatedWords = ArrayList<ThesaurusEntry.ThesaurusEntryDetails>()
            while (cursor.moveToNext()) {
                val wordType = ThesaurusEntry.WordType.valueOf(cursor.getString(0))
                val relatedWords = split(cursor.getString(1)).filterNot { exclude.contains(it) }
                if (relatedWords.isEmpty()) continue
                val entryDetails = if (relationType == RelationType.SYNONYM) ThesaurusEntry.ThesaurusEntryDetails(wordType, relatedWords, emptyList())
                else ThesaurusEntry.ThesaurusEntryDetails(wordType, emptyList(), relatedWords)

                reverseRelatedWords.add(entryDetails)
            }
            val result = merge(reverseRelatedWords)
            Log.v(TAG, "lookupReverseRelatedWords: result=$result")
            return result
        }
        return emptyList()
    }

    /**
     * @param entries a list of {@link ThesaurusEntry.ThesaurusEntryDetails} possibly containing multiple items for a given word type.
     * @return a list of one {@link ThesaurusEntry.ThesaurusEntryDetails} per word type.
     */
    private fun merge(entries: List<ThesaurusEntry.ThesaurusEntryDetails>): List<ThesaurusEntry.ThesaurusEntryDetails> {
        return entries.asSequence().groupBy { thesaurusEntryDetails -> thesaurusEntryDetails.wordType }
                .map { group ->
                    group.value.reduce { acc, thesaurusEntryDetails ->
                        ThesaurusEntry.ThesaurusEntryDetails(acc.wordType,
                                acc.synonyms.union(thesaurusEntryDetails.synonyms).toList(),
                                acc.antonyms.union(thesaurusEntryDetails.antonyms).toList())
                    }
                }.toList()
    }

    /**
     * @return the synonyms of the given word.
     */
    fun getFlatSynonyms(word: String, includeReverseLookup: Boolean): Collection<String> {
        val entries = lookup(word, EnumSet.of(RelationType.SYNONYM), includeReverseLookup).entries
        return merge(entries).flatMap { thesaurusEntryDetails -> thesaurusEntryDetails.synonyms }
    }

    private fun split(string: String): List<String> {
        if (TextUtils.isEmpty(string)) return emptyList()
        return string.split(",")
    }
}
