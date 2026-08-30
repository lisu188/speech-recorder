package pl.lisu188.speechrecorder

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

object OpenAiKeyStore {
    private const val PREFS = "openai_credentials"
    private const val PREF_CIPHERTEXT = "api_key_ciphertext"
    private const val PREF_IV = "api_key_iv"
    private const val KEY_ALIAS = "speech_recorder_openai_api_key"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"

    fun save(context: Context, apiKey: String) {
        val normalized = apiKey.trim()
        require(normalized.isNotEmpty()) { "API key must not be empty" }

        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val encrypted = cipher.doFinal(normalized.toByteArray(StandardCharsets.UTF_8))

        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(PREF_CIPHERTEXT, Base64.encodeToString(encrypted, Base64.NO_WRAP))
            .putString(PREF_IV, Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .apply()
    }

    fun load(context: Context): String? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val ciphertext = prefs.getString(PREF_CIPHERTEXT, null) ?: return null
        val iv = prefs.getString(PREF_IV, null) ?: return null

        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                getOrCreateKey(),
                GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)),
            )
            String(
                cipher.doFinal(Base64.decode(ciphertext, Base64.NO_WRAP)),
                StandardCharsets.UTF_8,
            )
        } catch (_: Exception) {
            prefs.edit().remove(PREF_CIPHERTEXT).remove(PREF_IV).apply()
            null
        }
    }

    fun hasKey(context: Context): Boolean = !load(context).isNullOrBlank()

    fun delete(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .remove(PREF_CIPHERTEXT)
            .remove(PREF_IV)
            .apply()
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }

        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build(),
            )
            generateKey()
        }
    }
}
