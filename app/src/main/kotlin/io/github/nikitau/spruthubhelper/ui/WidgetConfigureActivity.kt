package io.github.nikitau.spruthubhelper.ui

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.nikitau.spruthubhelper.AppGraph
import io.github.nikitau.spruthubhelper.data.AccessoryControlGroup
import io.github.nikitau.spruthubhelper.data.ConnectionPhase
import io.github.nikitau.spruthubhelper.data.SprutControl
import io.github.nikitau.spruthubhelper.data.groupControlsByAccessory
import io.github.nikitau.spruthubhelper.tiles.TileIconResolver
import io.github.nikitau.spruthubhelper.widget.SprutAppWidgetProvider
import io.github.nikitau.spruthubhelper.widget.WidgetAssignmentStore
import kotlinx.coroutines.launch

class WidgetConfigureActivity : ComponentActivity() {
    private val appWidgetId: Int by lazy {
        intent?.getIntExtra(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID,
        ) ?: AppWidgetManager.INVALID_APPWIDGET_ID
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setResult(Activity.RESULT_CANCELED)
        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }
        AppGraph.initialize(applicationContext)
        setContent {
            SprutHelperTheme {
                WidgetConfigureScreen(
                    appWidgetId = appWidgetId,
                    onCancel = ::finish,
                    onConfirm = ::finishConfiguration,
                )
            }
        }
    }

    private fun finishConfiguration(controlId: String) {
        WidgetAssignmentStore.save(this, appWidgetId, controlId)
        SprutAppWidgetProvider.updateWidget(this, appWidgetId)
        setResult(
            Activity.RESULT_OK,
            Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId),
        )
        finish()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WidgetConfigureScreen(
    appWidgetId: Int,
    onCancel: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    val context = LocalContext.current
    val catalog by AppGraph.repository.catalog.collectAsState()
    val connection by AppGraph.repository.connectionStatus.collectAsState()
    var query by rememberSaveable { mutableStateOf("") }
    var selectedId by rememberSaveable(appWidgetId) {
        mutableStateOf(WidgetAssignmentStore.controlId(context, appWidgetId).orEmpty())
    }
    val groups = remember(catalog.controls, query) {
        groupControlsByAccessory(catalog.controls).filter { it.matches(query) }
    }

    LaunchedEffect(appWidgetId) {
        AppGraph.repository.refreshIfStale(maxAgeMs = 0)
    }

    SprutBackdrop {
        Scaffold(
            containerColor = Color.Transparent,
            contentColor = SprutText,
            topBar = {
                TopAppBar(
                    title = { Text("Виджет SprutHub") },
                    navigationIcon = {
                        SprutHeaderIconButton(Icons.Rounded.Close, "Отмена", onCancel)
                    },
                    actions = {
                        SprutHeaderIconButton(
                            icon = Icons.Rounded.Refresh,
                            contentDescription = "Обновить каталог",
                            onClick = { AppGraph.applicationScope.launch { AppGraph.repository.refresh(true) } },
                        )
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Color.Transparent,
                        scrolledContainerColor = SprutBackground.copy(alpha = 0.92f),
                    ),
                )
            },
            bottomBar = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                ) {
                    Button(
                        onClick = { onConfirm(selectedId) },
                        enabled = selectedId.isNotBlank(),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            if (WidgetAssignmentStore.controlId(context, appWidgetId) == null) {
                                "Добавить виджет"
                            } else {
                                "Сохранить"
                            },
                        )
                    }
                }
            },
        ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 16.dp),
            ) {
                Text(
                    "Выберите устройство, сценарий или датчик. Нажатие на управляемый виджет отправит команду, датчик откроет приложение.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth(),
                    leadingIcon = { Icon(Icons.Rounded.Search, null) },
                    label = { Text("Поиск по устройствам и комнатам") },
                    singleLine = true,
                    shape = SprutControlShape,
                    colors = sprutTextFieldColors(),
                )
                Spacer(Modifier.height(12.dp))

                if (connection.phase == ConnectionPhase.CONNECTING && groups.isEmpty()) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(24.dp),
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                    }
                } else if (groups.isEmpty()) {
                    Text(
                        if (query.isNotBlank()) "Ничего не найдено" else connection.message.ifBlank {
                            "Каталог пуст. Сначала проверьте подключение в приложении."
                        },
                        modifier = Modifier.padding(vertical = 24.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        groups.groupBy(AccessoryControlGroup::room).forEach { (room, roomGroups) ->
                            item(key = "room:$room") {
                                Text(
                                    room,
                                    modifier = Modifier.padding(top = 8.dp, bottom = 2.dp),
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                            roomGroups.forEach { group ->
                                items(group.controls, key = SprutControl::id) { control ->
                                    WidgetControlChoice(
                                        accessoryTitle = group.title,
                                        serviceLabel = group.serviceLabel(control),
                                        control = control,
                                        selected = selectedId == control.id,
                                        onClick = { selectedId = control.id },
                                    )
                                }
                            }
                        }
                        item { Spacer(Modifier.height(80.dp)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun WidgetControlChoice(
    accessoryTitle: String,
    serviceLabel: String,
    control: SprutControl,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = SprutTileShape,
        colors = CardDefaults.cardColors(
            containerColor = if (selected) SprutAccent.copy(alpha = 0.11f) else SprutSurfaceLow,
        ),
        border = BorderStroke(
            1.dp,
            if (selected) SprutAccent.copy(alpha = 0.66f) else SprutGlassBorder,
        ),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                painter = painterResource(TileIconResolver.resource(control)),
                contentDescription = null,
                modifier = Modifier.size(30.dp),
                tint = if (selected) SprutAccent else Color.White,
            )
            Spacer(Modifier.size(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    if (serviceLabel == accessoryTitle) accessoryTitle else "$accessoryTitle · $serviceLabel",
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    listOf(control.subtitle, control.displayValue).filter(String::isNotBlank).joinToString(" · "),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
