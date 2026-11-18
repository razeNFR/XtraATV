package com.github.andreyasadchy.xtra.ui.tv

import android.content.Context
import androidx.core.content.edit
import com.github.andreyasadchy.xtra.model.ui.Stream
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray

/**
 * TV: favorite channels among the followed ones. When they are live they come first in the followed streams
 * (home row, Followed page, other streams of the player), in their own order, then the others by viewers.
 * Kept on this device, in their own preferences file.
 */
object TvFavorites {

    private const val FILE = "tv_favorites"
    private const val ITEMS = "items"

    class Favorite(val login: String, val name: String?)

    /** The favorites in their order, loaded once with [load]. */
    val items = MutableStateFlow<List<Favorite>>(emptyList())

    private var loadedFile: String? = null

    private fun fileName(context: Context): String = FILE + TvAccounts.storageSuffix(context)

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(fileName(context), Context.MODE_PRIVATE)

    @Synchronized
    fun load(context: Context) {
        val currentFile = fileName(context)
        if (loadedFile == currentFile) {
            return
        }
        loadedFile = currentFile
        val array = JSONArray(prefs(context).getString(ITEMS, null) ?: "[]")
        items.value = (0 until array.length()).mapNotNull { index ->
            array.optJSONObject(index)?.let { item ->
                item.optString("login").takeIf { it.isNotBlank() }?.let { Favorite(it, item.optString("name").ifBlank { null }) }
            }
        }
    }

    @Synchronized
    fun reload(context: Context) {
        loadedFile = null
        load(context)
    }

    fun isFavorite(login: String?) = login != null && items.value.any { it.login == login }

    @Synchronized
    fun add(context: Context, login: String, name: String?) {
        load(context)
        if (!isFavorite(login)) {
            save(context, items.value + Favorite(login, name))
        }
    }

    @Synchronized
    fun remove(context: Context, login: String) {
        load(context)
        save(context, items.value.filter { it.login != login })
    }

    /** New order of the favorites (the list of the settings). */
    @Synchronized
    fun reorder(context: Context, list: List<Favorite>) {
        save(context, list)
    }

    private fun save(context: Context, list: List<Favorite>) {
        items.value = list
        val array = JSONArray()
        list.forEach { array.put(org.json.JSONObject().put("login", it.login).put("name", it.name ?: "")) }
        prefs(context).edit { putString(ITEMS, array.toString()) }
    }

    /** Followed streams: the favorites live first in their order, then the others as they were (by viewers). */
    fun sort(list: List<Stream>): List<Stream> {
        val order = items.value.map { it.login }
        if (order.isEmpty()) {
            return list
        }
        val (favorites, others) = list.partition { it.channelLogin in order }
        return favorites.sortedBy { order.indexOf(it.channelLogin) } + others
    }
}
