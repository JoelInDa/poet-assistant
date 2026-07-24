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

package ca.rmen.android.poetassistant.main.dictionaries

import androidx.lifecycle.LiveData
import android.content.Context
import ca.rmen.android.poetassistant.di.NonAndroidEntryPoint
import dagger.hilt.android.EntryPointAccessors

abstract class ResultListLiveData<T> protected constructor(protected val context: Context) : LiveData<T>() {
    private var mIsLoading = false

    @Volatile
    private var mCanceled = false

    /**
     * True once this LiveData has gone inactive - which, with the query switchMap in
     * ResultListViewModel, means the query has been superseded (the user moved to another word).
     * A long-running loadInBackground can check this to skip expensive work that no one will see.
     */
    protected val isCanceled: Boolean get() = mCanceled

    protected abstract fun loadInBackground(): T

    /**
     * Emit an intermediate result from the background thread, before loadInBackground returns its
     * final value. Used to paint fast results immediately and fill in slow ones afterwards.
     */
    protected fun publishProgress(result: T) = postValue(result)

    override fun onActive() {
        if (value == null && !mIsLoading) {
            mIsLoading = true
            mCanceled = false
            val entryPoint = EntryPointAccessors.fromApplication(
                context.applicationContext,
                NonAndroidEntryPoint::class.java
            )
            val threading = entryPoint.threading()
            threading.execute(
                { loadInBackground() },
                {
                    value = it
                    mIsLoading = false
                }
            )
        }
    }

    override fun onInactive() {
        mCanceled = true
    }
}
