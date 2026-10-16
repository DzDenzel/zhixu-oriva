package com.planlock.app.security

data class BasePermissionSummary(val enabled: Int, val total: Int)

fun basePermissionSummary(granted: Map<PermissionKind, Boolean>): BasePermissionSummary {
    val kinds = basePermissionKinds()
    return BasePermissionSummary(kinds.count { granted[it] == true }, kinds.size)
}
