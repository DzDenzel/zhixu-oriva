package com.planlock.app

import java.security.MessageDigest

/** Dismissal belongs to the complete occurrence, including its waiting/running transition. */
object PlanLiveIdentity {
    fun occurrence(owner: String, planId: Long, endAt: Long): String =
        MessageDigest.getInstance("SHA-256").digest("$owner\n$planId\n$endAt".toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    fun mayPublish(dismissed: String?, occurrence: String): Boolean = dismissed != occurrence
}
