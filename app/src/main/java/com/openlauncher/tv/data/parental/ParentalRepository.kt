package com.openlauncher.tv.data.parental

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.security.MessageDigest
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

private val Context.parentalDataStore by preferencesDataStore(name = "parental_state")

/** 儿童模式 / 防沉迷的全局状态。唯一事实来源，UI 与计时服务都只看它。 */
data class ParentalState(
    val kidsMode: Boolean = false,
    val pinSet: Boolean = false,
    val singleLimitMin: Int = 45,
    val dailyLimitMin: Int = 120,
    /** 本次（单次观看）剩余秒数 */
    val sessionRemainingSec: Int = 45 * 60,
    /** 今日已观看秒数 */
    val usedTodaySec: Int = 0,
    /** 家长今日额外赠送的秒数（相当于临时提高当日上限） */
    val bonusTodaySec: Int = 0,
    val unlimitedToday: Boolean = false,
    val dayKey: String = "",
    val locked: Boolean = false,
    val failedAttempts: Int = 0,
    val lockoutUntilMs: Long = 0L
) {
    val dailyLimitSec: Int get() = dailyLimitMin * 60 + bonusTodaySec

    val dailyRemainingSec: Int get() = (dailyLimitSec - usedTodaySec).coerceAtLeast(0)

    /** 真正可用于观看的剩余秒数（取单次与当日两个上限中更小的） */
    val remainingSec: Int
        get() = if (unlimitedToday) sessionRemainingSec
        else minOf(sessionRemainingSec, dailyRemainingSec).coerceAtLeast(0)

    fun isLockedOut(now: Long = System.currentTimeMillis()): Boolean = lockoutUntilMs > now

    companion object {
        const val DEFAULT_SINGLE_LIMIT_MIN = 45
        const val DEFAULT_DAILY_LIMIT_MIN = 120
    }
}

sealed class PinResult {
    object Ok : PinResult()
    data class Wrong(val attemptsLeft: Int) : PinResult()
    data class LockedOut(val untilMs: Long) : PinResult()
    object NotSet : PinResult()
}

/**
 * 家长控制仓库：内存 StateFlow 为唯一真源，写入做节流（计时每秒变化不会每秒落盘）。
 */
