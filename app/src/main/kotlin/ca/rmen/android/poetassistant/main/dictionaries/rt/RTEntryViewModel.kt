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
import androidx.databinding.ObservableBoolean
import ca.rmen.android.poetassistant.databinding.BindingCallbackAdapter
import ca.rmen.android.poetassistant.di.NonAndroidEntryPoint
import dagger.hilt.android.EntryPointAccessors

class RTEntryViewModel(context: Context, val type: Type, val text: String,
                       isFavoriteInitialValue: Boolean, val hasDefinition: Boolean,
                       /**
                        * How opaque to draw the chip, so the best results stand out and the weak
                        * tail recedes (the shading from the RhymeZone screenshot). The rhymer sets
                        * it - by frequency for perfect rhymes, by match quality for near rhymes so
                        * brightness tracks the ordering. 1 (fully opaque) everywhere else.
                        */
                       val chipAlpha: Float = 1f) {
    enum class Type {
        HEADING,
        SUBHEADING,
        WORD
    }

    val isFavorite = ObservableBoolean()

    // Headings/subheadings: no favorite state, no definition.
    constructor(context: Context, type: Type, text: String) :
            this(context, type, text, false, false)

    // Words where we only know the favorite state (thesaurus/favorites): assume a definition exists.
    constructor(context: Context, type: Type, text: String, isFavoriteInitialValue: Boolean) :
            this(context, type, text, isFavoriteInitialValue, true)

    init {
        val favorites = EntryPointAccessors.fromApplication(context, NonAndroidEntryPoint::class.java).favorites()
        isFavorite.set(isFavoriteInitialValue)
        isFavorite.addOnPropertyChangedCallback(BindingCallbackAdapter(object : BindingCallbackAdapter.Callback {
            override fun onChanged() {
                favorites.saveFavorite(text, isFavorite.get())
            }
        }))
    }

    override fun toString(): String {
        return "RTEntryViewModel(text='$text')"
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as RTEntryViewModel

        if (type != other.type) return false
        if (text != other.text) return false
        if (hasDefinition != other.hasDefinition) return false
        // Compare the favorite *value*, not the ObservableBoolean instance. ObservableBoolean has
        // no equals(), so comparing the objects is reference equality - and every RTEntryViewModel
        // makes a fresh one, so two otherwise-identical items would never be equal. That made
        // DiffUtil treat every item as changed across emissions, tearing down and rebuilding the
        // whole list (a visible flash) instead of just inserting new items.
        if (isFavorite.get() != other.isFavorite.get()) return false

        return true
    }

    override fun hashCode(): Int {
        var result = type.hashCode()
        result = 31 * result + text.hashCode()
        result = 31 * result + hasDefinition.hashCode()
        result = 31 * result + isFavorite.get().hashCode()
        return result
    }


}
