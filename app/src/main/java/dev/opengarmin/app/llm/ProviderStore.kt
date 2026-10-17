package dev.opengarmin.app.llm

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import dev.opengarmin.app.BuildConfig
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class ProviderPreset(
    val id: String,
    val label: String,
    val baseUrl: String,
    val suggestedModels: List<String>,
    val isLocal: Boolean = false,
)

object ProviderPresets {
    val all = listOf(
        ProviderPreset(
            id = "openai",
            label = "OpenAI",
            baseUrl = "https://api.openai.com/v1",
            suggestedModels = listOf("gpt-4o-mini", "gpt-4.1-mini"),
        ),
        ProviderPreset(
            id = "deepseek",
            label = "DeepSeek",
            baseUrl = "https://api.deepseek.com/v1",
            suggestedModels = listOf("deepseek-chat", "deepseek-reasoner"),
        ),
        ProviderPreset(
            id = "openrouter",
            label = "OpenRouter",
            baseUrl = "https://openrouter.ai/api/v1",
            suggestedModels = listOf(
                "openai/gpt-4o-mini",
                "anthropic/claude-3.5-sonnet",
                "meta-llama/llama-3.1-70b-instruct",
            ),
        ),
        ProviderPreset(
            id = "ollama",
            label = "Ollama",
            baseUrl = "http://localhost:11434/v1",
            suggestedModels = listOf("llama3.1", "qwen2.5", "mistral"),
            isLocal = true,
        ),
        ProviderPreset(
            id = "lmstudio",
            label = "LM Studio",
            baseUrl = "http://localhost:1234/v1",
            suggestedModels = listOf("local-model"),
            isLocal = true,
        ),
        ProviderPreset(
            id = "vllm",
            label = "vLLM",
            baseUrl = "http://localhost:8000/v1",
            suggestedModels = listOf("served-model-name"),
            isLocal = true,
        ),
        ProviderPreset(
            id = "custom",
            label = "Custom",
            baseUrl = "",
            suggestedModels = emptyList(),
        ),
    )

    fun byId(id: String): ProviderPreset = all.firstOrNull { it.id == id } ?: all.last()
}

data class ModelConfig(
    val id: String,
    val name: String,
    val temperature: Double? = null,
    val maxTokens: Int? = null,
)

data class ProviderConfig(
    val presetId: String = "custom",
    val baseUrl: String = "",
    val models: List<ModelConfig> = emptyList(),
    val defaultModelId: String? = null,
    val keyHint: String = "",
    val hasStoredKey: Boolean = false,
    val fromBuildConfig: Boolean = false,
) {
    val defaultModel: ModelConfig?
        get() = models.firstOrNull { it.id == defaultModelId } ?: models.firstOrNull()

    val isConfigured: Boolean
        get() = baseUrl.isNotBlank() && defaultModel != null
}

/**
 * Encrypts provider keys at rest with an Android Keystore AES-GCM key. The
 * ciphertext lives in private SharedPreferences; the plaintext is only read
 * inside the HTTP request path.
 */
class SecretStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("pulse_secrets", Context.MODE_PRIVATE)

    fun save(plaintext: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val iv = cipher.iv
        val encrypted = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        val encoded = Base64.encodeToString(iv, Base64.NO_WRAP) + ":" +
            Base64.encodeToString(encrypted, Base64.NO_WRAP)
        prefs.edit().putString(KEY_CIPHERTEXT, encoded).apply()
        return mask(plaintext)
    }

    fun read(): String? {
        val encoded = prefs.getString(KEY_CIPHERTEXT, null) ?: return null
        return runCatching {
            val parts = encoded.split(':', limit = 2)
            if (parts.size != 2) return@runCatching null
            val iv = Base64.decode(parts[0], Base64.NO_WRAP)
            val data = Base64.decode(parts[1], Base64.NO_WRAP)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
            String(cipher.doFinal(data), Charsets.UTF_8)
        }.getOrNull()
    }

    fun delete() {
        prefs.edit().remove(KEY_CIPHERTEXT).apply()
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
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "pulse_provider_key"
        private const val KEY_CIPHERTEXT = "provider_key_ciphertext"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"

        fun mask(key: String): String =
            if (key.length <= 4) "••••" else "••••" + key.takeLast(4)
    }
}

class ProviderStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("pulse_provider", Context.MODE_PRIVATE)
    private val secrets = SecretStore(context)

    fun load(): ProviderConfig {
        val stored = prefs.getString(KEY_CONFIG, null)
        if (stored != null) {
            runCatching { parse(JSONObject(stored)) }.getOrNull()?.let { return it }
        }
        return fromBuildConfig()
    }

    fun save(config: ProviderConfig, newKey: String?): ProviderConfig {
        var next = config
        if (!newKey.isNullOrBlank()) {
            val hint = secrets.save(newKey.trim())
            next = next.copy(keyHint = hint, hasStoredKey = true, fromBuildConfig = false)
        }
        prefs.edit().putString(KEY_CONFIG, toJson(next).toString()).apply()
        return next
    }

    fun deleteKey(): ProviderConfig {
        secrets.delete()
        val next = load().copy(hasStoredKey = false, keyHint = "", fromBuildConfig = false)
        prefs.edit().putString(KEY_CONFIG, toJson(next).toString()).apply()
        return next
    }

    fun clear() {
        secrets.delete()
        prefs.edit().clear().apply()
    }

    fun apiKeyFor(config: ProviderConfig): String? {
        if (config.hasStoredKey) return secrets.read()
        if (config.fromBuildConfig) return BuildConfig.OPENAI_API_KEY.takeIf { it.isNotBlank() }
        return null
    }

    private fun fromBuildConfig(): ProviderConfig {
        val base = BuildConfig.OPENAI_BASE_URL
        if (base.isBlank()) return ProviderConfig()
        val model = BuildConfig.OPENAI_MODEL.ifBlank { "gpt-4o-mini" }
        val key = BuildConfig.OPENAI_API_KEY
        return ProviderConfig(
            presetId = "custom",
            baseUrl = base.trimEnd('/'),
            models = listOf(ModelConfig(id = "env", name = model)),
            defaultModelId = "env",
            keyHint = if (key.isBlank()) "" else SecretStore.mask(key),
            hasStoredKey = false,
            fromBuildConfig = true,
        )
    }

    private fun parse(json: JSONObject): ProviderConfig {
        val models = mutableListOf<ModelConfig>()
        val array = json.optJSONArray("models") ?: JSONArray()
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val name = item.optString("name").trim()
            if (name.isEmpty()) continue
            models += ModelConfig(
                id = item.optString("id").ifBlank { name },
                name = name,
                temperature = item.optDouble("temperature").takeIf { !it.isNaN() },
                maxTokens = item.optInt("maxTokens", 0).takeIf { it > 0 },
            )
        }
        return ProviderConfig(
            presetId = json.optString("presetId", "custom"),
            baseUrl = json.optString("baseUrl").trimEnd('/'),
            models = models,
            defaultModelId = json.optString("defaultModelId").takeIf { it.isNotBlank() },
            keyHint = json.optString("keyHint"),
            hasStoredKey = json.optBoolean("hasStoredKey", false),
        )
    }

    private fun toJson(config: ProviderConfig): JSONObject {
        val models = JSONArray()
        config.models.forEach { model ->
            models.put(
                JSONObject().apply {
                    put("id", model.id)
                    put("name", model.name)
                    model.temperature?.let { put("temperature", it) }
                    model.maxTokens?.let { put("maxTokens", it) }
                },
            )
        }
        return JSONObject().apply {
            put("presetId", config.presetId)
            put("baseUrl", config.baseUrl)
            put("models", models)
            config.defaultModelId?.let { put("defaultModelId", it) }
            put("keyHint", config.keyHint)
            put("hasStoredKey", config.hasStoredKey)
        }
    }

    companion object {
        private const val KEY_CONFIG = "provider_config"
    }
}
