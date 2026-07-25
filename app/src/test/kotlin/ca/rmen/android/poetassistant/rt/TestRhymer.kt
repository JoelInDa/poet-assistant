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

package ca.rmen.android.poetassistant.rt

import ca.rmen.android.poetassistant.main.dictionaries.rt.Rhymer
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import javax.inject.Inject

/**
 * Exercises the M3 rhyme engine (CMUdict `pronunciation` table): syllable-count grouping and
 * frequency ordering (common words first), replacing the old ca.rmen:rhymer library.
 */
@HiltAndroidTest
@Config(application = HiltTestApplication::class)
@RunWith(RobolectricTestRunner::class)
class TestRhymer {

    @get:Rule
    val hiltTestRule = HiltAndroidRule(this)

    @Inject
    lateinit var rhymer: Rhymer

    @Before
    fun setup() {
        hiltTestRule.inject()
    }

    @Test
    fun testNightGroupedAndFrequencyOrdered() {
        val results = rhymer.getRhymingWords("night")
        assertEquals(1, results.size) // "night" has one pronunciation

        val oneSyllable = results[0].sections.first { it.syllables == 1 }.words.map { it.word }
        assertTrue(oneSyllable.containsAll(listOf("right", "light", "might", "white")))
        // Common words come before rare ones.
        assertTrue(oneSyllable.indexOf("right") < oneSyllable.indexOf("blight"))

        val twoSyllable = results[0].sections.first { it.syllables == 2 }.words.map { it.word }
        assertTrue(twoSyllable.contains("tonight"))
    }

    @Test
    fun testReducedFinalVowelRhymes() {
        // muffin, mcguffin and toughen all reduce to the "AH F AH N" rhyme key.
        val rhymes = rhymer.getRhymingWords("muffin").flatMap { it.sections }.flatMap { it.words }.map { it.word }
        assertTrue(rhymes.contains("mcguffin"))
        assertTrue(rhymes.contains("toughen"))
    }

    @Test
    fun testNearRhymes() {
        val near = rhymer.getNearRhymingWords("night")
            .flatMap { it.sections }.flatMap { it.words }.map { it.word }
        // slant rhymes present (night -> side / wide, T->D)
        assertTrue(near.contains("side"))
        assertTrue(near.contains("wide"))
        // perfect rhymes are excluded - they're the "perfect" view
        assertFalse(near.contains("right"))
        assertFalse(near.contains("light"))
    }

    @Test
    fun testOrangeHasNoRhymes() {
        val results = rhymer.getRhymingWords("orange") // in the dictionary, but famously rhymeless
        assertTrue(results.isNotEmpty())
        assertTrue(results.all { it.sections.isEmpty() })
    }
}
