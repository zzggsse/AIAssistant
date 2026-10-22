package com.aibutler.app

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 本地敏感信息加密存取：基于安卓系统 Keystore 的 AES-256-GCM。
 * 用于 API Key / 锁屏 PIN 这类"泄露会出事"的配置：
 * - 密钥保存在系统 Keystore 里，不落盘、不进 adb/云备份，卸载 App 即销毁
 * - 密文格式为 base64(iv + ciphertext)，同一明文每次加密结果都不同
 * - 加解密失败（密钥失效/数据损坏/恢复到新手机）一律返回空串，绝不抛异常崩溃
 */
object SecureStore {
    private const val KEY_ALIAS = "aibutler_secure_v1"
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val IV_BYTES = 12
    private const val TAG_BITS = 128

    private fun secretKey(): SecretKey {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (ks.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    /** 加密；空串原样返回（不生成无意义的密文）。
     *  Keystore 异常（个别机型密钥失效/系统繁忙）不向外抛：调用方 Prefs 没有 try，
     *  抛出去就是设置页直接崩溃；记日志后返回 ""，语义等价"没配置"，用户重填即可。 */
    fun encrypt(plain: String): String {
        if (plain.isEmpty()) return ""
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, secretKey())
            val encrypted = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
            Base64.encodeToString(cipher.iv + encrypted, Base64.NO_WRAP)
        } catch (e: Exception) {
            android.util.Log.e("SecureStore", "加密失败（Keystore 异常？），本次敏感配置未落盘", e)
            CrashLogger.log("SecureStore", "加密失败，敏感配置本次未保存", e)
            ""
        }
    }

    /** 解密；任何失败都返回空串（等价于"没配置"，由调用方走正常提示流程） */
    fun decrypt(stored: String): String {
        if (stored.isEmpty()) return ""
        return try {
            val data = Base64.decode(stored, Base64.NO_WRAP)
            if (data.size <= IV_BYTES) return ""
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.DECRYPT_MODE, secretKey(),
                GCMParameterSpec(TAG_BITS, data, 0, IV_BYTES)
            )
            String(cipher.doFinal(data, IV_BYTES, data.size - IV_BYTES), Charsets.UTF_8)
        } catch (_: Exception) {
            ""
        }
    }
}
