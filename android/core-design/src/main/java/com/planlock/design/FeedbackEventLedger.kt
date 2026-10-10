package com.planlock.design

/** Records consumed identities only; it neither publishes messages nor owns presentation. */
class FeedbackEventLedger(private val capacity: Int = 512) {
    init { require(capacity > 0) }
    private val consumed = linkedSetOf<Pair<String, String>>()

    @Synchronized fun consume(source: String, eventId: String): Boolean {
        if (eventId.isBlank() || !consumed.add(source to eventId)) return false
        if (consumed.size > capacity) consumed.remove(consumed.first())
        return true
    }
}
