package com.planlock.app.sync

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

/** Choice/proof durability is separate from the current login; retaining guests retains their proof. */
class GuestMergeJournal(private val preferences: DataStore<Preferences>) {
    private val json = Json { ignoreUnknownKeys = true }
    private fun key(scope: String) = stringPreferencesKey("pending_guest_merge:$scope")
    private fun proofKey(scope: String) = stringPreferencesKey("guest_merge_source_proof:$scope")
    suspend fun save(offer: GuestMergeOffer) { preferences.edit { it[key(offer.accountScope)] = json.encodeToString(offer) } }
    suspend fun read(scope: String): GuestMergeOffer? = preferences.data.first()[key(scope)]?.let {
        runCatching { json.decodeFromString<GuestMergeOffer>(it) }.getOrNull()?.takeIf { offer -> offer.accountScope == scope }
    }
    suspend fun clear(scope: String) { preferences.edit { it.remove(key(scope)) } }
    suspend fun saveProof(scope: String, token: String) { preferences.edit { it[proofKey(scope)] = token } }
    suspend fun proof(scope: String): String? = preferences.data.first()[proofKey(scope)]
}
