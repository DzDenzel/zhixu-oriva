package com.planlock.app.api

/** QA application IDs differ from the official host, but both must stay out of behavior statistics. */
object HostApplication {
    @Volatile var packageName: String = "com.planlock.app"
    fun owns(value: String?): Boolean = value == "com.planlock.app" || value == packageName
}
