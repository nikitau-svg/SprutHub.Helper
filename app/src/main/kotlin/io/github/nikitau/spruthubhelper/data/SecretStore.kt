package io.github.nikitau.spruthubhelper.data

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal class SecretStore(context: Context) {
    private val coordinator = SecretCredentialCoordinator(
        storage = SharedPreferencesSecretStorage(
            context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE),
        ),
        cryptor = AndroidSecretCryptor(),
    )

    fun readPasswords(): StoredHubPasswords = coordinator.read()

    fun updatePasswords(update: HubPasswordUpdate) {
        coordinator.update(update)
    }

    private companion object {
        const val PREFERENCES_NAME = "encrypted_credentials"
    }
}

internal class StoredHubPasswords(
    val localPassword: String,
    val cloudPassword: String,
) {
    val legacyCompatiblePassword: String
        get() = localPassword.takeIf { it == cloudPassword }.orEmpty()

    override fun toString(): String =
        "StoredHubPasswords(localPassword=<redacted>, cloudPassword=<redacted>)"
}

internal enum class SecretSlot {
    LEGACY,
    LOCAL,
    CLOUD,
}

internal data class EncryptedSecret(
    val ciphertext: String,
    val iv: String,
)

internal interface SecretStorage {
    fun read(slot: SecretSlot): EncryptedSecret?
    fun write(slot: SecretSlot, secret: EncryptedSecret): Boolean
    fun remove(slot: SecretSlot): Boolean
}

internal interface SecretCryptor {
    fun encrypt(plaintext: String): EncryptedSecret
    fun decrypt(secret: EncryptedSecret): String
}

/**
 * Migrates the original single encrypted password lazily and safely.
 *
 * The legacy entry is copied into each missing slot and is removed only after
 * both new entries can be read back successfully. If a write or verification
 * fails, reads keep falling back to the legacy value so an upgrade cannot lose
 * access to either endpoint.
 */
internal class SecretCredentialCoordinator(
    private val storage: SecretStorage,
    private val cryptor: SecretCryptor,
) {
    @Synchronized
    fun read(): StoredHubPasswords {
        val legacy = readValue(SecretSlot.LEGACY)
        var local = readValue(SecretSlot.LOCAL)
        var cloud = readValue(SecretSlot.CLOUD)

        if (legacy != null) {
            if (local == null && migrate(SecretSlot.LOCAL, legacy)) {
                local = readValue(SecretSlot.LOCAL)
            }
            if (cloud == null && migrate(SecretSlot.CLOUD, legacy)) {
                cloud = readValue(SecretSlot.CLOUD)
            }
            if (local != null && cloud != null) {
                storage.remove(SecretSlot.LEGACY)
            }
        }

        return StoredHubPasswords(
            localPassword = local ?: legacy.orEmpty(),
            cloudPassword = cloud ?: legacy.orEmpty(),
        )
    }

    @Synchronized
    fun update(update: HubPasswordUpdate) {
        // Attempt migration before an independent update so clearing one slot
        // cannot erase the fallback still needed by the other slot.
        read()
        update.localPassword?.let { replace(SecretSlot.LOCAL, it) }
        update.cloudPassword?.let { replace(SecretSlot.CLOUD, it) }
    }

    private fun migrate(slot: SecretSlot, plaintext: String): Boolean = runCatching {
        writeAndVerify(slot, plaintext)
    }.getOrDefault(false)

    private fun replace(slot: SecretSlot, plaintext: String) {
        if (plaintext.isEmpty()) {
            check(storage.remove(slot)) { "Не удалось очистить защищённый пароль SprutHub" }
        } else {
            check(writeAndVerify(slot, plaintext)) { "Не удалось сохранить защищённый пароль SprutHub" }
        }
    }

    private fun writeAndVerify(slot: SecretSlot, plaintext: String): Boolean {
        val encrypted = cryptor.encrypt(plaintext)
        return storage.write(slot, encrypted) && readValue(slot) == plaintext
    }

    private fun readValue(slot: SecretSlot): String? {
        val encrypted = storage.read(slot) ?: return null
        return runCatching { cryptor.decrypt(encrypted) }.getOrNull()
    }
}

private class SharedPreferencesSecretStorage(
    private val preferences: SharedPreferences,
) : SecretStorage {
    override fun read(slot: SecretSlot): EncryptedSecret? {
        val keys = slot.keys
        val ciphertext = preferences.getString(keys.ciphertext, null) ?: return null
        val iv = preferences.getString(keys.iv, null) ?: return null
        return EncryptedSecret(ciphertext, iv)
    }

    override fun write(slot: SecretSlot, secret: EncryptedSecret): Boolean {
        val keys = slot.keys
        return preferences.edit()
            .putString(keys.ciphertext, secret.ciphertext)
            .putString(keys.iv, secret.iv)
            .commit()
    }

    override fun remove(slot: SecretSlot): Boolean {
        val keys = slot.keys
        return preferences.edit()
            .remove(keys.ciphertext)
            .remove(keys.iv)
            .commit()
    }

    private data class PreferenceKeys(val ciphertext: String, val iv: String)

    private val SecretSlot.keys: PreferenceKeys
        get() = when (this) {
            SecretSlot.LEGACY -> PreferenceKeys("password_ciphertext", "password_iv")
            SecretSlot.LOCAL -> PreferenceKeys("local_password_ciphertext_v2", "local_password_iv_v2")
            SecretSlot.CLOUD -> PreferenceKeys("cloud_password_ciphertext_v2", "cloud_password_iv_v2")
        }
}

private class AndroidSecretCryptor : SecretCryptor {
    override fun encrypt(plaintext: String): EncryptedSecret {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        return EncryptedSecret(
            ciphertext = Base64.encodeToString(
                cipher.doFinal(plaintext.toByteArray(StandardCharsets.UTF_8)),
                Base64.NO_WRAP,
            ),
            iv = Base64.encodeToString(cipher.iv, Base64.NO_WRAP),
        )
    }

    override fun decrypt(secret: EncryptedSecret): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            getOrCreateKey(),
            GCMParameterSpec(128, Base64.decode(secret.iv, Base64.NO_WRAP)),
        )
        return cipher.doFinal(Base64.decode(secret.ciphertext, Base64.NO_WRAP))
            .toString(StandardCharsets.UTF_8)
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
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

    private companion object {
        // Keep the original alias: it is required to decrypt the pre-v2 secret.
        const val KEY_ALIAS = "spruthub_helper_password_v1"
        const val KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
