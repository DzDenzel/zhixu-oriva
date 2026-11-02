package com.planlock.app.security

import android.app.Notification
import android.app.NotificationManager
import com.planlock.app.api.AlarmDeferral
import com.planlock.app.api.AlarmKind
import com.planlock.app.api.ReadinessKey
import com.planlock.app.api.ReadinessState
import org.junit.Assert.assertEquals
import org.junit.Test

class ReminderReadinessProbeTest {
    @Test fun booleanFactsKeepUnknownSeparateFromDenied() {
        assertEquals(ReadinessState.ON, readinessState(true))
        assertEquals(ReadinessState.OFF, readinessState(false))
        assertEquals(ReadinessState.UNKNOWN, readinessState(null))
    }

    @Test fun appOwnedSwitchesUsePersistedOrProvidedValuesWithoutPermissionInference() {
        for (key in listOf(ReadinessKey.PUNCTUAL_START, ReadinessKey.CALENDAR_FALLBACK)) {
            assertEquals(key.name, ReadinessState.ON, readinessState(true))
            assertEquals(key.name, ReadinessState.OFF, readinessState(false))
        }
    }

    @Test fun fullScreenBeforeAndroid14IsAvailableWithoutASettingsGrant() {
        for (sdk in listOf(24, 31, 33)) {
            for (allowed in listOf(true, false, null)) {
                assertEquals(ReadinessState.ON, fullScreenReadiness(sdk, allowed))
            }
        }
    }

    @Test fun fullScreenOnAndroid14AndLaterUsesThePublicFact() {
        for (sdk in listOf(34, 35, 36, 37)) {
            assertEquals(ReadinessState.ON, fullScreenReadiness(sdk, true))
            assertEquals(ReadinessState.OFF, fullScreenReadiness(sdk, false))
            assertEquals(ReadinessState.UNKNOWN, fullScreenReadiness(sdk, null))
        }
    }

    @Test fun promotionBeforeAndroid16IsUnknownNotDenied() {
        for (sdk in listOf(24, 33, 34, 35)) {
            for (allowed in listOf(true, false, null)) {
                assertEquals(ReadinessState.UNKNOWN, promotedReadiness(sdk, allowed))
            }
        }
    }

    @Test fun promotionOnAndroid16AndLaterAllowsUnavailableSystemFeature() {
        for (sdk in listOf(36, 37)) {
            assertEquals(ReadinessState.ON, promotedReadiness(sdk, true))
            assertEquals(ReadinessState.OFF, promotedReadiness(sdk, false))
            assertEquals(ReadinessState.UNKNOWN, promotedReadiness(sdk, null))
        }
    }

    @Test fun backgroundRestrictionIsTheOnlyOffEvidenceAndTakesPrecedence() {
        for (exempt in listOf(true, false, null)) {
            assertEquals(ReadinessState.OFF, backgroundReadiness(exempt, true))
        }
    }

    @Test fun batteryExemptionWithoutKnownRestrictionIsOn() {
        assertEquals(ReadinessState.ON, backgroundReadiness(true, false))
        assertEquals(ReadinessState.ON, backgroundReadiness(true, null))
    }

    @Test fun nonExemptBackgroundIsUnknownEvenWhenStandardRestrictionIsOff() {
        for (exempt in listOf(false, null)) {
            for (restricted in listOf(false, null)) {
                assertEquals(ReadinessState.UNKNOWN, backgroundReadiness(exempt, restricted))
            }
        }
    }

    private val alertChannel = ReminderChannelReadiness(
        importance = NotificationManager.IMPORTANCE_HIGH,
        lockscreenVisibility = Notification.VISIBILITY_PUBLIC,
        groupBlocked = false,
        soundPresent = true,
        minimumImportance = NotificationManager.IMPORTANCE_HIGH,
        needsChannelSound = true,
    )

    private fun vendor(vararg channels: ReminderChannelReadiness) =
        vendorNotificationReadiness(channels.toList())

