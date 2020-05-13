/*
 * Copyright © 2020. Daniel Schaal <daniel@schaal.email>
 *
 * This file is part of ocreader.
 *
 * ocreader is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * ocreader is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Foobar.  If not, see <http://www.gnu.org/licenses/>.
 */

package email.schaal.ocreader.api

import android.content.Context
import android.util.Log
import androidx.preference.PreferenceManager
import com.github.zafarkhaja.semver.Version
import com.squareup.moshi.Moshi
import email.schaal.ocreader.Preferences
import email.schaal.ocreader.R
import email.schaal.ocreader.api.json.*
import email.schaal.ocreader.api.json.v12.ItemIds
import email.schaal.ocreader.api.json.v12.ItemMap
import email.schaal.ocreader.database.model.*
import email.schaal.ocreader.http.HttpManager
import email.schaal.ocreader.service.SyncType
import io.realm.Realm
import io.realm.kotlin.where
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import retrofit2.http.*

class API {
    companion object {
        private const val TAG = "API"

        const val BATCH_SIZE = 100L
        const val API_ROOT = "./index.php/apps/news/api/"

        val MIN_VERSION: Version = Version.forIntegers(8, 8, 2)

        val moshi: Moshi = Moshi.Builder()
                .add(FeedJsonTypeAdapter())
                .add(ItemJsonTypeAdapter())
                .add(DateTypeAdapter())
                .add(UserJsonTypeAdapter())
                .add(VersionTypeAdapter())
                .build()
        val converterFactory: MoshiConverterFactory = MoshiConverterFactory.create(moshi)

        var instance: API? = null

        suspend fun login(context: Context, baseUrl: HttpUrl, username: String, apptoken: String): Status? {
            val httpManager = HttpManager(username, apptoken, baseUrl)
            val resolvedBaseUrl = baseUrl.resolve("") ?: throw IllegalArgumentException("Couldn't parse URL")

            val moshi = Moshi.Builder().build()

            val retrofit = Retrofit.Builder()
                    .baseUrl(resolvedBaseUrl)
                    .client(httpManager.client)
                    .addConverterFactory(MoshiConverterFactory.create(moshi))
                    .build()

            val commonAPI = retrofit.create(CommonAPI::class.java)
            val response = commonAPI.apiLevels()
            if(response.isSuccessful) {
                val apiLevels = response.body()
                val apiLevel = apiLevels?.highestSupportedApi() ?: throw IllegalArgumentException(context.getString(R.string.error_not_compatible))
                val loginInstance = Level.getAPI(context, apiLevel, httpManager)
                val status = loginInstance.metaData()
                val version = status?.version
                if(version != null && MIN_VERSION.lessThanOrEqualTo(version)) {
                    PreferenceManager.getDefaultSharedPreferences(context).edit()
                            .putString(Preferences.USERNAME.key, username)
                            .putString(Preferences.APPTOKEN.key, apptoken)
                            .putString(Preferences.URL.key, resolvedBaseUrl.toString())
                            .putString(Preferences.SYS_DETECTED_API_LEVEL.key, apiLevel.level)
                            .apply()
                    instance = loginInstance
                    return status
                }
            }
            instance = null
            return null
        }
    }

    enum class MarkAction(val key: String, val changedKey: String, val value: Boolean) {
        MARK_READ(Item.UNREAD, Item::unreadChanged.name, false),
        MARK_UNREAD(Item.UNREAD, Item::unreadChanged.name, true),
        MARK_STARRED(Item.STARRED, Item::starredChanged.name, true),
        MARK_UNSTARRED(Item.STARRED, Item::starredChanged.name, false);
    }

    private interface APIv12Interface {
        /* SERVER **/
        /** Since 6.0.5  */
        @GET("user")
        suspend fun user(): User

        @GET("status")
        suspend fun status(): Status

        /** FOLDERS  */
        @GET("folders")
        suspend fun folders(): Folders

        /** FEEDS  */
        @GET("feeds")
        suspend fun feeds(): Feeds

        @POST("feeds")
        suspend fun createFeed(@Body feedMap: Map<String, @JvmSuppressWildcards Any>): Feeds

