package io.github.nikitau.spruthubhelper.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SecretCredentialCoordinatorTest {
    @Test
    fun migratesLegacyPasswordToBothSlotsBeforeRemovingIt() {
        val storage = FakeSecretStorage().apply {
            seed(SecretSlot.LEGACY, "legacy-password")
        }

        val passwords = SecretCredentialCoordinator(storage, FakeSecretCryptor()).read()

        assertEquals("legacy-password", passwords.localPassword)
        assertEquals("legacy-password", passwords.cloudPassword)
        assertNotNull(storage.read(SecretSlot.LOCAL))
        assertNotNull(storage.read(SecretSlot.CLOUD))
        assertNull(storage.read(SecretSlot.LEGACY))
    }

    @Test
    fun keepsLegacyFallbackWhenOneMigrationWriteFails() {
        val storage = FakeSecretStorage().apply {
            seed(SecretSlot.LEGACY, "legacy-password")
            failedWrites += SecretSlot.CLOUD
        }
        val coordinator = SecretCredentialCoordinator(storage, FakeSecretCryptor())

        val firstRead = coordinator.read()

        assertEquals("legacy-password", firstRead.localPassword)
        assertEquals("legacy-password", firstRead.cloudPassword)
        assertNotNull(storage.read(SecretSlot.LOCAL))
        assertNull(storage.read(SecretSlot.CLOUD))
        assertNotNull(storage.read(SecretSlot.LEGACY))

        storage.failedWrites.clear()
        val recoveredRead = coordinator.read()

        assertEquals("legacy-password", recoveredRead.cloudPassword)
        assertNotNull(storage.read(SecretSlot.CLOUD))
        assertNull(storage.read(SecretSlot.LEGACY))
    }

    @Test
    fun migrationNeverOverwritesAnExistingIndependentPassword() {
        val storage = FakeSecretStorage().apply {
            seed(SecretSlot.LEGACY, "legacy-password")
            seed(SecretSlot.LOCAL, "new-local-password")
        }

        val passwords = SecretCredentialCoordinator(storage, FakeSecretCryptor()).read()

        assertEquals("new-local-password", passwords.localPassword)
        assertEquals("legacy-password", passwords.cloudPassword)
        assertNull(storage.read(SecretSlot.LEGACY))
    }

    @Test
    fun updatesAndClearsEachPasswordIndependently() {
        val storage = FakeSecretStorage().apply {
            seed(SecretSlot.LEGACY, "legacy-password")
        }
        val coordinator = SecretCredentialCoordinator(storage, FakeSecretCryptor())

        coordinator.update(HubPasswordUpdate(localPassword = "local-password"))
        var passwords = coordinator.read()
        assertEquals("local-password", passwords.localPassword)
        assertEquals("legacy-password", passwords.cloudPassword)

        coordinator.update(HubPasswordUpdate(cloudPassword = "cloud-password"))
        passwords = coordinator.read()
        assertEquals("local-password", passwords.localPassword)
        assertEquals("cloud-password", passwords.cloudPassword)

        coordinator.update(HubPasswordUpdate(localPassword = ""))
        passwords = coordinator.read()
        assertEquals("", passwords.localPassword)
        assertEquals("cloud-password", passwords.cloudPassword)
    }

    @Test
    fun secretBearingModelsAlwaysRedactTheirStringRepresentation() {
        val stored = StoredHubPasswords("local-needle", "cloud-needle")
        val update = HubPasswordUpdate("updated-local-needle", "updated-cloud-needle")

        assertFalse(stored.toString().contains("local-needle"))
        assertFalse(stored.toString().contains("cloud-needle"))
        assertFalse(update.toString().contains("updated-local-needle"))
        assertFalse(update.toString().contains("updated-cloud-needle"))
        assertTrue(stored.toString().contains("<redacted>"))
    }

    private class FakeSecretStorage : SecretStorage {
        private val values = mutableMapOf<SecretSlot, EncryptedSecret>()
        val failedWrites = mutableSetOf<SecretSlot>()

        fun seed(slot: SecretSlot, plaintext: String) {
            values[slot] = FakeSecretCryptor().encrypt(plaintext)
        }

        override fun read(slot: SecretSlot): EncryptedSecret? = values[slot]

        override fun write(slot: SecretSlot, secret: EncryptedSecret): Boolean {
            if (slot in failedWrites) return false
            values[slot] = secret
            return true
        }

        override fun remove(slot: SecretSlot): Boolean {
            values.remove(slot)
            return true
        }
    }

    private class FakeSecretCryptor : SecretCryptor {
        override fun encrypt(plaintext: String): EncryptedSecret =
            EncryptedSecret(ciphertext = "encrypted:$plaintext", iv = "test-iv")

        override fun decrypt(secret: EncryptedSecret): String =
            secret.ciphertext.removePrefix("encrypted:")
    }
}
