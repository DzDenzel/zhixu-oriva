package com.planlock.app.sync

/** Functional handoff to the account UI; neither choice is preselected. */
@kotlinx.serialization.Serializable
data class GuestMergeOffer(val sourceScopes: List<String>, val guestId: Long?, val count: Int, val accountScope: String, val guestToken: String? = null)
