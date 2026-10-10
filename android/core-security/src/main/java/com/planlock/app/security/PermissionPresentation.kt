package com.planlock.app.security

fun basePermissionKinds(): List<PermissionKind> = PermissionKind.entries.filterNot { it == PermissionKind.ACCESSIBILITY }

enum class ObservationAvailability { PRECISE, APPROXIMATE, UNAVAILABLE }

fun observationAvailability(health: AccessibilityHealth, usageStatsGranted: Boolean): ObservationAvailability = when {
    health == AccessibilityHealth.HEALTHY -> ObservationAvailability.PRECISE
    usageStatsGranted -> ObservationAvailability.APPROXIMATE
    else -> ObservationAvailability.UNAVAILABLE
}
