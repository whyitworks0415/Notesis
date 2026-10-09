package com.notesis

import android.content.Context
import android.os.Build
import android.os.CancellationSignal
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * One password for every locked note, kept on this device only, as a salted
 * PBKDF2 hash beside an optional hint. There is no recovery: forget it and the
 * locked notes stay behind it, which is what a local lock without an account means.
 *
 * ponytail: this gates opening a note in the app; the note's files are not
 * encrypted on disk. Encrypt the page files with a key derived from the
 * password if the threat is someone reading the app's storage directly.
 */
class NoteLock(context: Context) {
    private val prefs = context.getSharedPreferences("noteLock", Context.MODE_PRIVATE)

    val hasPassword: Boolean get() = prefs.contains(HASH)

    var hint: String
        get() = prefs.getString(HINT, "").orEmpty()
        private set(value) = prefs.edit().putString(HINT, value).apply()

    var biometric: Boolean
        get() = prefs.getBoolean(BIOMETRIC, true)
        set(value) = prefs.edit().putBoolean(BIOMETRIC, value).apply()

    fun setPassword(password: String, hint: String) {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        prefs.edit()
            .putString(SALT, salt.toHex())
            .putString(HASH, passwordHash(password, salt).toHex())
            .apply()
        this.hint = hint
    }

    fun check(password: String): Boolean {
        val salt = prefs.getString(SALT, null)?.fromHex() ?: return false
        val stored = prefs.getString(HASH, null) ?: return false
        return java.security.MessageDigest.isEqual(passwordHash(password, salt).toHex().toByteArray(), stored.toByteArray())
    }

    /**
     * Asks for the device's fingerprint or face; [onResult] gets true when it
     * passed. False straight away where there is none to ask.
     */
    fun askBiometric(activity: android.app.Activity, title: String, onResult: (Boolean) -> Unit) {
        if (!biometric || Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return onResult(false)
        runCatching {
            val prompt = android.hardware.biometrics.BiometricPrompt.Builder(activity)
                .setTitle(title)
                .setNegativeButton("암호 입력", activity.mainExecutor) { _, _ -> onResult(false) }
                .build()
            prompt.authenticate(CancellationSignal(), activity.mainExecutor,
                object : android.hardware.biometrics.BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(
                        result: android.hardware.biometrics.BiometricPrompt.AuthenticationResult?,
                    ) = onResult(true)

                    override fun onAuthenticationError(errorCode: Int, errString: CharSequence?) = onResult(false)
                })
        }.onFailure { onResult(false) }
    }

    private companion object {
        const val SALT = "salt"
        const val HASH = "hash"
        const val HINT = "hint"
        const val BIOMETRIC = "biometric"
        fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
        fun String.fromHex(): ByteArray = chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }
}

/** PBKDF2-SHA256 of [password] with [salt]; what [NoteLock] stores instead of the password. */
internal fun passwordHash(password: String, salt: ByteArray, iterations: Int = 120_000): ByteArray =
    SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        .generateSecret(PBEKeySpec(password.toCharArray(), salt, iterations, 256))
        .encoded
