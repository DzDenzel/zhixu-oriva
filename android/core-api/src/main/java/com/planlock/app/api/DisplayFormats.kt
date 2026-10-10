package com.planlock.app.api

import java.util.Locale

/** A display-only projection shared by reports and local assistant replies. */
fun durationDisplayLabel(millis: Long): String {
    val seconds = millis.coerceAtLeast(0) / 1_000
    return "%d:%02d:%02d".format(Locale.ROOT, seconds / 3_600, seconds / 60 % 60, seconds % 60)
}
