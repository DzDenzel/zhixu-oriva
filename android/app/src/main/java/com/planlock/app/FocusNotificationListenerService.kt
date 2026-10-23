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
class FocusNotificationListenerService : NotificationListenerService() {
    private var requested: Boolean? = null
    override fun onListenerConnected() {
        super.onListenerConnected()
        requested = null // A new system connection has no hints, even when this Service instance is reused.
        NotificationSilenceController.attach(this)
    }
    override fun onListenerDisconnected() {
        NotificationSilenceController.detach(this)
        super.onListenerDisconnected()
        NotificationSilenceController.recoverIfRequired()
    }
    override fun onDestroy() { NotificationSilenceController.destroy(this); super.onDestroy() }
    override fun onListenerHintsChanged(hints: Int) {
        Log.i("OrivaSilence", "host-hints=$hints")
    }
    internal fun applyRequired(required: Boolean) {
        if (requested == required) return
        runCatching { requestListenerHints(if (required) HINT_HOST_DISABLE_NOTIFICATION_EFFECTS else 0) }
            .onSuccess { requested = required }
            .onFailure { Log.w("OrivaSilence", "Host hint request failed", it) }
    }
}

internal object NotificationSilenceController {
    private var app: Context? = null
    private var listener: FocusNotificationListenerService? = null
    private var required = false
    private val recovery = ListenerRebindGate()

    @Synchronized fun initialize(context: Context) { app = context.applicationContext }
    @Synchronized fun attach(service: FocusNotificationListenerService) {
        listener = service
        service.applyRequired(required)
        Log.i("OrivaSilence", "connected required=$required")
    }
    // After onListenerDisconnected only requestRebind is safe; never send hints via a stale token.
    @Synchronized fun detach(service: FocusNotificationListenerService) {
        if (listener === service) { listener = null; Log.i("OrivaSilence", "disconnected") }
    }
    @Synchronized fun destroy(service: FocusNotificationListenerService) {
        if (listener === service) { service.applyRequired(false); listener = null }
    }
    @Synchronized fun setRequired(value: Boolean) {
        val beginning = value && !required
        required = value
        listener?.applyRequired(value)
        if (beginning) refreshConnection()
    }
    @Synchronized fun recoverIfRequired() { if (required) refreshConnection() }

    /** One bounded request per lifecycle opportunity, only for access already granted by the user. */
    @Synchronized fun refreshConnection() {
        val context = app ?: return
        val granted = CapabilityAccess.granted(context, Capability.NOTIFICATION_SILENCE)
        if (!recovery.shouldRequest(granted, listener != null, SystemClock.elapsedRealtime())) return
        runCatching {
            val component = ComponentName(context, FocusNotificationListenerService::class.java)
            // A dead binding can remain registered after force-stop / replacement on API 36.
            // Clear only our approved, disconnected listener before asking the host to bind again.
            if (Build.VERSION.SDK_INT >= 34) NotificationListenerService.requestUnbind(component)
            NotificationListenerService.requestRebind(component)
        }
            .onSuccess { Log.i("OrivaSilence", "rebind-request required=$required") }
            .onFailure { Log.w("OrivaSilence", "Listener rebind request failed", it) }
    }
}
