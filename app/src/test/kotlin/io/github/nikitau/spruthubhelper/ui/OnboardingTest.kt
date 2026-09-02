package io.github.nikitau.spruthubhelper.ui

import io.github.nikitau.spruthubhelper.data.ConnectionPhase
import io.github.nikitau.spruthubhelper.data.ConnectionStatus
import io.github.nikitau.spruthubhelper.data.SprutCatalog
import io.github.nikitau.spruthubhelper.data.SprutControl
import org.junit.Assert.assertEquals
import org.junit.Test

class OnboardingTest {
    private val catalog = SprutCatalog(
        controls = listOf(
            SprutControl(
                id = "fixture-control",
                accessoryId = "fixture-accessory",
                serviceId = "fixture-service",
                characteristicId = "fixture-characteristic",
                title = "Fixture",
            ),
        ),
    )

    @Test
    fun `connection step advances only after connection and catalog are confirmed`() {
        val ready = MainUiState(
            connection = ConnectionStatus(
                phase = ConnectionPhase.CONNECTED_LOCAL,
                message = "Подключено",
            ),
            catalog = catalog,
        )

        assertEquals(
            OnboardingStep.READY,
            advanceOnboardingStep(OnboardingStep.CONNECTION, ready),
        )
    }

    @Test
    fun `connected transport without catalog remains on mandatory step`() {
        val noCatalog = MainUiState(
            connection = ConnectionStatus(
                phase = ConnectionPhase.CONNECTED_CLOUD,
                message = "Подключено",
            ),
        )

        assertEquals(
            OnboardingStep.CONNECTION,
            advanceOnboardingStep(OnboardingStep.CONNECTION, noCatalog),
        )
    }

    @Test
    fun `welcome never skips itself because an old catalog is cached`() {
        val ready = MainUiState(
            connection = ConnectionStatus(
                phase = ConnectionPhase.CONNECTED_LOCAL,
                message = "Подключено",
            ),
            catalog = catalog,
        )

        assertEquals(
            OnboardingStep.WELCOME,
            advanceOnboardingStep(OnboardingStep.WELCOME, ready),
        )
        assertEquals(
            OnboardingStep.SURFACES,
            advanceOnboardingStep(OnboardingStep.SURFACES, ready),
        )
    }
}
