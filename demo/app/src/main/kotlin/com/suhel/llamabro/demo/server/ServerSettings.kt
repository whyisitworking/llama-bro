package com.suhel.llamabro.demo.server

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Server mode's stored values, in the app's settings [store] beside the rest (docs/design/server.md §3). */
class ServerSettings(private val store: DataStore<Preferences>) {
    /** The server's key; null until the first start makes one. */
    val key: Flow<String?> = store.data.map { it[KEY] }

    /**
     * The stored key while it is [valid], else [make]'s, stored in the same edit: two first reads at once agree on one
     * key, and a key in an old format is replaced once.
     */
    suspend fun keyOr(valid: (String) -> Boolean, make: () -> String): String =
        checkNotNull(store.edit { prefs -> if (prefs[KEY]?.let(valid) != true) prefs[KEY] = make() }[KEY])

    suspend fun setKey(key: String) {
        store.edit { it[KEY] = key }
    }

    private companion object {
        val KEY = stringPreferencesKey("server_key")
    }
}
