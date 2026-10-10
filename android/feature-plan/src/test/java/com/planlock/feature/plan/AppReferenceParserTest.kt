package com.planlock.feature.plan

import org.junit.Assert.assertEquals
import org.junit.Test

class AppReferenceParserTest {
    @Test
    fun jsonStrategyEntriesKeepPackageIdentityForEngineDecisions() {
        val entries = parseAppReferences(
            """[{"name":"YouTube","packageName":"com.google.android.youtube"},{"name":"Chrome","packageName":"com.android.chrome"}]""",
        )

        assertEquals(
            listOf(
                AppReference("com.google.android.youtube", "YouTube"),
                AppReference("com.android.chrome", "Chrome"),
            ),
            entries,
        )
    }

    @Test
    fun legacyNameOnlyEntriesRemainVisibleAndAddressable() {
        assertEquals(
            listOf(AppReference("YouTube", "YouTube"), AppReference("Chrome", "Chrome")),
            parseAppReferences("[YouTube, Chrome]"),
        )
    }
}
