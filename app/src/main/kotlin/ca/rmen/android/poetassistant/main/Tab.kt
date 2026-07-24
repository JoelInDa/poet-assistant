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

/**
 * The pages of the view pager, in display order.
 *
 * RHYMER, THESAURUS and DICTIONARY are the three lookup modes you swipe between.
 * FAVORITES is the saved-words list.
 *
 * The ordinal is used as the pager position, so the declaration order matters.
 */
enum class Tab {
    RHYMER, THESAURUS, DICTIONARY, FAVORITES;

    companion object {
        fun parse(value: String): Tab? = entries.firstOrNull { it.name.equals(value, true) }
    }
}
