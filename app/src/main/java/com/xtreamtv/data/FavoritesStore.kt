package com.xtreamtv.data

import android.content.Context

/**
 * Lightweight persistent tracker of favorited live-channel stream IDs.
 */
class FavoritesStore(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)

    private val ids: MutableSet<Int> =
        (prefs.getStringSet(KEY_IDS, emptySet()) ?: emptySet())
            .mapNotNull { it.toIntOrNull() }
            .toMutableSet()

    fun isFavorite(streamId: Int): Boolean = streamId in ids

    /** Toggles the channel and returns true when it is now a favorite. */
    fun toggle(streamId: Int): Boolean {
        val nowFavorite = if (streamId in ids) {
            ids.remove(streamId)
            false
        } else {
            ids.add(streamId)
            true
        }
        persist()
        return nowFavorite
    }

    fun all(): Set<Int> = ids.toSet()

    private fun persist() {
        prefs.edit().putStringSet(KEY_IDS, ids.map(Int::toString).toSet()).apply()
    }

    companion object {
        private const val PREFS_FILE = "xtream_favorites"
        private const val KEY_IDS = "live_stream_ids"
    }
}
