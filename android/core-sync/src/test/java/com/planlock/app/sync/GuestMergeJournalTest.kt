package com.planlock.app.sync

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class GuestMergeJournalTest {
    @Test fun pendingChoiceSurvivesRestartAndCannotAppearForAnotherAccount() = runBlocking {
        val disk = object : DataStore<Preferences> {
            override val data = MutableStateFlow<Preferences>(emptyPreferences())
            override suspend fun updateData(transform: suspend (Preferences) -> Preferences) = transform(data.value).also { data.value = it }
        }
        val offer = GuestMergeOffer(listOf("local\nlocal", "instance\n7"), 7, 3, "instance\n9")
        GuestMergeJournal(disk).save(offer)
        assertEquals(offer, GuestMergeJournal(disk).read("instance\n9"))
        assertNull(GuestMergeJournal(disk).read("instance\n10"))
        GuestMergeJournal(disk).clear("instance\n9")
        assertNull(GuestMergeJournal(disk).read("instance\n9"))
    }
}
