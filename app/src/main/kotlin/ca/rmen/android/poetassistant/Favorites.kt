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

package ca.rmen.android.poetassistant

import androidx.lifecycle.LiveData
import androidx.lifecycle.map
import androidx.annotation.MainThread
import androidx.annotation.WorkerThread
import android.util.Log

class Favorites (private val threading: Threading, private val favoriteDao: FavoriteDao) {
    companion object {
        private val TAG = Constants.TAG + Favorites::class.java.simpleName
    }

    fun getIsFavoriteLiveData(word: String): LiveData<Boolean> {
        return favoriteDao.getCountLiveData(word).map { count -> count > 0 }
    }

    fun getFavoritesLiveData(): LiveData<List<Favorite>> = favoriteDao.getFavoritesLiveData()

    @WorkerThread
    fun getFavorites(): Set<String> {
        return favoriteDao.getFavorites().map(Favorite::getWord).toSet()
    }

    @MainThread
    fun saveFavorite(word: String, isFavorite: Boolean) {
        if (isFavorite) threading.execute({ favoriteDao.insert(Favorite(word)) })
        else removeFavorite(word)
    }

    @MainThread
    private fun removeFavorite(favorite: String) {
        Log.v(TAG, "removeFavorite $favorite")
        threading.execute({ favoriteDao.delete(Favorite(favorite)) })
    }

    @MainThread
    fun clear() {
        threading.execute({ favoriteDao.deleteAll() })
    }
}
