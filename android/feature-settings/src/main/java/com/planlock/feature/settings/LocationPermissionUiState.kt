package com.planlock.feature.settings

internal enum class LocationPermissionUiState {
    ASK,
    ASK_AGAIN,
    APP_SETTINGS,
    ENABLED,
}

internal fun locationPermissionUiState(
    granted: Boolean,
    requestedBefore: Boolean,
    shouldShowRationale: Boolean,
): LocationPermissionUiState = when {
    granted -> LocationPermissionUiState.ENABLED
    !requestedBefore -> LocationPermissionUiState.ASK
    shouldShowRationale -> LocationPermissionUiState.ASK_AGAIN
    else -> LocationPermissionUiState.APP_SETTINGS
}
