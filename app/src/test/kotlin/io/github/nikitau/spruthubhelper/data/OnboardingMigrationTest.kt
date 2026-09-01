package io.github.nikitau.spruthubhelper.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OnboardingMigrationTest {
    @Test
    fun `fresh installation sees onboarding`() {
        assertTrue(shouldShowInitialOnboarding(storedVersion = null, storedSerial = ""))
    }

    @Test
    fun `configured installation from an older beta is migrated silently`() {
        assertFalse(shouldShowInitialOnboarding(storedVersion = null, storedSerial = "FIXTURE-HUB"))
    }

    @Test
    fun `completed onboarding stays completed even if connection is later cleared`() {
        assertFalse(shouldShowInitialOnboarding(storedVersion = 1, storedSerial = ""))
    }

    @Test
    fun `fresh onboarding survives process death after connection was saved`() {
        assertTrue(shouldShowInitialOnboarding(storedVersion = -1, storedSerial = "FIXTURE-HUB"))
    }
}
