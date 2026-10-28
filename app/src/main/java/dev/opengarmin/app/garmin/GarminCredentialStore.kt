package dev.opengarmin.app.garmin

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Local Garmin account metadata.
 *
 * The email is kept as a convenience so the settings field can be prefilled.
 * The password is never persisted: it is exchanged for a DI OAuth2 token pair
 * which is encrypted with an Android Keystore AES-GCM key.
 */
class GarminCredentialStore(context: Context) : GarminTokenStore {
    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun username(): String = prefs.getString(KEY_USERNAME, "") ?: ""

    fun saveUsername(username: String) {
        prefs.edit().putString(KEY_USERNAME, username.trim().take(320)).apply()
    }

    fun isSignedIn(): Boolean = load() != null

    override fun load(): GarminTokens? {
        val encoded = prefs.getString(KEY_TOKENS, null) ?: return null
        return runCatching {
            val json = JSONObject(decrypt(encoded))
            val access = json.optString("accessToken")
            val clientId = json.optString("clientId")
            val expiresAt = json.optLong("expiresAtMillis", 0L)
            if (access.isBlank() || clientId.isBlank()) return@runCatching null
            GarminTokens(
                accessToken = access,
                refreshToken = json.optString("refreshToken").takeIf { it.isNotBlank() },
                clientId = clientId,
                expiresAtMillis = expiresAt,
            )
        }.getOrNull()
    }

    override fun save(tokens: GarminTokens) {
        runCatching {
            val json = JSONObject().apply {
                put("accessToken", tokens.accessToken)
                tokens.refreshToken?.let { put("refreshToken", it) }
                put("clientId", tokens.clientId)
                put("expiresAtMillis", tokens.expiresAtMillis)
            }
            prefs.edit().putString(KEY_TOKENS, encrypt(json.toString())).apply()
        }
    }

    override fun clear() {
        prefs.edit().remove(KEY_TOKENS).apply()
    }

    fun signOut() {
        clear()
    }

    private fun encrypt(plaintext: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val iv = cipher.iv
        val encrypted = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(iv, Base64.NO_WRAP) + ":" +
            Base64.encodeToString(encrypted, Base64.NO_WRAP)
    }

    private fun decrypt(encoded: String): String {
        val parts = encoded.split(':', limit = 2)
        require(parts.size == 2) { "Malformed stored Garmin tokens" }
        val iv = Base64.decode(parts[0], Base64.NO_WRAP)
        val data = Base64.decode(parts[1], Base64.NO_WRAP)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
        return String(cipher.doFinal(data), Charsets.UTF_8)
    }

    private fun key(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val existing = keyStore.getKey(KEY_ALIAS, null) as? SecretKey
        if (existing != null) return existing
        val generator = KeyGenerator.getInstance("AES", ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    companion object {
        private const val PREFS_NAME = "pulse_garmin"
        private const val KEY_USERNAME = "username"
        private const val KEY_TOKENS = "tokens_ciphertext"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "pulse_garmin_token_key"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
