package io.github.nikitau.spruthubhelper.controls

import android.service.controls.Control
import android.service.controls.ControlsProviderService
import android.service.controls.actions.BooleanAction
import android.service.controls.actions.CommandAction
import android.service.controls.actions.ControlAction
import android.service.controls.actions.FloatAction
import io.github.nikitau.spruthubhelper.AppGraph
import java.util.concurrent.Flow.Publisher
import java.util.function.Consumer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.jdk9.asPublisher
import kotlinx.coroutines.launch

class SprutControlsProviderService : ControlsProviderService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val repository get() = AppGraph.repository

    override fun createPublisherForAllAvailable(): Publisher<Control> = flow {
        val catalog = repository.refreshIfStale().getOrElse { repository.catalog.value }
        catalog.controls.forEach { emit(ControlFactory.stateless(this@SprutControlsProviderService, it)) }
    }.asPublisher(scope.coroutineContext)

    override fun createPublisherForSuggested(): Publisher<Control> = flow {
        val catalog = repository.refreshIfStale().getOrElse { repository.catalog.value }
        val selectedIds = AppGraph.settings.panelItems.first().map { it.controlId }
        val byId = catalog.controls.associateBy { it.id }
        val suggestions = (
            selectedIds.mapNotNull(byId::get) + catalog.controls.filterNot { it.id in selectedIds }
            ).distinctBy { it.id }.take(6)
        suggestions.forEach { emit(ControlFactory.stateless(this@SprutControlsProviderService, it)) }
    }.asPublisher(scope.coroutineContext)

    override fun createPublisherFor(controlIds: MutableList<String>): Publisher<Control> = flow {
        repository.refreshIfStale()
        repository.catalog.collect { catalog ->
            val byId = catalog.controls.associateBy { it.id }
            controlIds.mapNotNull(byId::get).forEach {
                emit(ControlFactory.stateful(this@SprutControlsProviderService, it))
            }
        }
    }.asPublisher(scope.coroutineContext)

    override fun performControlAction(
        controlId: String,
        action: ControlAction,
        consumer: Consumer<Int>,
    ) {
        scope.launch {
            val result = when (action) {
                is BooleanAction -> repository.setBoolean(controlId, action.newState)
                is FloatAction -> repository.setRange(controlId, action.newValue.toDouble())
                is CommandAction -> repository.execute(controlId)
                else -> Result.failure(IllegalArgumentException("Неподдерживаемая команда"))
            }
            consumer.accept(if (result.isSuccess) ControlAction.RESPONSE_OK else ControlAction.RESPONSE_FAIL)
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
