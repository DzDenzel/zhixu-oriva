package com.planlock.app

import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.service.notification.NotificationListenerService
import android.util.Log
import com.planlock.app.security.Capability
import com.planlock.app.security.CapabilityAccess

/** Requests host notification effects only; never reads, stores or forwards notification contents. */
class FocusNotificationListenerService : NotificationListenerService(), SilenceListenerHandle {
    private val hintState = ListenerHintState()

    override fun onListenerConnected() {
        super.onListenerConnected()
        NotificationSilenceController.initialize(this)
        hintState.reset() // A new system connection has no hints, even when this Service instance is reused.
        NotificationSilenceController.attach(this)
    }

    override fun onListenerDisconnected() {
        NotificationSilenceController.detach(this)
        hintState.reset()
        super.onListenerDisconnected()
        NotificationSilenceController.recoverIfRequired()
    }

    override fun onDestroy() {
        NotificationSilenceController.destroy(this)
        hintState.reset()
        super.onDestroy()
    }

    override fun onListenerHintsChanged(hints: Int) {
        Log.i("OrivaSilence", "host-hints=$hints")
        NotificationSilenceController.observeHints(this, hints)
    }

    override fun applyRequired(required: Boolean) {
        hintState.applyIfNeeded(required) { target ->
            runCatching {
                requestListenerHints(if (target) HINT_HOST_DISABLE_NOTIFICATION_EFFECTS else 0)
            }.onFailure {
                Log.w("OrivaSilence", "Host hint request failed", it)
            }.isSuccess
        }
    }

    override fun resetRequested() {
        hintState.reset()
    }

    override fun currentHostHints(): Int? = runCatching { currentListenerHints }.getOrNull()
}

internal object NotificationSilenceController {
    @Volatile private var app: Context? = null
    private val session = NotificationSilenceSession(
        isGranted = {
            val context = app
            context != null && CapabilityAccess.granted(context, Capability.NOTIFICATION_SILENCE)
        },
        nowElapsedMs = { SystemClock.elapsedRealtime() },
        requestRebind = {
            val context = app ?: return@NotificationSilenceSession false
            runCatching {
                val component = ComponentName(context, FocusNotificationListenerService::class.java)
                // A dead binding can remain registered after force-stop / replacement on API 36.
                // Clear only our approved, disconnected listener before asking the host to bind again.
                if (Build.VERSION.SDK_INT >= 34) NotificationListenerService.requestUnbind(component)
                NotificationListenerService.requestRebind(component)
            }.onFailure {
                Log.w("OrivaSilence", "Listener rebind request failed", it)
            }.isSuccess
        },
        onLog = { message -> Log.i("OrivaSilence", message) },
    )

    @Synchronized fun initialize(context: Context) {
        val resolved = context.applicationContext ?: context
        if (app !== resolved) app = resolved
        session.initialize()
    }

    fun attach(service: FocusNotificationListenerService) = session.attach(service)
    // After onListenerDisconnected only requestRebind is safe; never send hints via a stale token.
    fun detach(service: FocusNotificationListenerService) = session.detach(service)
    fun destroy(service: FocusNotificationListenerService) = session.destroy(service)
    fun isRequired(): Boolean = session.isRequired()
    fun observeHints(service: FocusNotificationListenerService, value: Int) = session.observeHints(service, value)
    fun prepareReleaseReceipt(): PlanHintReceipt.Ticket? = session.prepareReleaseReceipt()
    fun releaseReceiptConfirmed(ticket: PlanHintReceipt.Ticket): Boolean = session.releaseReceiptConfirmed(ticket)
    fun currentHostHints(): Int? = session.currentHostHints()
    fun setRequired(value: Boolean) = session.setRequired(value)
    fun recoverIfRequired() = session.recoverIfRequired()

    /** One bounded request per lifecycle opportunity, only for access already granted by the user. */
    fun refreshConnection() {
        session.refreshConnection()
    }
}
