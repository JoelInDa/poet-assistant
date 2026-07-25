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

import ca.rmen.android.poetassistant.main.dictionaries.EmbeddedDb

/** The rhyming words of a single pronunciation, grouped by the rhyming word's syllable count. */
class RhymeResult(val variant: Int, val sections: List<RhymeSection>)

/** One syllable-count group; [words] is ordered most-common-first. */
class RhymeSection(val syllables: Int, val words: List<RhymeWord>)

/** A rhyming word with its wordfreq [frequency] (round(zipf*100); 0 = rare / proper noun). */
class RhymeWord(val word: String, val frequency: Int)

/**
 * Perfect-rhyme lookup over the CMUdict-derived `pronunciation` table (built by
 * tools/rhymedb/build_db.py). Two words rhyme iff they share a `rhyme_key` (the phonemes from the
 * last stressed vowel to the end). Results are grouped by the rhyming word's syllable count and
 * ordered by wordfreq `frequency` (common, singable words first), with `google_ngram` as a
 * tiebreaker for the rare tail. Replaces the old ca.rmen:rhymer library, whose SortedSet contract
 * forced alphabetical output.
 */
class Rhymer(private val embeddedDb: EmbeddedDb) {

    companion object {
        // Max phonetic tail distance for a near rhyme (tuned in tools' nearrhyme.py).
        private const val NEAR_THRESHOLD = 1.6
    }

    fun isLoaded(): Boolean = embeddedDb.isLoaded()

    /**
     * @return one [RhymeResult] per pronunciation of [word] (a word like "read" has more than one),
     * or an empty list if [word] isn't in the pronunciation table. Each result's sections are
     * ordered by syllable count, and the words within each section most-common-first.
     */
    fun getRhymingWords(word: String): List<RhymeResult> =
        pronunciationsOf(word).map { (variant, rhymeKey) -> RhymeResult(variant, rhymesForKey(rhymeKey, word)) }

    /**
     * Near (slant) rhymes for [word]: words whose rhyme tail is phonetically close but not identical.
     * Grouped by syllable count, ordered by closeness then frequency. Perfect rhymes are excluded -
     * they're what [getRhymingWords] returns.
     */
    fun getNearRhymingWords(word: String): List<RhymeResult> =
        pronunciationsOf(word).map { (variant, rhymeKey) -> RhymeResult(variant, nearRhymesForKey(rhymeKey, word)) }

    /** The (variant, rhyme_key) pairs for each pronunciation of [word]. */
    private fun pronunciationsOf(word: String): List<Pair<Int, String>> {
        val variants = ArrayList<Pair<Int, String>>()
        embeddedDb.query("pronunciation", arrayOf("variant", "rhyme_key"), "word=?", arrayOf(word))
            ?.use { cursor ->
                while (cursor.moveToNext()) variants.add(cursor.getInt(0) to cursor.getString(1))
            }
        return variants
    }

    private fun nearRhymesForKey(rhymeKey: String, excludeWord: String): List<RhymeSection> {
        val queryTail = rhymeKey.split(' ')
        val vowel = queryTail[0] // candidates share the query's stressed vowel
        // A candidate word can have several pronunciations; keep its closest, and drop any word that
        // is also a perfect rhyme (it belongs in the perfect view).
        val perfect = HashSet<String>()
        val best = HashMap<String, Triple<Int, Int, Double>>() // word -> (syllables, frequency, dist)
        embeddedDb.query(
            false, "pronunciation", arrayOf("word", "syllables", "frequency", "rhyme_key"),
            "(rhyme_key = ? OR rhyme_key GLOB ?) AND word != ?", arrayOf(vowel, "$vowel *", excludeWord),
            null, null
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val candKey = cursor.getString(3)
                val candWord = cursor.getString(0)
                if (candKey == rhymeKey) {
                    perfect.add(candWord)
                    continue
                }
                val dist = Phonetics.tailDist(queryTail, candKey.split(' '))
                if (dist <= 0.0 || dist > NEAR_THRESHOLD) continue
                val existing = best[candWord]
                if (existing == null || dist < existing.third) {
                    best[candWord] = Triple(cursor.getInt(1), cursor.getInt(2), dist)
                }
            }
        }
        perfect.forEach { best.remove(it) }

        // Group by syllable count; within a group, closest first then most common.
        return best.entries
            .groupBy { it.value.first }
            .toSortedMap()
            .map { (syllables, entries) ->
                val words = entries
                    .sortedWith(compareBy({ it.value.third }, { -it.value.second }, { it.key }))
                    .map { RhymeWord(it.key, it.value.second) }
                RhymeSection(syllables, words)
            }
    }

    private fun rhymesForKey(rhymeKey: String, excludeWord: String): List<RhymeSection> {
        // Rows come out grouped by syllable count, common-first within each group. A word can have
        // several pronunciations under one rhyme_key, so dedupe on the word (keeping the first,
        // which is its lowest syllable count / highest frequency by the ORDER BY).
        val bySyllable = LinkedHashMap<Int, MutableList<RhymeWord>>()
        val seen = HashSet<String>()
        embeddedDb.query(
            false, "pronunciation", arrayOf("word", "syllables", "frequency"),
            "rhyme_key=? AND word!=?", arrayOf(rhymeKey, excludeWord),
            "syllables, frequency DESC, google_ngram DESC, word", null
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val rhyme = cursor.getString(0)
                if (!seen.add(rhyme)) continue
                bySyllable.getOrPut(cursor.getInt(1)) { ArrayList() }.add(RhymeWord(rhyme, cursor.getInt(2)))
            }
        }
        return bySyllable.map { (syllables, words) -> RhymeSection(syllables, words) }
    }
}
