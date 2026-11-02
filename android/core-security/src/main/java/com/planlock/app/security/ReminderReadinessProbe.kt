package com.planlock.app.security

import android.app.ActivityManager
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import com.planlock.app.api.AlarmDeferral
import com.planlock.app.api.PlanTimingPreferences
import com.planlock.app.api.ReadinessKey
import com.planlock.app.api.ReadinessState
import com.planlock.app.api.ReminderReadiness
import com.planlock.app.api.ReminderReadinessItem
import com.planlock.app.api.planLockPreferences
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

/**
 * Reads a fresh device-local snapshot without requesting permissions or creating channels.
 * Call on a worker dispatcher: the synchronous contract waits for the DataStore snapshot.
 * The caller owns lifecycle refresh, calendar state, and the persisted deferral log.
 */
fun probeReminderReadiness(
    context: Context,
    calendarFallbackOn: Boolean,
    lastDeferral: AlarmDeferral?,
): ReminderReadiness {
    val app = context.applicationContext
    val sdk = Build.VERSION.SDK_INT
    val manager = app.getSystemService(NotificationManager::class.java)
    val packageUri = Uri.parse("package:${app.packageName}")
    val appDetails = PermissionIntent.appDetails(app)
    fun settings(intent: Intent): Intent = if (readReadinessFact {
        intent.resolveActivity(app.packageManager) != null
    } == true) intent else appDetails

    val notificationSettings = settings(PermissionIntent.forKind(app, PermissionKind.NOTIFICATIONS))
    val exactAlarmSettings = if (Build.VERSION.SDK_INT >= 31) settings(
        Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, packageUri),
    ) else appDetails
    val fullScreenSettings = if (Build.VERSION.SDK_INT >= 34) settings(
        Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, packageUri),
    ) else appDetails
    val promotedSettings = if (Build.VERSION.SDK_INT >= 36) settings(
        Intent(Settings.ACTION_APP_NOTIFICATION_PROMOTION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, app.packageName),
    ) else appDetails

    val channels = if (Build.VERSION.SDK_INT >= 26) PlanReminderChannel.readinessChannelIds().map { id ->
        val channel = readReadinessFact { manager?.getNotificationChannel(id) }
        ReminderChannelReadiness(
            importance = channel?.importance,
            lockscreenVisibility = channel?.lockscreenVisibility,
            groupBlocked = if (Build.VERSION.SDK_INT >= 28 && channel?.group != null) readReadinessFact {
                manager?.getNotificationChannelGroup(channel.group)?.isBlocked
            } else null,
            soundPresent = channel?.let { it.sound != null },
            minimumImportance = if (id == PlanReminderChannel.COMPLETION_ID) {
                NotificationManager.IMPORTANCE_DEFAULT
            } else NotificationManager.IMPORTANCE_HIGH,
            needsChannelSound = readinessChannelNeedsSound(id),
        )
    } else emptyList()
    val ignoresBatteryOptimizations = readReadinessFact {
        app.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(app.packageName)
    }
    val backgroundRestricted = if (Build.VERSION.SDK_INT >= 28) readReadinessFact {
        app.getSystemService(ActivityManager::class.java)?.isBackgroundRestricted
    } else false
    val punctualStart = readReadinessFact {
        runBlocking(Dispatchers.IO) { PlanTimingPreferences.enabled(app.planLockPreferences).first() }
    }

    fun item(key: ReadinessKey, state: ReadinessState, intent: Intent) =
        ReminderReadinessItem(key, state, intent)

    return ReminderReadiness(
        items = listOf(
            item(ReadinessKey.NOTIFICATIONS, readinessState(readReadinessFact {
                CapabilityAccess.granted(app, Capability.NOTIFICATIONS)
            }), notificationSettings),
            item(ReadinessKey.EXACT_ALARM, readinessState(readReadinessFact {
                CapabilityAccess.granted(app, Capability.EXACT_ALARM)
            }), exactAlarmSettings),
            item(ReadinessKey.FULL_SCREEN, fullScreenReadiness(sdk, if (Build.VERSION.SDK_INT >= 34) readReadinessFact {
                manager?.canUseFullScreenIntent()
            } else null), fullScreenSettings),
            item(ReadinessKey.PROMOTED, promotedReadiness(sdk, if (Build.VERSION.SDK_INT >= 36) readReadinessFact {
                manager?.canPostPromotedNotifications()
            } else null), promotedSettings),
            item(ReadinessKey.VENDOR_NOTIFICATION, vendorNotificationReadiness(channels), appDetails),
            item(ReadinessKey.BACKGROUND, backgroundReadiness(ignoresBatteryOptimizations, backgroundRestricted), appDetails),
            item(ReadinessKey.PUNCTUAL_START, readinessState(punctualStart), appDetails),
            item(ReadinessKey.CALENDAR_FALLBACK, readinessState(calendarFallbackOn), appDetails),
        ),
        lastDeferral = lastDeferral,
    )
}

/** CHANGE-063: the waiting capsule is silent; both alarm phases use START_ID. */
internal fun readinessChannelNeedsSound(id: String): Boolean = id != PlanReminderChannel.ID

internal fun readinessState(enabled: Boolean?): ReadinessState = when (enabled) {
    true -> ReadinessState.ON
    false -> ReadinessState.OFF
    null -> ReadinessState.UNKNOWN
}

internal fun fullScreenReadiness(sdk: Int, allowed: Boolean?): ReadinessState =
    if (sdk < 34) ReadinessState.ON else readinessState(allowed)

internal fun promotedReadiness(sdk: Int, allowed: Boolean?): ReadinessState =
    if (sdk < 36) ReadinessState.UNKNOWN else readinessState(allowed)

internal fun backgroundReadiness(
    ignoresBatteryOptimizations: Boolean?,
    backgroundRestricted: Boolean?,
): ReadinessState = when {
    backgroundRestricted == true -> ReadinessState.OFF
    ignoresBatteryOptimizations == true -> ReadinessState.ON
    // Not exempt is not proof of a restriction; OEM smart optimization is not publicly readable.
    else -> ReadinessState.UNKNOWN
}

internal data class ReminderChannelReadiness(
    val importance: Int?,
    val lockscreenVisibility: Int?,
    val groupBlocked: Boolean?,
    val soundPresent: Boolean?,
    val minimumImportance: Int,
    val needsChannelSound: Boolean,
)

internal fun vendorNotificationReadiness(channels: List<ReminderChannelReadiness>): ReadinessState {
    val blocked = channels.any { channel ->
        channel.groupBlocked == true ||
            channel.importance == NotificationManager.IMPORTANCE_NONE ||
            (channel.importance != null &&
                channel.importance in NotificationManager.IMPORTANCE_MIN until channel.minimumImportance) ||
            channel.lockscreenVisibility == Notification.VISIBILITY_SECRET ||
            (channel.needsChannelSound && channel.soundPresent == false)
    }
    // Public channel settings cannot prove that vendor app-level banner/lock-screen/sound toggles
    // are enabled. Even fully enabled channels must not be reported as vendor permission ON.
    return if (blocked) ReadinessState.OFF else ReadinessState.UNKNOWN
}

private inline fun <T> readReadinessFact(read: () -> T): T? = try {
    read()
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (_: IOException) {
    null
} catch (_: RuntimeException) {
    null
} catch (_: LinkageError) {
    // Some API 36 system images omit the feature-flagged promoted-notification API.
    null
}
