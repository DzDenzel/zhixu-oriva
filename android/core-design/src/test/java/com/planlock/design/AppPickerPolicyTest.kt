package com.planlock.design

import org.junit.Assert.assertEquals
import org.junit.Test

class AppPickerPolicyTest {
    private val apps = listOf(
        AppOption("social-b", "聊天 B", "社交"),
        AppOption("category:社交", "社交类应用", "社交"),
        AppOption("game-a", "游戏 A", "游戏"),
        AppOption("social-a", "聊天 A", "社交"),
    )

    @Test
    fun categoryBulkSelectionIncludesEveryInstalledAppInThatCategory() {
        assertEquals(listOf("social-b", "social-a"), bulkSelection(apps, "社交").map { it.id })
    }

    @Test
    fun globalBulkSelectionIncludesEveryVisibleInstalledApp() {
        assertEquals(listOf("social-b", "game-a", "social-a"), bulkSelection(apps, null).map { it.id })
    }

    @Test
    fun partiallySelectedCategoryProducesSelectActionForThatCategoryOnly() {
        val action = bulkSelectionAction(apps, "社交", setOf("social-b", "game-a"))

        assertEquals(true, action.select)
        assertEquals(listOf("social-b", "social-a"), action.apps.map { it.id })
    }

    @Test
    fun fullySelectedCategoryProducesDeselectActionForThatCategoryOnly() {
        val action = bulkSelectionAction(apps, "社交", setOf("social-b", "social-a", "game-a"))

        assertEquals(false, action.select)
        assertEquals(listOf("social-b", "social-a"), action.apps.map { it.id })
    }

    @Test
    fun fullySelectedGlobalScopeProducesDeselectActionForAllInstalledApps() {
        val action = bulkSelectionAction(apps, null, setOf("social-b", "social-a", "game-a"))

        assertEquals(false, action.select)
        assertEquals(listOf("social-b", "game-a", "social-a"), action.apps.map { it.id })
    }

    @Test
    fun emptyCategoryHasNoEnabledBulkAction() {
        val action = bulkSelectionAction(apps, "影音", emptySet())

        assertEquals(true, action.select)
        assertEquals(emptyList<String>(), action.apps.map { it.id })
    }
}
