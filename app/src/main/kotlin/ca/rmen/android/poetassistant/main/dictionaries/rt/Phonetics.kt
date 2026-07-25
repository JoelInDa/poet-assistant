/*
 * Copyright (c) 2026 Joel Haus
 *
 * This file is part of jLyrics Assistant (a fork of Poet Assistant).
 *
 * jLyrics Assistant is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package ca.rmen.android.poetassistant.main.dictionaries.rt

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * ARPAbet phonetic distance for near (slant) rhymes. Given two rhyme tails - the phonemes from the
 * last stressed vowel to the end, i.e. the `rhyme_key` we already store, stress digits stripped -
 * [tailDist] returns a small number when the tails sound alike. Tuned in tools' nearrhyme.py against
 * seed words (night -> side, love -> tough, time -> drive, heart -> hard, month -> once); keep this
 * in sync with that reference if the weights change.
 */
object Phonetics {

    /** Weighted phonetic distance between two rhyme tails (lists of ARPAbet phonemes, no stress). */
    fun tailDist(a: List<String>, b: List<String>): Double {
        if (a.isEmpty() || b.isEmpty()) return MIXED_PENALTY
        var d = phonDist(a[0], b[0]) * VOWEL_WEIGHT // the stressed vowel is the heart of the rhyme
        val n = minOf(a.size, b.size)
        for (i in 1 until n) d += phonDist(a[i], b[i])
        d += GAP_PENALTY * abs(a.size - b.size)
        return d
    }

    private fun phonDist(a: String, b: String): Double {
        if (a == b) return 0.0
        val av = VOWELS[a]
        val bv = VOWELS[b]
        if (av != null && bv != null) return vowelDist(av, bv)
        val ac = CONS[a]
        val bc = CONS[b]
        if (ac != null && bc != null) return consDist(ac, bc)
        return MIXED_PENALTY // vowel vs consonant
    }

    private fun vowelDist(a: Vowel, b: Vowel): Double =
        sqrt(
            ((a.height - b.height) / 3.0).sq() +
                ((a.backness - b.backness) / 2.0).sq() +
                (a.round - b.round).sq()
        )

    private fun consDist(a: Cons, b: Cons): Double =
        abs(a.place - b.place) / 7.0 * W_PLACE +
            abs(MANNER.getValue(a.manner) - MANNER.getValue(b.manner)) / 5.0 * W_MANNER +
            abs(a.voice - b.voice) * W_VOICE

    private fun Double.sq() = this * this

    private const val VOWEL_WEIGHT = 2.0
    private const val GAP_PENALTY = 1.3
    private const val MIXED_PENALTY = 3.0
    private const val W_PLACE = 1.0
    private const val W_MANNER = 1.4
    private const val W_VOICE = 0.4

    private class Vowel(val height: Double, val backness: Double, val round: Double)
    private class Cons(val place: Double, val manner: String, val voice: Int)

    // Vowel: height 0=high..3=low, backness 0=front..2=back, roundness 0..1
    private val VOWELS = mapOf(
        "IY" to Vowel(0.0, 0.0, 0.0), "IH" to Vowel(0.7, 0.0, 0.0), "EY" to Vowel(1.5, 0.0, 0.0),
        "EH" to Vowel(2.0, 0.0, 0.0), "AE" to Vowel(3.0, 0.0, 0.0), "AA" to Vowel(3.0, 2.0, 0.0),
        "AO" to Vowel(2.5, 2.0, 1.0), "OW" to Vowel(1.5, 2.0, 1.0), "UH" to Vowel(0.7, 2.0, 1.0),
        "UW" to Vowel(0.0, 2.0, 1.0), "AH" to Vowel(1.7, 1.0, 0.0), "ER" to Vowel(1.7, 1.0, 0.3),
        "AW" to Vowel(2.5, 1.3, 0.4), "AY" to Vowel(2.5, 0.7, 0.0), "OY" to Vowel(2.0, 1.0, 0.5),
    )

    // Consonant: place 0=bilabial..7=glottal, manner, voicing. lateral (L) and rhotic (R) are split
    // out of "liquid" so R and L sit a manner-step apart.
    private val CONS = mapOf(
        "P" to Cons(0.0, "stop", 0), "B" to Cons(0.0, "stop", 1), "M" to Cons(0.0, "nasal", 1),
        "F" to Cons(1.0, "fric", 0), "V" to Cons(1.0, "fric", 1),
        "TH" to Cons(2.0, "fric", 0), "DH" to Cons(2.0, "fric", 1),
        "T" to Cons(3.0, "stop", 0), "D" to Cons(3.0, "stop", 1), "S" to Cons(3.0, "fric", 0),
        "Z" to Cons(3.0, "fric", 1), "N" to Cons(3.0, "nasal", 1), "L" to Cons(3.0, "lateral", 1),
        "R" to Cons(3.5, "rhotic", 1), "SH" to Cons(4.0, "fric", 0), "ZH" to Cons(4.0, "fric", 1),
        "CH" to Cons(4.0, "afr", 0), "JH" to Cons(4.0, "afr", 1), "Y" to Cons(5.0, "glide", 1),
        "K" to Cons(6.0, "stop", 0), "G" to Cons(6.0, "stop", 1), "NG" to Cons(6.0, "nasal", 1),
        "W" to Cons(6.5, "glide", 1), "HH" to Cons(7.0, "fric", 0),
    )

    private val MANNER = mapOf(
        "stop" to 0, "afr" to 1, "fric" to 2, "nasal" to 3, "lateral" to 4, "rhotic" to 5, "glide" to 6
    )
}
