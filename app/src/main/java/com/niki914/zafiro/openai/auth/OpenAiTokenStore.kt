package com.niki914.zafiro.openai.auth

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
 * Persists only the long-lived refresh credential bundle, encrypted with an
 * Android Keystore AES-GCM key. Access tokens are deliberately never persisted.
 */
class OpenAiTokenStore(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    @Synchronized
    fun save(account: StoredOpenAiAccount) {
        require(account.refreshToken.isNotBlank()) { "refreshToken must not be blank" }
        val json = JSONObject()
            .put("local_account_id", account.localAccountId)
            .put("email", account.email)
            .put("chatgpt_account_id", account.chatgptAccountId)
            .put("refresh_token", account.refreshToken)
            .put("id_token", account.idToken)
            .toString()
        prefs.edit().putString(KEY_ACCOUNT, encrypt(json)).apply()
    }

    @Synchronized
    fun load(): StoredOpenAiAccount? {
        val encrypted = prefs.getString(KEY_ACCOUNT, null) ?: return null
        return runCatching {
            val json = JSONObject(decrypt(encrypted))
            val refreshToken = json.optString("refresh_token").trim()
            if (refreshToken.isBlank()) return@runCatching null
            StoredOpenAiAccount(
                localAccountId = json.optString("local_account_id").ifBlank { "default" },
                email = json.optNullableString("email"),
                chatgptAccountId = json.optNullableString("chatgpt_account_id"),
                refreshToken = refreshToken,
                idToken = json.optNullableString("id_token"),
            )
        }.getOrNull()
    }

    @Synchronized
    fun clear() {
        prefs.edit().remove(KEY_ACCOUNT).apply()
    }

    private fun encrypt(plaintext: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + "." +
                Base64.encodeToString(ciphertext, Base64.NO_WRAP)
    }

    private fun decrypt(value: String): String {
        val parts = value.split('.', limit = 2)
        require(parts.size == 2) { "Invalid encrypted credential payload" }
        val iv = Base64.decode(parts[0], Base64.NO_WRAP)
        val ciphertext = Base64.decode(parts[1], Base64.NO_WRAP)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
        return cipher.doFinal(ciphertext).toString(Charsets.UTF_8)
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build(),
        )
        return generator.generateKey()
    }

    private fun JSONObject.optNullableString(name: String): String? {
        if (isNull(name)) return null
        return optString(name).trim().takeIf { it.isNotEmpty() }
    }

    private companion object {
        const val PREFS_NAME = "openai_oauth_secure"
        const val KEY_ACCOUNT = "account_v1"
        const val KEY_ALIAS = "zafiro_openai_oauth_v1"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_TAG_BITS = 128
    }
}
