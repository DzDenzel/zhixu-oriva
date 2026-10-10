package com.planlock.design

import org.junit.Assert.*
import org.junit.Test
import java.util.TimeZone
import java.util.GregorianCalendar
import java.util.Locale
import java.time.LocalDate
import java.time.ZoneOffset

class DatePickerValuesTest {
    @Test fun `calendar days round trip under positive negative and daylight saving offsets`() {
        val original = TimeZone.getDefault()
        try {
            for (zone in listOf("Asia/Shanghai", "America/Los_Angeles", "Pacific/Kiritimati")) {
                TimeZone.setDefault(TimeZone.getTimeZone(zone))
                for (day in listOf("2024-02-29", "2026-03-08", "2026-11-01", "2026-12-31")) {
                    val selected = checkNotNull(datePickerUtcMillis(day))
                    assertEquals(day, datePickerIsoDate(selected))
                    assertEquals(0L, selected % 86_400_000L)
                }
            }
        } finally { TimeZone.setDefault(original) }
    }
    @Test fun `unresolved or invalid dates remain unselected rather than becoming today`() {
        for (value in listOf("", "明天", "2025-02-29", "2026-13-01", "2026-10-09junk"))
            assertNull(datePickerUtcMillis(value))
    }
    @Test fun `modern Material proleptic days interoperate across full picker year range`() {
        for (day in listOf("0001-01-01", "1500-01-01", "1582-10-04", "1582-10-10", "1582-10-15", "9999-12-31")) {
            val materialUtc = LocalDate.parse(day).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
            assertEquals("Material selection for $day", materialUtc, datePickerUtcMillis(day))
            assertEquals("Saved ISO day for $day", day, datePickerIsoDate(materialUtc))
        }
        assertNull(datePickerUtcMillis("1500-02-29"))
    }

    @Test fun `legacy Material Calendar selections keep their displayed date on API 24 and 25`() {
        for (day in listOf("0001-01-01", "1500-01-01", "1582-10-04", "1582-10-15", "9999-12-31")) {
            val parts = day.split('-').map(String::toInt)
            val materialUtc = GregorianCalendar(TimeZone.getTimeZone("UTC"), Locale.US).apply {
                clear()
                isLenient = false
                set(parts[0], parts[1] - 1, parts[2], 0, 0, 0)
            }.timeInMillis
            assertEquals(materialUtc, datePickerUtcMillis(day, prolepticGregorian = false))
            assertEquals(day, datePickerIsoDate(materialUtc, prolepticGregorian = false))
        }
        // These days do not exist in Material's legacy cutover calendar: keep unselected.
        assertNull(datePickerUtcMillis("1582-10-10", prolepticGregorian = false))
        assertNotNull(datePickerUtcMillis("1500-02-29", prolepticGregorian = false))
    }

}
