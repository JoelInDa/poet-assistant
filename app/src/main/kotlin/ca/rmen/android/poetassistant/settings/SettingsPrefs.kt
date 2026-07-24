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
import androidx.preference.PreferenceManager
import ca.rmen.android.poetassistant.main.Tab

class SettingsPrefs(application: Application) {

    private val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(application)

    companion object {
        const val THEME_LIGHT = "Light"
        const val THEME_DARK = "Dark"
        const val PREF_THEME = "PREF_THEME"
        const val PREF_TEXT_SIZE = "PREF_TEXT_SIZE"
        // Multiplies every sp size. 1.0 is the M1 baseline (already +25% for the panel); the four
        // steps are one larger and two smaller, with 1.0 the default (the 3rd-largest).
        const val TEXT_SIZE_DEFAULT = "1.0"
        private const val PREF_ALL_RHYMES_ENABLED = "PREF_ALL_RHYMES_ENABLED"
        private const val PREF_MATCH_AO_AA_ENABLED = "PREF_MATCH_AO_AA_ENABLED"
        private const val PREF_MATCH_AOR_AO_ENABLED = "PREF_MATCH_AOR_AO_ENABLED"
        private const val PREF_TAB = "PREF_TAB"
        private const val PREF_TAB_DEFAULT = "RHYMER"

        fun getTab(prefs: SettingsPrefs): Tab? {
            return Tab.parse(prefs.tab)
        }
    }

    var theme: String
        get() {
            return sharedPreferences.getString(PREF_THEME, THEME_DARK) ?: THEME_DARK
        }
        set(newValue) {
            sharedPreferences.edit().putString(PREF_THEME, newValue).apply()
        }

    val textScale: Float
        get() = (sharedPreferences.getString(PREF_TEXT_SIZE, TEXT_SIZE_DEFAULT)
            ?: TEXT_SIZE_DEFAULT).toFloatOrNull() ?: 1.0f

    var isAllRhymesEnabled: Boolean
        get () {
            return sharedPreferences.getBoolean(PREF_ALL_RHYMES_ENABLED, false)
        }
        set(newValue) {
            sharedPreferences.edit().putBoolean(PREF_ALL_RHYMES_ENABLED, newValue).apply()
        }

    var isAOAAMatchEnabled: Boolean
        get () {
            return sharedPreferences.getBoolean(PREF_MATCH_AO_AA_ENABLED, false)
        }
        set(newValue) {
            sharedPreferences.edit().putBoolean(PREF_MATCH_AO_AA_ENABLED, newValue).apply()
        }

    var isAORAOMatchEnabled: Boolean
        get () {
            return sharedPreferences.getBoolean(PREF_MATCH_AOR_AO_ENABLED, false)
        }
        set(newValue) {
            sharedPreferences.edit().putBoolean(PREF_MATCH_AOR_AO_ENABLED, newValue).apply()
        }

    var tab: String
        get() {
            return sharedPreferences.getString(PREF_TAB, PREF_TAB_DEFAULT)
                    ?: PREF_TAB_DEFAULT
        }
        set(newValue) {
            sharedPreferences.edit().putString(PREF_TAB, newValue).apply()
        }
}
