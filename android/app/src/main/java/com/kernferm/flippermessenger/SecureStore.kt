package com.kernferm.flippermessenger

import android.content.Context
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties

class SecureStore(context: Context) {
    private val prefs = context.getSharedPreferences("secure_state", Context.MODE_PRIVATE)
    private val alias = "flipper_messenger_wrap_v1"

    private fun wrappingKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder(alias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).build())
            generateKey()
        }
    }

    fun saveLinkKey(key: ByteArray) {
        require(key.size == 32)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, wrappingKey())
        prefs.edit()
            .putString("link_iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .putString("link_key", Base64.encodeToString(cipher.doFinal(key), Base64.NO_WRAP)).apply()
    }

    fun loadLinkKey(): ByteArray? = try {
        val iv = Base64.decode(prefs.getString("link_iv", null), Base64.NO_WRAP)
        val wrapped = Base64.decode(prefs.getString("link_key", null), Base64.NO_WRAP)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, wrappingKey(), GCMParameterSpec(128, iv))
        cipher.doFinal(wrapped).takeIf { it.size == 32 }
    } catch (_: Exception) { null }

    fun clearLink() = prefs.edit().remove("link_iv").remove("link_key").apply()
    fun selectedDevice(): String? = prefs.getString("device", null)
    fun saveDevice(address: String) = prefs.edit().putString("device", address).apply()
    fun selectedSubscription(): Int = prefs.getInt("subscription", -1)
    fun saveSubscription(id: Int) = prefs.edit().putInt("subscription", id).apply()

    fun requestStatus(id: Long): Int? = if (prefs.contains("request_$id")) prefs.getInt("request_$id", 0) else null

    /** Synchronously persist before the SMS side effect; retain a bounded replay ledger. */
    fun saveRequestStatus(id: Long, status: Int): Boolean {
        val current = prefs.getString("request_order", "").orEmpty().split(',').filter { it.isNotBlank() }.toMutableList()
        val value = id.toString()
        current.remove(value)
        current += value
        val editor = prefs.edit().putInt("request_$id", status)
        while (current.size > 256) {
            val removed = current.removeAt(0)
            editor.remove("request_$removed")
        }
        return editor.putString("request_order", current.joinToString(",")).commit()
    }
}

