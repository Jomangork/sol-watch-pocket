package local.solwatch.pocket

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * The SOL API key, encrypted with a key that never leaves the Android Keystore.
 * Test builds also carry a built-in key (BuildConfig.TEST_API_KEY), used until the user saves one.
 */
object ApiKey {
    private const val ALIAS = "pocket_api_key"
    private const val PREF = "api_key_encrypted"

    fun get(context: Context): String? = stored(context) ?: BuildConfig.TEST_API_KEY.ifEmpty { null }

    fun isBuiltIn(context: Context) = stored(context) == null && BuildConfig.TEST_API_KEY.isNotEmpty()

    fun save(context: Context, key: String) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val sealed = cipher.iv + cipher.doFinal(key.trim().toByteArray())
        Settings.prefs(context).edit().putString(PREF, Base64.encodeToString(sealed, Base64.NO_WRAP)).apply()
    }

    fun clear(context: Context) = Settings.prefs(context).edit().remove(PREF).apply()

    private fun stored(context: Context): String? {
        val sealed = Settings.prefs(context).getString(PREF, null)?.let { Base64.decode(it, Base64.NO_WRAP) }
            ?: return null
        return runCatching {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, sealed, 0, 12))
            String(cipher.doFinal(sealed, 12, sealed.size - 12))
        }.getOrNull()
    }

    private fun secretKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build()
        )
        return generator.generateKey()
    }
}
