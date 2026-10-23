package com.planlock.design

internal enum class CapabilityRequestRoute { SYSTEM_DIALOG, SETTINGS_EXPLANATION }

/** A prior denial alone does not mean Android has stopped allowing runtime requests. */
internal fun capabilityRequestRoute(hasMissingRuntimePermission: Boolean, requestedBefore: Boolean, canAskAgain: Boolean): CapabilityRequestRoute =
    if (hasMissingRuntimePermission && (!requestedBefore || canAskAgain)) CapabilityRequestRoute.SYSTEM_DIALOG
    else CapabilityRequestRoute.SETTINGS_EXPLANATION
