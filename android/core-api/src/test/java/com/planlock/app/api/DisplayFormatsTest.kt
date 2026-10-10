package com.planlock.app.api

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

class DisplayFormatsTest {
    @Test fun boundariesKeepWholeSecondsAndNeverChangeTheInput() {
        listOf(-1L to "0:00:00", 0L to "0:00:00", 999L to "0:00:00", 61_999L to "0:01:01",
            3_599_999L to "0:59:59", 3_600_000L to "1:00:00", 25_201_000L to "7:00:01")
            .forEach { (millis, label) -> assertEquals(label, durationDisplayLabel(millis)) }
    }
    @Test fun formattingDoesNotDependOnDeviceNumeralLocale() {
        val previous = Locale.getDefault()
        try {
            listOf(Locale.CHINA, Locale.US, Locale.forLanguageTag("ar-EG")).forEach {
                Locale.setDefault(it)
                assertEquals("7:00:01", durationDisplayLabel(25_201_000L))
            }
        } finally { Locale.setDefault(previous) }
    }
}
