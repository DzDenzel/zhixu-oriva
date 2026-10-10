package com.planlock.app.security

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

data class PasswordCredential(
    val saltEncoded: String,
    val hashEncoded: String,
    val iterations: Int,
)

data class BackupPasswordState(
    val credential: PasswordCredential? = null,
    val failedAttempts: Int = 0,
    val cooldownUntilMillis: Long = 0,
)

sealed interface BackupPasswordResult {
    data object Verified : BackupPasswordResult
    data object NotSet : BackupPasswordResult
    data class Invalid(val remainingAttempts: Int) : BackupPasswordResult
    data class Cooldown(val remainingMillis: Long) : BackupPasswordResult
}

data class BackupPasswordAttempt(
    val result: BackupPasswordResult,
    val state: BackupPasswordState,
)

class Pbkdf2PasswordHasher(
    private val iterations: Int = DEFAULT_ITERATIONS,
    private val random: SecureRandom = SecureRandom(),
) {
    fun create(password: String): PasswordCredential {
        val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
        return PasswordCredential(salt.encodeHex(), derive(password, salt, iterations).encodeHex(), iterations)
    }

    fun matches(password: String, credential: PasswordCredential): Boolean {
        val expected = credential.hashEncoded.decodeHex()
        val actual = derive(password, credential.saltEncoded.decodeHex(), credential.iterations)
        return MessageDigest.isEqual(expected, actual)
    }

    private fun derive(password: String, salt: ByteArray, rounds: Int): ByteArray {
        val spec = PBEKeySpec(password.toCharArray(), salt, rounds, KEY_BITS)
        return try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    companion object {
        const val DEFAULT_ITERATIONS = 120_000
        private const val SALT_BYTES = 16
        private const val KEY_BITS = 256
    }
}

class BackupPasswordVerifier(private val hasher: Pbkdf2PasswordHasher = Pbkdf2PasswordHasher()) {
    fun verify(input: String, current: BackupPasswordState, nowMillis: Long): BackupPasswordAttempt {
        val credential = current.credential ?: return BackupPasswordAttempt(BackupPasswordResult.NotSet, current)
        if (current.cooldownUntilMillis > nowMillis) {
            return BackupPasswordAttempt(
                BackupPasswordResult.Cooldown(current.cooldownUntilMillis - nowMillis),
                current,
            )
        }
        val state = if (current.cooldownUntilMillis != 0L) current.copy(failedAttempts = 0, cooldownUntilMillis = 0) else current
        if (hasher.matches(input, credential)) {
            return BackupPasswordAttempt(BackupPasswordResult.Verified, state.copy(failedAttempts = 0, cooldownUntilMillis = 0))
        }
        val failures = state.failedAttempts + 1
        return if (failures >= MAX_FAILURES) {
            val cooldown = nowMillis + COOLDOWN_MILLIS
            BackupPasswordAttempt(BackupPasswordResult.Cooldown(COOLDOWN_MILLIS), state.copy(failedAttempts = 0, cooldownUntilMillis = cooldown))
        } else {
            BackupPasswordAttempt(BackupPasswordResult.Invalid(MAX_FAILURES - failures), state.copy(failedAttempts = failures))
        }
    }

    companion object {
        const val MAX_FAILURES = 3
        const val COOLDOWN_MILLIS = 30_000L
    }
}

class BackupPasswordStore(
    private val dataStore: DataStore<Preferences>,
    private val hasher: Pbkdf2PasswordHasher = Pbkdf2PasswordHasher(),
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    suspend fun hasPassword(): Boolean = readState().credential != null

    suspend fun set(password: String) = stateMutex.withLock {
        require(password.length in 4..16) { "备用密码须为 4–16 位" }
        writeState(BackupPasswordState(hasher.create(password)))
    }

    suspend fun verify(input: String): BackupPasswordResult = stateMutex.withLock {
        val current = readState()
        val attempt = BackupPasswordVerifier(hasher).verify(input, current, nowMillis())
        if (attempt.state != current) writeState(attempt.state)
        attempt.result
    }

    private suspend fun readState(): BackupPasswordState {
        val values = dataStore.data.first()
        val salt = values[SALT]
        val hash = values[HASH]
        val rounds = values[ITERATIONS]
        val credential = if (salt != null && hash != null && rounds != null) PasswordCredential(salt, hash, rounds) else null
        return BackupPasswordState(
            credential = credential,
            failedAttempts = values[FAILURES] ?: 0,
            cooldownUntilMillis = values[COOLDOWN_UNTIL] ?: 0,
        )
    }

    private suspend fun writeState(state: BackupPasswordState) {
        dataStore.edit { values ->
            state.credential?.let {
                values[SALT] = it.saltEncoded
                values[HASH] = it.hashEncoded
                values[ITERATIONS] = it.iterations
            } ?: run {
                values.remove(SALT); values.remove(HASH); values.remove(ITERATIONS)
            }
            values[FAILURES] = state.failedAttempts
            values[COOLDOWN_UNTIL] = state.cooldownUntilMillis
        }
    }

    companion object {
        private val SALT = stringPreferencesKey("backup_password_salt")
        private val HASH = stringPreferencesKey("backup_password_hash")
        private val ITERATIONS = intPreferencesKey("backup_password_iterations")
        private val FAILURES = intPreferencesKey("backup_password_failures")
        private val COOLDOWN_UNTIL = longPreferencesKey("backup_password_cooldown_until")
        private val stateMutex = Mutex()
    }
}

private val HEX = "0123456789abcdef".toCharArray()

private fun ByteArray.encodeHex(): String = buildString(size * 2) {
    for (byte in this@encodeHex) {
        val value = byte.toInt() and 0xff
        append(HEX[value ushr 4])
        append(HEX[value and 0x0f])
    }
}

private fun String.decodeHex(): ByteArray {
    require(length % 2 == 0) { "Invalid hex length" }
    return ByteArray(length / 2) { index ->
        val high = this[index * 2].digitToInt(16)
        val low = this[index * 2 + 1].digitToInt(16)
        ((high shl 4) or low).toByte()
    }
}
