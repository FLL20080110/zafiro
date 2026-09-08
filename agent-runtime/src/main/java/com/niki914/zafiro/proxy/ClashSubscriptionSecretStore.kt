package com.niki914.zafiro.proxy

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import java.io.DataInputStream
import java.io.DataOutputStream
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Stores the Clash subscription URL encrypted at rest.
 *
 * The AES key is non-exportable and lives in Android Keystore. Only IV + ciphertext are
 * persisted in the app-private no-backup directory. Callers must never log the returned secret.
 */
class ClashSubscriptionSecretStore(context: Context) {
    // Ciphertext is intentionally excluded from Android Auto Backup. The Keystore key may not
    // survive a restore to another device, and restoring an undecryptable subscription is useless.
    private val file = AtomicFile(context.applicationContext.noBackupFilesDir.resolve(FILE_NAME))

    @Synchronized
    fun save(subscriptionUrl: String) {
        val secret = subscriptionUrl.trim()
        require(secret.isNotEmpty()) { "Clash 订阅地址不能为空" }
        require(secret.length <= MAX_SECRET_CHARS) { "Clash 订阅地址过长" }

        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val ciphertext = cipher.doFinal(secret.toByteArray(Charsets.UTF_8))
        val iv = cipher.iv

        val output = file.startWrite()
        try {
            // Do not close the FileOutputStream before AtomicFile.finishWrite(); finishWrite is
            // responsible for syncing, closing, and committing the temporary file atomically.
            val data = DataOutputStream(output)
            data.writeInt(FORMAT_VERSION)
            data.writeInt(iv.size)
            data.write(iv)
            data.writeInt(ciphertext.size)
            data.write(ciphertext)
            data.flush()
            file.finishWrite(output)
        } catch (t: Throwable) {
            file.failWrite(output)
            throw t
        }
    }

    @Synchronized
    fun read(): String? {
        if (!file.baseFile.exists()) return null
        return runCatching {
            DataInputStream(file.openRead()).use { data ->
                require(data.readInt() == FORMAT_VERSION) { "不支持的 Clash 订阅密文版本" }
                val ivLength = data.readInt()
                require(ivLength in 12..32) { "Clash 订阅密文 IV 无效" }
                val iv = ByteArray(ivLength).also(data::readFully)
                val ciphertextLength = data.readInt()
                require(ciphertextLength in 1..MAX_CIPHERTEXT_BYTES) { "Clash 订阅密文长度无效" }
                val ciphertext = ByteArray(ciphertextLength).also(data::readFully)
                require(data.read() == -1) { "Clash 订阅密文包含多余数据" }

                val cipher = Cipher.getInstance(TRANSFORMATION)
                cipher.init(
                    Cipher.DECRYPT_MODE,
                    getOrCreateKey(),
                    GCMParameterSpec(GCM_TAG_BITS, iv),
                )
                cipher.doFinal(ciphertext).toString(Charsets.UTF_8).also {
                    require(it.isNotBlank() && it.length <= MAX_SECRET_CHARS) {
                        "Clash 订阅密文内容无效"
                    }
                }
            }
        }.getOrElse {
            // Corrupt or undecryptable state fails closed. Never include the secret/ciphertext in errors.
            throw IllegalStateException("无法读取已加密的 Clash 订阅，请重新配置", it)
        }
    }

    @Synchronized
    fun clear() {
        file.delete()
        // Dedicated alias: deleting it gives clear() cryptographic-erasure semantics as well as
        // deleting the ciphertext. A later save() transparently creates a fresh key.
        runCatching {
            KeyStore.getInstance(KEYSTORE).apply { load(null) }.deleteEntry(KEY_ALIAS)
        }
    }

    /** Returns true only when the stored ciphertext can actually be decrypted and validated. */
    @Synchronized
    fun isConfigured(): Boolean = runCatching { read() != null }.getOrDefault(false)

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setKeySize(256)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build(),
        )
        return generator.generateKey()
    }

    companion object {
        private const val KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "zafiro.clash.subscription.v1"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_TAG_BITS = 128
        private const val FILE_NAME = "clash-subscription.enc"
        private const val FORMAT_VERSION = 1
        private const val MAX_SECRET_CHARS = 8_192
        private const val MAX_CIPHERTEXT_BYTES = 32 * 1024
    }
}
