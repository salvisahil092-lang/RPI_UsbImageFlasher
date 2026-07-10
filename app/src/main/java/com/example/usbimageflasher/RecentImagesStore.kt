package com.example.usbimageflasher

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject

data class RecentImage(
    val uri: String,
    val name: String,
    val sizeBytes: Long,
    val lastUsed: Long,
    val favorite: Boolean
)

/** Persists a small list of recently used / favorited image files across app restarts. */
class RecentImagesStore(context: Context) {
    private val prefs = context.getSharedPreferences("recent_images", Context.MODE_PRIVATE)
    private val maxNonFavorites = 8

    fun getAll(): List<RecentImage> {
        val raw = prefs.getString("entries", null) ?: return emptyList()
        val arr = JSONArray(raw)
        val list = mutableListOf<RecentImage>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            list.add(
                RecentImage(
                    uri = o.getString("uri"),
                    name = o.getString("name"),
                    sizeBytes = o.getLong("size"),
                    lastUsed = o.getLong("lastUsed"),
                    favorite = o.optBoolean("favorite", false)
                )
            )
        }
        return list.sortedWith(
            compareByDescending<RecentImage> { it.favorite }.thenByDescending { it.lastUsed }
        )
    }

    fun addOrUpdate(uri: Uri, name: String, sizeBytes: Long) {
        val current = getAll().toMutableList()
        val uriStr = uri.toString()
        val existing = current.find { it.uri == uriStr }
        current.removeAll { it.uri == uriStr }
        current.add(
            RecentImage(
                uri = uriStr,
                name = name,
                sizeBytes = sizeBytes,
                lastUsed = System.currentTimeMillis(),
                favorite = existing?.favorite ?: false
            )
        )
        trimAndSave(current)
    }

    fun toggleFavorite(uriStr: String) {
        val current = getAll().toMutableList()
        val idx = current.indexOfFirst { it.uri == uriStr }
        if (idx >= 0) {
            current[idx] = current[idx].copy(favorite = !current[idx].favorite)
        }
        trimAndSave(current)
    }

    fun remove(uriStr: String) {
        val current = getAll().toMutableList()
        current.removeAll { it.uri == uriStr }
        trimAndSave(current)
    }

    private fun trimAndSave(list: MutableList<RecentImage>) {
        val favorites = list.filter { it.favorite }
        val nonFavorites = list.filter { !it.favorite }
            .sortedByDescending { it.lastUsed }
            .take(maxNonFavorites)
        val finalList = (favorites + nonFavorites).sortedWith(
            compareByDescending<RecentImage> { it.favorite }.thenByDescending { it.lastUsed }
        )
        val arr = JSONArray()
        for (item in finalList) {
            val o = JSONObject()
            o.put("uri", item.uri)
            o.put("name", item.name)
            o.put("size", item.sizeBytes)
            o.put("lastUsed", item.lastUsed)
            o.put("favorite", item.favorite)
            arr.put(o)
        }
        prefs.edit().putString("entries", arr.toString()).apply()
    }
}
