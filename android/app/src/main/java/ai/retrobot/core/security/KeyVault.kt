package ai.retrobot.core.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Stores API keys encrypted under a hardware-backed Android Keystore key.
 *
 * A key vault rather than encrypted preferences, because the keys need to
 * live in a database (one per provider account) and Room does not encrypt
 * its own storage. The database holds ciphertext; only this class, and the
 * Keystore, can turn it back into a usable key.
 *
 * The master key never leaves the Keystore. It is generated on first use
 * with `setUserAuthenticationRequired(false)` so a background battle can
 * still run while the screen is locked, and with randomised encryption
 * required so the same key never produces the same ciphertext twice.
 */
class KeyVault(context: Context) {

    private val appContext = context.applicationContext

    /**
     * Encrypt a secret for storage.
     *
     * @return base64 of `iv || ciphertext`, or an empty string when the input
     *   is empty.
     */
    fun encrypt(plainText: String): String {
        if (plainText.isEmpty()) return ""
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, secretKey())
            val iv = cipher.iv
            val encrypted = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))
            Base64.encodeToString(iv + encrypted, Base64.NO_WRAP)
        } catch (e: Exception) {
            // A key that cannot be created or used is a hard failure: storing
            // the secret in the clear would be worse than not storing it.
            Log.e(TAG, "Could not encrypt secret", e)
            throw IllegalStateException("The device keystore is unavailable", e)
        }
    }

    /**
     * Decrypt a secret read back from storage.
     *
     * @return the plaintext, or an empty string when the value cannot be
     *   decrypted — which happens when the keystore key was lost, for
     *   instance after a factory reset.
     */
    fun decrypt(cipherText: String): String {
        if (cipherText.isEmpty()) return ""
        return try {
            val raw = Base64.decode(cipherText, Base64.NO_WRAP)
            val iv = raw.copyOfRange(0, IV_LENGTH)
            val body = raw.copyOfRange(IV_LENGTH, raw.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(TAG_LENGTH, iv))
            String(cipher.doFinal(body), Charsets.UTF_8)
        } catch (e: Exception) {
            Log.w(TAG, "Could not decrypt secret; the key is probably gone", e)
            ""
        }
    }

    /** Whether a key for this vault exists, i.e. whether secrets are usable. */
    fun isAvailable(): Boolean = try {
        secretKey()
        true
    } catch (e: Exception) {
        false
    }

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }

    private companion object {
        const val TAG = "KeyVault"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "retrobot.api_key.v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_LENGTH = 12
        const val TAG_LENGTH = 128
    }
}
