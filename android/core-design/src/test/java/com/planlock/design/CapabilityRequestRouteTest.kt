package com.planlock.design

import org.junit.Assert.assertEquals
import org.junit.Test

class CapabilityRequestRouteTest {
    @Test fun firstRequestDoesNotNeedAnAppExplanationBeforeTheSystemDialog() {
        assertEquals(CapabilityRequestRoute.SYSTEM_DIALOG, capabilityRequestRoute(true, false, false))
    }
    @Test fun oneDenialStillUsesTheSystemWhenItAllowsAnotherRequest() {
        assertEquals(CapabilityRequestRoute.SYSTEM_DIALOG, capabilityRequestRoute(true, true, true))
    }
    @Test fun permanentDenialRequiresTheSettingsExplanation() {
        assertEquals(CapabilityRequestRoute.SETTINGS_EXPLANATION, capabilityRequestRoute(true, true, false))
    }
    @Test fun specialPermissionsAndGrantedLocationWithDisabledSystemSwitchUseSettings() {
        for (asked in listOf(false, true)) for (again in listOf(false, true))
            assertEquals(CapabilityRequestRoute.SETTINGS_EXPLANATION, capabilityRequestRoute(false, asked, again))
    }
}
