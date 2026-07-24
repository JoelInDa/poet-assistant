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

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.util.Log
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.VisibleForTesting
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.updatePadding
import androidx.databinding.DataBindingUtil
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SwitchPreferenceCompat
import ca.rmen.android.poetassistant.Constants
import ca.rmen.android.poetassistant.R
import ca.rmen.android.poetassistant.databinding.ActivitySettingsBinding
import ca.rmen.android.poetassistant.getInsets
import ca.rmen.android.poetassistant.fixStatusBarViewForInsets
import ca.rmen.android.poetassistant.main.dictionaries.ConfirmDialogFragment
import com.google.android.material.snackbar.Snackbar
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

private val TAG = Constants.TAG + SettingsActivity::class.java.simpleName

// Split into separate impl and base class to get full code coverage stats:
// https://medium.com/livefront/dagger-hilt-testing-injected-android-components-with-code-coverage-30089a1f6872

@AndroidEntryPoint
class SettingsActivity : SettingsActivityImpl()

open class SettingsActivityImpl : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val binding = DataBindingUtil.setContentView<ActivitySettingsBinding>(this, R.layout.activity_settings)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        getInsets(binding.settingsFragment) { view, insets ->
            view.updatePadding(
                left = insets.left,
                right = insets.right,
                bottom = insets.bottom,
            )
            fixStatusBarViewForInsets(binding.statusBarView, insets)
        }
        volumeControlStream = AudioManager.STREAM_MUSIC
    }
}

// Split into separate impl and base class to get full code coverage stats:
// https://medium.com/livefront/dagger-hilt-testing-injected-android-components-with-code-coverage-30089a1f6872

@AndroidEntryPoint
class GeneralPreferenceFragment : GeneralPreferenceFragmentImpl()

open class GeneralPreferenceFragmentImpl : PreferenceFragmentCompat(), ConfirmDialogFragment.ConfirmDialogListener {
    companion object {
        private const val DIALOG_TAG = "dialog_tag"
        private const val ACTION_EXPORT_FAVORITES = 1
        private const val ACTION_IMPORT_FAVORITES = 2
        private const val ACTION_CLEAR_SEARCH_HISTORY = 3
        @VisibleForTesting
        const val PREF_CATEGORY_VOICE = "PREF_CATEGORY_VOICE"
        private const val PREF_CATEGORY_NOTIFICATIONS = "PREF_CATEGORY_NOTIFICATIONS"
        private const val PREF_EXPORT_FAVORITES = "PREF_EXPORT_FAVORITES"
        private const val PREF_IMPORT_FAVORITES = "PREF_IMPORT_FAVORITES"
        private const val PREF_CLEAR_SEARCH_HISTORY = "PREF_CLEAR_SEARCH_HISTORY"
    }

    @Inject lateinit var mPrefs: SettingsPrefs
    private lateinit var mViewModel: SettingsViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        context?.let {
            mViewModel = ViewModelProvider(this).get(SettingsViewModel::class.java)
            mViewModel.snackbarText.observe(this, mSnackbarCallback)
        }
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        loadPreferences()
    }

    private fun loadPreferences() {
        context?.let {
            addPreferencesFromResource(R.xml.pref_general)
            setOnPreferenceClickListener(PREF_EXPORT_FAVORITES, Runnable { startActivityForResult(mViewModel.getExportFavoritesIntent(), ACTION_EXPORT_FAVORITES) })
            setOnPreferenceClickListener(PREF_IMPORT_FAVORITES, Runnable { startActivityForResult(mViewModel.getImportFavoritesIntent(), ACTION_IMPORT_FAVORITES) })
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        Log.d(TAG, "onActivityResult: requestCode=$requestCode, resultCode=$resultCode, data=$data")
        val uri = data?.data
        if (requestCode == ACTION_EXPORT_FAVORITES && resultCode == Activity.RESULT_OK && uri != null) {
            mViewModel.exportFavorites(uri)
        } else if (requestCode == ACTION_IMPORT_FAVORITES && resultCode == Activity.RESULT_OK && uri != null) {
            mViewModel.importFavorites(uri)
        }
    }

    override fun onOk(actionId: Int) = Unit


    private fun removePreferences(categoryKey: String, vararg preferenceKeys: String) {
        preferenceKeys.forEach { removePreference(categoryKey, findPreference(it)!!) }
    }

    private fun removePreference(categoryKey: String, preference: Preference) {
        val category = preferenceScreen.findPreference<Preference>(categoryKey)!! as PreferenceCategory
        category.removePreference(preference)
    }

    private fun setOnPreferenceClickListener(preferenceKey: String, runnable: Runnable) {
        setOnPreferenceClickListener(findPreference<Preference>(preferenceKey)!!, runnable)
    }

    private fun setOnPreferenceClickListener(preference: Preference, runnable: Runnable) {
        preference.setOnPreferenceClickListener {
            runnable.run()
            false
        }
    }

    private val mSnackbarCallback = Observer<String> { snackbarText ->
        view?.let {
            if (!TextUtils.isEmpty(snackbarText)) {
                Snackbar.make(it, snackbarText!!, Snackbar.LENGTH_LONG).show()
            }
        }
    }

}

