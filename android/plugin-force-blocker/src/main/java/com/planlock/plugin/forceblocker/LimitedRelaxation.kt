package com.planlock.plugin.forceblocker

import java.io.File
import java.util.Properties

/** One temporary response exception, never a host whitelist or a change to tracking. */
internal class LimitedRelaxation(
    private val file: File?,
    monotonicClock: (() -> Long)? = null,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val monotonicClock = monotonicClock ?: clock
    private data class Grant(val planId: Long, val packageName: String, val appName: String, val startedAt: Long, val expiresAt: Long,
        val elapsedStart: Long, val elapsedEnd: Long)
    // Interned file identity is shared even when reload creates a different DexClassLoader.
    private val fileLock = (file?.absolutePath ?: "oriva-no-state").intern()

    fun grant(planId: Long, packageName: String, appName: String, occurrenceEnd: Long): String = synchronized(fileLock) {
        val now = clock()
        val elapsed = monotonicClock()
        require(planId != 0L && packageName.matches(Regex("[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)+"))) { "请选择一个应用" }
        require(occurrenceEnd > now) { "当前计划不在执行时段，未新增豁免" }
        val duration = minOf(900_000L, occurrenceEnd - now)
        val value = Grant(planId, packageName, appName.ifBlank { packageName }, now, now + duration, elapsed, elapsed + duration)
        val target = requireNotNull(file) { "插件存储尚未就绪，未新增豁免" }
        require(target.parentFile?.isDirectory == true || target.parentFile?.mkdirs() == true) { "插件存储不可用" }
        val properties = Properties().apply {
            setProperty("planId", value.planId.toString()); setProperty("package", value.packageName)
            setProperty("name", value.appName); setProperty("startedAt", value.startedAt.toString())
            setProperty("expiresAt", value.expiresAt.toString())
            setProperty("elapsedStart", value.elapsedStart.toString()); setProperty("elapsedEnd", value.elapsedEnd.toString())
        }
        require(!target.exists() || target.isFile) { "插件存储不可用" }
        val staged = File.createTempFile("relax-", ".tmp", target.parentFile)
        try {
            staged.outputStream().use { properties.store(it, null); it.fd.sync() }
            // Android minSdk24: fail closed if replacement is interrupted; no partial target can grant.
            require(!target.exists() || target.delete()) { "无法替换插件状态" }
            require(staged.renameTo(target)) { "无法保存插件状态" }
        } finally { staged.delete() }
        "${value.appName} 已限时放松 ${((value.expiresAt - now + 59_999) / 60_000)} 分钟；计划结束或到期自动恢复全拦。应用使用仍会记录；再次选择会替换当前豁免。"
    }

    fun isRelaxed(planId: Long, packageName: String): Boolean = synchronized(fileLock) {
        current()?.let { it.planId == planId && it.packageName == packageName } == true
    }

    fun remainingMillis(planId: Long, packageName: String): Long? = synchronized(fileLock) {
        current()?.takeIf { it.planId == planId && it.packageName == packageName }?.let { it.elapsedEnd - monotonicClock() }
    }

    fun status(planId: Long): String = synchronized(fileLock) { current()?.takeIf { it.planId == planId }?.let {
        "${it.appName} 正在限时放松，剩余 ${((it.expiresAt - clock() + 59_999) / 60_000).coerceAtLeast(1)} 分钟；其余分心应用继续全拦。"
    } ?: "当前没有限时放松豁免。" }

    private fun current(): Grant? {
        val value = read() ?: return null
        val now = clock()
        val elapsed = monotonicClock()
        if (now < value.startedAt || now >= value.expiresAt || elapsed < value.elapsedStart || elapsed >= value.elapsedEnd ||
            kotlin.math.abs((now - value.startedAt) - (elapsed - value.elapsedStart)) > 1_000L) {
            file?.delete()
            return null
        }
        return value
    }

    private fun read(): Grant? = runCatching {
        val source = file?.takeIf { it.isFile } ?: return null
        val p = Properties().apply { source.inputStream().use(::load) }
        Grant(p.getProperty("planId").toLong(), p.getProperty("package"), p.getProperty("name"),
            p.getProperty("startedAt").toLong(), p.getProperty("expiresAt").toLong(),
            p.getProperty("elapsedStart").toLong(), p.getProperty("elapsedEnd").toLong()).takeIf {
            it.planId != 0L && it.packageName.isNotBlank() && it.expiresAt > it.startedAt && it.expiresAt - it.startedAt <= 900_000L
                && it.elapsedEnd - it.elapsedStart == it.expiresAt - it.startedAt
        }
    }.getOrNull()
}
