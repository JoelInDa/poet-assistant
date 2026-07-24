/*
 * Copyright (c) 2016 - present Carmen Alvarez
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

import android.content.Context
import android.content.res.Configuration
import androidx.preference.PreferenceManager
import ca.rmen.android.poetassistant.settings.SettingsPrefs

/**
 * Applies the user's "Text size" preference as an app-wide font scale.
 *
 * Called from each activity's attachBaseContext (before Hilt is available), so it reads the
 * preference straight from SharedPreferences and returns a context whose Configuration.fontScale
 * is set. Every sp dimension the activity inflates is then multiplied by that scale.
 */
object FontScale {
    fun wrap(context: Context): Context {
        val scale = (PreferenceManager.getDefaultSharedPreferences(context)
            .getString(SettingsPrefs.PREF_TEXT_SIZE, SettingsPrefs.TEXT_SIZE_DEFAULT)
            ?: SettingsPrefs.TEXT_SIZE_DEFAULT).toFloatOrNull() ?: 1.0f
        if (scale == context.resources.configuration.fontScale) return context
        val config = Configuration(context.resources.configuration)
        config.fontScale = scale
        return context.createConfigurationContext(config)
    }
}