        @PUT("feeds/{feedId}/move")
        suspend fun moveFeed(@Path("feedId") feedId: Long, @Body folderIdMap: Map<String, Long>): Response<Void>

        @DELETE("feeds/{feedId}")
        suspend fun deleteFeed(@Path("feedId") feedId: Long): Response<Void>

        /** ITEMS  */
        @GET("items")
        suspend fun items(
                @Query("batchSize") batchSize: Long,
                @Query("offset") offset: Long,
                @Query("type") type: Int,
                @Query("id") id: Long,
                @Query("getRead") getRead: Boolean,
                @Query("oldestFirst") oldestFirst: Boolean
        ): Items

        @GET("items/updated")
        suspend fun updatedItems(
                @Query("lastModified") lastModified: Long,
                @Query("type") type: Int,
                @Query("id") id: Long
        ): Items

        @PUT("items/read/multiple")
        suspend fun markItemsRead(@Body items: ItemIds): Response<Void>

        @PUT("items/unread/multiple")
        suspend fun markItemsUnread(@Body items: ItemIds): Response<Void>

        @PUT("items/star/multiple")
        suspend fun markItemsStarred(@Body itemMap: ItemMap): Response<Void>

        @PUT("items/unstar/multiple")
        suspend fun markItemsUnstarred(@Body itemMap: ItemMap): Response<Void>
    }

    private val api: APIv12Interface?

    constructor(context: Context, httpManager: HttpManager) {
        api = setupApi(httpManager)
    }

    constructor(context: Context) {
        val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(context)
        val username = Preferences.USERNAME.getString(sharedPreferences)
        val apptoken = Preferences.APPTOKEN.getString(sharedPreferences)
        val url = Preferences.URL.getString(sharedPreferences)?.toHttpUrlOrNull()

        if(username == null || apptoken == null || url == null)
            throw IllegalStateException()

        val httpManager = HttpManager(username, apptoken, url)

        api = setupApi(httpManager)
    }

    private fun setupApi(httpManager: HttpManager): APIv12Interface {
        val retrofit = Retrofit.Builder()
                .baseUrl(httpManager.credentials.rootUrl.resolve(String.format("%s%s/", API_ROOT, Level.V12.level))!!)
                .client(httpManager.client)
                .addConverterFactory(converterFactory)
                .build()

        return retrofit.create(APIv12Interface::class.java)
    }

    private fun syncChanges(realm: Realm): Flow<Pair<MarkAction, List<Item>?>> = flow {
        for(markAction in MarkAction.values())
            emit(markAction to markItems(markAction, realm))
    }

    private suspend fun markItems(action: MarkAction, realm: Realm): List<Item>? {
        val results = realm.where<Item>()
                .equalTo(action.changedKey, true)
                .equalTo(action.key, action.value)
                .findAll()

        if(results.isEmpty())
            return null

        val response: Response<Void>? = when(action) {
            MarkAction.MARK_READ -> {
                api?.markItemsRead(ItemIds(results))
            }
            MarkAction.MARK_UNREAD -> {
                api?.markItemsUnread(ItemIds(results))
            }
            MarkAction.MARK_STARRED -> {
                api?.markItemsStarred(ItemMap(results))
            }
            MarkAction.MARK_UNSTARRED -> {
                api?.markItemsUnstarred(ItemMap(results))
            }
        }

        return if (response?.isSuccessful == true) results else throw IllegalStateException("Marking items failed")
    }

    private enum class QueryType(val type: Int) {
        FEED(0),
        FOLDER(1),
        STARRED(2),
        ALL(3)
    }

    private suspend fun batchedItemLoad(collector: FlowCollector<List<Insertable>>, queryType: QueryType, getRead: Boolean = false) {
        var offset = 0L
        do {
            val resultCount = api?.items(BATCH_SIZE, offset, queryType.type, 0L, getRead = getRead, oldestFirst = true)?.items?.let {
                collector.emit(it)
                offset = it.firstOrNull()?.id ?: 0L
                it.size.toLong()
            }
            Log.d(TAG, "offset: $offset, resultCount: $resultCount")
        } while(resultCount == BATCH_SIZE)
    }