class ParentalRepository(
    private val context: Context,
    private val scope: CoroutineScope
) {

    private val _state = MutableStateFlow(ParentalState(dayKey = todayKey()))
    val state: StateFlow<ParentalState> = _state.asStateFlow()

    private val persistMutex = Mutex()
    private var persistJob: Job? = null
    private var dirty = false

    init {
        scope.launch {
            val loaded = runCatching { readSnapshot() }.getOrNull()
            if (loaded != null && !dirty) {
                _state.value = rolloverIfNeeded(loaded)
            }
        }
    }

    // ---- 状态更新 ----------------------------------------------------------------

    private fun mutate(block: (ParentalState) -> ParentalState, immediate: Boolean) {
        dirty = true
        _state.update { current -> rolloverIfNeeded(block(current)) }
        schedulePersist(immediate)
    }

    fun setKidsMode(enabled: Boolean) = mutate({ s ->
        s.copy(
            kidsMode = enabled,
            // 开启时重置本次时长，但保留当日已用（避免关掉再开就白嫖）
            sessionRemainingSec = if (enabled) s.singleLimitMin * 60 else s.sessionRemainingSec,
            locked = if (enabled) s.locked else false
        )
    }, true)

    fun setSingleLimitMinutes(minutes: Int) = mutate({ s ->
        s.copy(
            singleLimitMin = minutes,
            sessionRemainingSec = minutes * 60,
            locked = false
        )
    }, true)

    fun setDailyLimitMinutes(minutes: Int) = mutate({ s -> s.copy(dailyLimitMin = minutes) }, true)

    /** 家长解锁时"增加 N 分钟"：同时抬高当日额度，否则会立刻又被日上限锁住。 */
    fun grantExtraMinutes(minutes: Int) = mutate({ s ->
        s.copy(
            sessionRemainingSec = minutes * 60,
            bonusTodaySec = s.bonusTodaySec + minutes * 60,
            locked = false
        )
    }, true)

    fun grantUnlimitedToday() = mutate({ s ->
        s.copy(
            unlimitedToday = true,
            sessionRemainingSec = s.singleLimitMin * 60,
            locked = false
        )
    }, true)

    fun resetToday() = mutate({ s ->
        s.copy(
            usedTodaySec = 0,
            bonusTodaySec = 0,
            unlimitedToday = false,
            sessionRemainingSec = s.singleLimitMin * 60,
            locked = false
        )
    }, true)

    /** 计时服务每秒调用一次。返回 true 表示本次调用触发了锁定。 */
    fun consumeOneSecond(): Boolean {
        val current = rolloverIfNeeded(_state.value)
        if (!current.kidsMode || current.locked || current.unlimitedToday) {
            if (current !== _state.value) _state.value = current
            return false
        }
        val next = current.copy(
            usedTodaySec = current.usedTodaySec + 1,
            sessionRemainingSec = (current.sessionRemainingSec - 1).coerceAtLeast(0)
        )
        val shouldLock = next.sessionRemainingSec <= 0 || next.usedTodaySec >= next.dailyLimitSec
        _state.value = if (shouldLock) next.copy(locked = true) else next
        schedulePersist(immediate = shouldLock)
        return shouldLock
    }

    // ---- PIN --------------------------------------------------------------------

    /** 设置 / 修改 PIN（修改前的旧 PIN 校验由调用方完成）。 */
    suspend fun setPin(pin: String) {
        val iterations = PIN_ITERATIONS
        val algorithm = pickPbkdf2Algorithm()
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val hash = pbkdf2(pin, salt, iterations, algorithm)
        context.parentalDataStore.edit { prefs ->
            prefs[K_PIN_SALT] = salt.toHex()
            prefs[K_PIN_HASH] = hash.toHex()
            prefs[K_PIN_ALGO] = algorithm
            prefs[K_PIN_ITER] = iterations
        }
        mutate({ s -> s.copy(pinSet = true, failedAttempts = 0, lockoutUntilMs = 0) }, true)
    }

    suspend fun verifyPin(pin: String): PinResult {
        val now = System.currentTimeMillis()
        val snapshot = _state.value
        if (snapshot.isLockedOut(now)) return PinResult.LockedOut(snapshot.lockoutUntilMs)

        val prefs = runCatching { context.parentalDataStore.data.first() }.getOrNull()
            ?: return PinResult.NotSet
        val saltHex = prefs[K_PIN_SALT].orEmpty()
        val hashHex = prefs[K_PIN_HASH].orEmpty()
        if (saltHex.isEmpty() || hashHex.isEmpty()) return PinResult.NotSet

        val algorithm = prefs[K_PIN_ALGO] ?: ALGO_SHA1
        val iterations = prefs[K_PIN_ITER] ?: PIN_ITERATIONS
        val candidate = pbkdf2(pin, saltHex.hexToBytes(), iterations, algorithm)
        val ok = MessageDigest.isEqual(candidate, hashHex.hexToBytes())

        if (ok) {
            mutate({ s -> s.copy(failedAttempts = 0, lockoutUntilMs = 0) }, true)
            return PinResult.Ok
        }

        val attempts = snapshot.failedAttempts + 1
        return if (attempts >= MAX_ATTEMPTS) {
            val until = now + LOCKOUT_DURATION_MS
            mutate({ s -> s.copy(failedAttempts = 0, lockoutUntilMs = until) }, true)
            PinResult.LockedOut(until)
        } else {
            mutate({ s -> s.copy(failedAttempts = attempts) }, true)
            PinResult.Wrong(MAX_ATTEMPTS - attempts)
        }
    }

    // ---- 持久化 ------------------------------------------------------------------

    private fun schedulePersist(immediate: Boolean) {
        if (immediate) {
            persistJob?.cancel()
            persistJob = scope.launch { persistNow() }
        } else if (persistJob?.isActive != true) {
            persistJob = scope.launch {
                delay(PERSIST_INTERVAL_MS)
                persistNow()
            }
        }
    }

    private suspend fun persistNow() = persistMutex.withLock {
        val s = _state.value
        runCatching {
            context.parentalDataStore.edit { prefs ->
                prefs[K_KIDS] = s.kidsMode
                prefs[K_SINGLE] = s.singleLimitMin
                prefs[K_DAILY] = s.dailyLimitMin
                prefs[K_SESSION] = s.sessionRemainingSec
                prefs[K_USED] = s.usedTodaySec
                prefs[K_BONUS] = s.bonusTodaySec
                prefs[K_UNLIMITED] = s.unlimitedToday
                prefs[K_DAY] = s.dayKey
                prefs[K_LOCKED] = s.locked
                prefs[K_FAILED] = s.failedAttempts
                prefs[K_LOCKOUT] = s.lockoutUntilMs
            }
        }
    }

    private suspend fun readSnapshot(): ParentalState {
        val prefs = context.parentalDataStore.data.first()
        return ParentalState(
            kidsMode = prefs[K_KIDS] ?: false,
            pinSet = !prefs[K_PIN_HASH].orEmpty().isEmpty(),
            singleLimitMin = prefs[K_SINGLE] ?: ParentalState.DEFAULT_SINGLE_LIMIT_MIN,
            dailyLimitMin = prefs[K_DAILY] ?: ParentalState.DEFAULT_DAILY_LIMIT_MIN,
            sessionRemainingSec = prefs[K_SESSION]
                ?: ((prefs[K_SINGLE] ?: ParentalState.DEFAULT_SINGLE_LIMIT_MIN) * 60),
            usedTodaySec = prefs[K_USED] ?: 0,
            bonusTodaySec = prefs[K_BONUS] ?: 0,
            unlimitedToday = prefs[K_UNLIMITED] ?: false,
            dayKey = prefs[K_DAY] ?: "",
            locked = prefs[K_LOCKED] ?: false,
            failedAttempts = prefs[K_FAILED] ?: 0,
            lockoutUntilMs = prefs[K_LOCKOUT] ?: 0L
        )
    }

    /** 跨天自动重置：每日上限、赠送额度、当日已用、锁定状态。 */
    private fun rolloverIfNeeded(s: ParentalState): ParentalState {
        val today = todayKey()
        if (s.dayKey == today) return s
        return s.copy(
            dayKey = today,
            usedTodaySec = 0,
            bonusTodaySec = 0,
            unlimitedToday = false,
            sessionRemainingSec = s.singleLimitMin * 60,
            locked = false,
            failedAttempts = 0,
            lockoutUntilMs = 0L
        )
    }

    // ---- 工具 --------------------------------------------------------------------

    private fun pickPbkdf2Algorithm(): String = try {
        SecretKeyFactory.getInstance(ALGO_SHA256)
        ALGO_SHA256
    } catch (e: Exception) {
        ALGO_SHA1
    }

    private fun pbkdf2(pin: String, salt: ByteArray, iterations: Int, algorithm: String): ByteArray {
        val spec = PBEKeySpec(pin.toCharArray(), salt, iterations, 256)
        return try {
            SecretKeyFactory.getInstance(algorithm).generateSecret(spec).encoded
        } catch (e: Exception) {
            // 极端兜底：该设备不支持 PBKDF2 时退回加盐 SHA-256（仍好于明文）
            MessageDigest.getInstance("SHA-256").digest(salt + pin.toByteArray(Charsets.UTF_8))
        } finally {
            spec.clearPassword()
        }
    }

    private fun todayKey(): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    private fun String.hexToBytes(): ByteArray {
        if (length % 2 != 0) return ByteArray(0)
        return ByteArray(length / 2) { i -> substring(i * 2, i * 2 + 2).toInt(16).toByte() }
    }

    companion object {
        const val PIN_LENGTH = 4
        const val MAX_ATTEMPTS = 5
        private const val LOCKOUT_DURATION_MS = 5 * 60 * 1000L
        private const val PERSIST_INTERVAL_MS = 5_000L
        private const val PIN_ITERATIONS = 120_000
        private const val ALGO_SHA256 = "PBKDF2WithHmacSHA256"
        private const val ALGO_SHA1 = "PBKDF2WithHmacSHA1"

        private val K_KIDS = booleanPreferencesKey("kids_mode")
        private val K_SINGLE = intPreferencesKey("single_limit_min")
        private val K_DAILY = intPreferencesKey("daily_limit_min")
        private val K_SESSION = intPreferencesKey("session_remaining_sec")
        private val K_USED = intPreferencesKey("used_today_sec")
        private val K_BONUS = intPreferencesKey("bonus_today_sec")
        private val K_UNLIMITED = booleanPreferencesKey("unlimited_today")
        private val K_DAY = stringPreferencesKey("day_key")
        private val K_LOCKED = booleanPreferencesKey("locked")
        private val K_FAILED = intPreferencesKey("failed_attempts")
        private val K_LOCKOUT = longPreferencesKey("lockout_until_ms")

        private val K_PIN_SALT = stringPreferencesKey("pin_salt")
        private val K_PIN_HASH = stringPreferencesKey("pin_hash")
        private val K_PIN_ALGO = stringPreferencesKey("pin_algo")
        private val K_PIN_ITER = intPreferencesKey("pin_iterations")
    }
}
