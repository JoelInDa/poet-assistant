/*
 * Copyright (c) 2017 Carmen Alvarez
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

import androidx.room.Database
import androidx.room.RoomDatabase

/**
 * Version 3 drops the SUGGESTION table along with the search-history feature.
 *
 * There is no migration from 1 or 2: this fork ships under a new applicationId, so every
 * install is a fresh one and there is no older database on disk to upgrade. DbModule
 * uses a destructive fallback to make that explicit rather than crashing on a stale file.
 */
@Database(entities = [Favorite::class], version = 3)
abstract class UserDb : RoomDatabase() {
    abstract fun favoriteDao(): FavoriteDao
}