    suspend fun sync(syncType: SyncType) {
        Log.d(TAG, "Sync started: ${syncType.action}")
        Realm.getDefaultInstance().use { realm ->
            val result = syncChanges(realm)
            when(syncType) {
                SyncType.SYNC_CHANGES_ONLY -> {
                    realm.executeTransaction {
                        resetItemChanged(result)
                    }
                }
                SyncType.FULL_SYNC -> {
                    val lastSync = realm.where<Item>().maximumDate(Item::lastModified.name)?.time?.let { it / 1000L } ?: 0L

                    val folders = api?.folders()?.folders
                    val feeds = api?.feeds()?.feeds

                    val insertFlow = flow<List<Insertable>> {
                        api?.user()?.let { emit(listOf(it))}

                        if(lastSync == 0L) {
                            batchedItemLoad(this, QueryType.STARRED, true)
                            batchedItemLoad(this, QueryType.ALL, false)
                        } else {
                            api?.updatedItems(lastSync, QueryType.ALL.type, 0L)?.items?.let { emit(it) }
                        }
                    }

                    realm.beginTransaction()
                        resetItemChanged(result)

                        if(folders != null) {
                            val dbFolders = realm.where<Folder>().findAll()
                            val foldersToDelete = dbFolders.minus(folders)

                            for(folder in folders)
                                folder.insert(realm)

                            for(folder in foldersToDelete)
                                folder.delete(realm)
                        }

                        val dbFeeds = realm.where<Feed>().findAll()

                        if(feeds != null) {
                            val feedsToDelete = dbFeeds.minus(feeds)

                            for(feed in feeds)
                                feed.insert(realm)

                            for(feed in feedsToDelete)
                                feed.delete(realm)
                        }

                        insertFlow.collect {
                            for(insertable in it)
                                insertable.insert(realm)
                        }

                        for (feed in dbFeeds) {
                            feed.starredCount = realm.where<Item>()
                                    .equalTo(Item::feedId.name, feed.id)
                                    .equalTo(Item.STARRED, true).count().toInt()
                            feed.unreadCount = realm.where<Item>()
                                    .equalTo(Item::feedId.name, feed.id)
                                    .equalTo(Item.UNREAD, true).count().toInt()
                        }

                        Item.removeExcessItems(realm, 10000)
                    realm.commitTransaction()

                }
                SyncType.LOAD_MORE -> {

                }
            }
        }
        Log.d(TAG, "Sync finished: ${syncType.action}")
    }

    private fun resetItemChanged(result: Flow<Pair<MarkAction, List<Item>?>>) = runBlocking {
        result.collect { (action, results) ->
            if(results != null) {
                when(action) {
                    MarkAction.MARK_READ, MarkAction.MARK_UNREAD -> {
                        results.forEach { it.unreadChanged = false }
                    }
                    MarkAction.MARK_STARRED, MarkAction.MARK_UNSTARRED -> {
                        results.forEach { it.starredChanged = false }
                    }
                }
            }
        }
    }

    suspend fun createFeed(url: String, folderId: Long) {
        val feeds = api?.createFeed(mapOf("url" to url, "folderId" to folderId))?.feeds

        feeds?.get(0)?.let { feed: Feed ->
            Realm.getDefaultInstance().use { realm ->
                realm.executeTransaction {
                    feed.unreadCount = 0
                    feed.insert(it)
                }
            }
        }
    }

    suspend fun deleteFeed(feed: Feed) {
        val response = api?.deleteFeed(feed.id)
        if(response?.isSuccessful == true) {
            Realm.getDefaultInstance().use { realm ->
                realm.executeTransaction {
                    feed.delete(it)
                }
            }
        }
    }

    suspend fun moveFeed(feedId: Long, folderId: Long) {
        Realm.getDefaultInstance().use { realm ->
            val feed = Feed.get(realm, feedId) ?: return

            val response = api?.moveFeed(feed.id, mapOf("folderId" to folderId))
            if (response?.isSuccessful == true) {
                realm.executeTransaction {
                    feed.folderId = folderId
                    feed.folder = Folder.getOrCreate(it, folderId)
                }
            }
        }
    }

    private interface CommonAPI {
        @GET("index.php/apps/news/api")
        suspend fun apiLevels(): Response<APILevels>
    }

    suspend fun metaData(): Status? {
        return api?.status()
    }

}