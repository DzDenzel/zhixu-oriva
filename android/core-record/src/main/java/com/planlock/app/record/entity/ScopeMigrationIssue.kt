package com.planlock.app.record.entity

@androidx.room.Entity(tableName = "scope_migration_issue", primaryKeys = ["sourceTable", "rowKey"])
data class ScopeMigrationIssue(val sourceTable: String, val rowKey: String, val reason: String)
