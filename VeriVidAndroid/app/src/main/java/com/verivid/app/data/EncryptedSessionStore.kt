package com.verivid.app.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Tokens stay on this device; no token logging and no cloud backup. */
class EncryptedSessionStore(context: Context, endpoint: String) : SessionStore {
    private val prefs = context.getSharedPreferences("analysis-session", Context.MODE_PRIVATE)
    private val slot = java.security.MessageDigest.getInstance("SHA-256").digest(endpoint.toByteArray()).joinToString("") { "%02x".format(it) }
    private val alias = "verivid.analysis.session.v1"
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    override var token: String?
        @Synchronized get() = prefs.getString(slot, null)?.let { stored ->
            runCatching {
                val parts = stored.split(":")
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)))
                String(cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)), Charsets.UTF_8)
            }.getOrElse { prefs.edit().remove(slot).apply(); null }
        }
        @Synchronized set(value) {
            if (value == null) { prefs.edit().remove(slot).apply(); return }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
            val iv = Base64.encodeToString(cipher.iv, Base64.NO_WRAP)
            val encrypted = Base64.encodeToString(cipher.doFinal(value.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
            check(prefs.edit().putString(slot, "$iv:$encrypted").commit()) { "세션을 저장하지 못했어요." }
        }
}
