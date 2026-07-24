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

package ca.rmen.android.poetassistant

import androidx.annotation.MainThread
import androidx.appcompat.app.AppCompatDelegate
import ca.rmen.android.poetassistant.settings.SettingsPrefs

object Theme {
    /**
     * Only an explicit Light/Dark choice, no "follow system".
     *
     * The target device runs Android 8.1, which predates the system-wide dark theme
     * (Android 10). MODE_NIGHT_FOLLOW_SYSTEM there has nothing to follow and silently
     * resolves to light, so the old "Auto" option was a setting that appeared to do
     * something and did not. Dark is the default.
     */
    @MainThread
    fun setThemeFromSettings(settingsPrefs: SettingsPrefs) {
        val mode = if (settingsPrefs.theme == SettingsPrefs.THEME_LIGHT) {
            AppCompatDelegate.MODE_NIGHT_NO
        } else {
            AppCompatDelegate.MODE_NIGHT_YES
        }
        AppCompatDelegate.setDefaultNightMode(mode)
    }
}
