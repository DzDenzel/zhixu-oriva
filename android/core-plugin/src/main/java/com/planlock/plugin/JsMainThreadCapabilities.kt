package com.planlock.plugin

import android.os.Handler
import android.os.Looper

/** The only native UI actions cross back to the main thread; stale plugin tasks are discarded. */
internal class JsMainThreadCapabilities(private val delegate: PluginCapabilities, private val active: () -> Boolean) : PluginCapabilities {
    private val handler = Handler(Looper.getMainLooper())
    private fun post(action: () -> Unit) { handler.post { if (active()) runCatching(action) } }
    override fun overlay(): OverlayCapability? = delegate.overlay()?.let { overlay -> object : OverlayCapability {
        override fun show(factory: PluginViewFactory, displayMillis: Long, onExpired: Runnable) {
            post { overlay.show(factory, displayMillis, Runnable { if (active()) onExpired.run() }) }
        }
    } }
    override fun home(): HomeCapability? = delegate.home()?.let { home -> HomeCapability { post { home.requestHome() } } }
    override fun haptic(): HapticCapability? = delegate.haptic()?.let { haptic -> HapticCapability { post { haptic.perform() } } }
    override fun feedback(): FeedbackCapability? = delegate.feedback()?.let { feedback -> FeedbackCapability { code -> post { feedback.emit(code) } } }
}
