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

package ca.rmen.android.poetassistant.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.preference.PreferenceManager
import ca.rmen.android.poetassistant.main.dictionaries.dictionary.Dictionary
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    application: Application,
    dictionary: Dictionary,
    settingsPrefs: SettingsPrefs,
) : AndroidViewModel(application) {

    private val mListener: SettingsChangeListener = SettingsChangeListener(application, dictionary, settingsPrefs)

    init {
        PreferenceManager.getDefaultSharedPreferences(application).registerOnSharedPreferenceChangeListener(mListener)
    }

    override fun onCleared() {
        super.onCleared()
        PreferenceManager.getDefaultSharedPreferences(getApplication()).unregisterOnSharedPreferenceChangeListener(mListener)
    }
}
