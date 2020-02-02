/*
 * Copyright (C) 2015-2016 Daniel Schaal <daniel@schaal.email>
 *
 * This file is part of OCReader.
 *
 * OCReader is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * OCReader is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with OCReader.  If not, see <http://www.gnu.org/licenses/>.
 *
 */
package email.schaal.ocreader.database

import android.content.Context
import android.util.Log
import email.schaal.ocreader.database.model.Insertable
import email.schaal.ocreader.database.model.Item
import email.schaal.ocreader.database.model.TemporaryFeed
import email.schaal.ocreader.database.model.TemporaryFeed.Companion.getListTemporaryFeed
import io.realm.Realm
import io.realm.Realm.Transaction.OnSuccess
import io.realm.RealmConfiguration
import io.realm.RealmMigration
import io.realm.kotlin.createObject
import io.realm.kotlin.where

/**
 * Utility class containing some commonly used Queries for the Realm database.
 */
object Queries {
    private val TAG = Queries::class.java.name
    const val SCHEMA_VERSION = 12L
    private val initialData = Realm.Transaction { realm: Realm ->
        realm.deleteAll()
        realm.createObject<TemporaryFeed>(TemporaryFeed.LIST_ID)
        realm.createObject<TemporaryFeed>(TemporaryFeed.PAGER_ID)
    }
    private val migration: RealmMigration = DatabaseMigration()
    const val MAX_ITEMS = 10000

    fun init(context: Context) {
        Realm.init(context)
        val realmConfiguration = RealmConfiguration.Builder()
                .schemaVersion(SCHEMA_VERSION)
                .migration(migration)
                .initialData(initialData)
                .compactOnLaunch()
                .build()
        Realm.setDefaultConfiguration(realmConfiguration)

        try {
            Realm.getDefaultInstance().use {
                if(it.isEmpty)
                    it.executeTransaction(initialData)
            }
        } catch(e: Exception) {
            Log.e(TAG, "Failed to open realm db", e)
        }
    }

    fun resetDatabase() {
        Log.w(TAG, "Database will be reset")
        Realm.getDefaultInstance().use {
            it.executeTransaction(initialData)
        }
    }

    fun markAboveAsRead(realm: Realm, items: List<Item>?, lastItemId: Long) {
        if(items != null) {
            realm.executeTransaction { realm1: Realm ->
                try {
                    for (item in items) {
                        item.unread = false
                        if (item.id == lastItemId) {
                            break
                        }
                    }
                } finally {
                    checkAlarm(realm1)
                }
            }
        }
    }

    fun markTemporaryFeedAsRead(realm: Realm, onSuccess: OnSuccess?, onError: Realm.Transaction.OnError?) {
        realm.executeTransactionAsync(Realm.Transaction { realm1: Realm ->
            try {
                val unreadItems = getListTemporaryFeed(realm1)
                        ?.items
                        ?.where()
                        ?.equalTo(Item::unread.name, true)
                        ?.findAll()
                if(unreadItems != null)
                    for (item in unreadItems) {
                        item.unread = false
                    }
            } finally {
                checkAlarm(realm1)
            }
        }, onSuccess, onError)
    }

    @Synchronized
    private fun checkAlarm(realm: Realm) {
        val changedItemsCount = realm.where<Item>()
                .equalTo(Item::unreadChanged.name, true)
                .or()
                .equalTo(Item::starredChanged.name, true).count()
        if (changedItemsCount > 0) {
            TODO("Add job")
        } else {
            TODO("cancel job")
        }
    }
}