    @Test fun enabledChannelsCannotProveVendorAppLevelSwitchesAreOn() {
        assertEquals(ReadinessState.UNKNOWN, vendor(alertChannel))
        assertEquals(ReadinessState.UNKNOWN, vendor(alertChannel.copy(lockscreenVisibility = Notification.VISIBILITY_PRIVATE)))
    }

    @Test fun missingChannelsAndUnspecifiedChannelFieldsAreUnknownNotOff() {
        assertEquals(ReadinessState.UNKNOWN, vendor())
        assertEquals(ReadinessState.UNKNOWN, vendor(alertChannel.copy(
            importance = null, lockscreenVisibility = null, groupBlocked = null, soundPresent = null,
        )))
        assertEquals(ReadinessState.UNKNOWN, vendor(alertChannel.copy(
            importance = NotificationManager.IMPORTANCE_UNSPECIFIED,
            lockscreenVisibility = NotificationManager.IMPORTANCE_UNSPECIFIED,
        )))
    }

    @Test fun blockedChannelOrGroupIsOffEvidence() {
        assertEquals(ReadinessState.OFF, vendor(alertChannel.copy(importance = NotificationManager.IMPORTANCE_NONE)))
        assertEquals(ReadinessState.OFF, vendor(alertChannel.copy(groupBlocked = true)))
    }

    @Test fun loweredWaitingOrStartImportanceIsOffEvidence() {
        for (importance in listOf(
            NotificationManager.IMPORTANCE_MIN,
            NotificationManager.IMPORTANCE_LOW,
            NotificationManager.IMPORTANCE_DEFAULT,
        )) {
            assertEquals(ReadinessState.OFF, vendor(alertChannel.copy(importance = importance)))
        }
    }

    @Test fun completionDoesNotRequireBannerImportance() {
        val completion = alertChannel.copy(minimumImportance = NotificationManager.IMPORTANCE_DEFAULT)
        assertEquals(ReadinessState.UNKNOWN, vendor(completion.copy(importance = NotificationManager.IMPORTANCE_DEFAULT)))
        assertEquals(ReadinessState.OFF, vendor(completion.copy(importance = NotificationManager.IMPORTANCE_LOW)))
    }

    @Test fun hiddenLockscreenOrMutedAudibleChannelIsOffEvidence() {
        assertEquals(ReadinessState.OFF, vendor(alertChannel.copy(lockscreenVisibility = Notification.VISIBILITY_SECRET)))
        assertEquals(ReadinessState.OFF, vendor(alertChannel.copy(soundPresent = false)))
    }

    @Test fun silentWaitingCapsuleDoesNotNeedChannelSound() {
        val waiting = alertChannel.copy(
            needsChannelSound = readinessChannelNeedsSound(PlanReminderChannel.ID), soundPresent = false,
        )
        assertEquals(ReadinessState.UNKNOWN, vendor(waiting))
        assertEquals(ReadinessState.OFF, vendor(waiting.copy(groupBlocked = true)))
    }

    @Test fun alarmAndCompletionRespectMutedChannelsIncludingApplicationPlayback() {
        for (id in listOf(PlanReminderChannel.START_ID, PlanReminderChannel.COMPLETION_ID)) {
            val channel = alertChannel.copy(needsChannelSound = readinessChannelNeedsSound(id), soundPresent = false)
            assertEquals(id, ReadinessState.OFF, vendor(channel))
        }
    }

    @Test fun oneRestrictedChannelIsNotHiddenByOtherEnabledChannels() {
        assertEquals(ReadinessState.OFF, vendor(alertChannel, alertChannel.copy(groupBlocked = true), alertChannel))
    }

    @Test fun deferralCarriesKindAndSignedEpochMillisecondDifferenceWithoutRounding() {
        for (kind in AlarmKind.entries) {
            for (lateMs in listOf(-1L, 0L, 59_999L, 60_000L, 60_001L)) {
                val deferral = AlarmDeferral(kind, 42L, "专注阅读", 1_000_000L, 1_000_000L + lateMs)
                assertEquals(kind, deferral.kind)
                assertEquals(lateMs, deferral.lateMs)
            }
        }
    }
}
