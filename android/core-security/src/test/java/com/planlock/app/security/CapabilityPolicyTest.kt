package com.planlock.app.security

import org.junit.Assert.*
import org.junit.Test

class CapabilityPolicyTest {
    @Test fun punctualStartRequiresExactEvenWithoutAReminder() {
        assertEquals(setOf(Capability.EXACT_ALARM), CapabilityRequirements.plan(false, false, false, punctualStart = true))
        assertTrue(CapabilityRequirements.plan(false, false, false, punctualStart = false).isEmpty())
    }
    @Test fun silentNotificationsAndDndRequireDistinctGrants() {
        val required = CapabilityRequirements.plan(autoDnd = true, notifySilence = true, reminder = false)
        assertEquals(setOf(Capability.DND, Capability.NOTIFICATION_SILENCE), required)
        assertEquals(setOf(Capability.NOTIFICATION_SILENCE), CapabilityRequirements.missing(required, setOf(Capability.DND)))
    }
    @Test fun reminderRequiresPostingAndExactAlarmBeforeSave() {
        assertEquals(setOf(Capability.NOTIFICATIONS, Capability.EXACT_ALARM), CapabilityRequirements.reminder(true))
        assertTrue(CapabilityRequirements.reminder(false).isEmpty())
    }
    @Test fun revokedPermissionIsNeverDescribedAsEffective() {
        assertEquals("未配置", capabilityEffectText(null, false))
        assertEquals("未开启", capabilityEffectText(false, false))
        assertEquals("未生效：未授权", capabilityEffectText(true, false))
        assertEquals("已开启", capabilityEffectText(true, true))
    }
}
