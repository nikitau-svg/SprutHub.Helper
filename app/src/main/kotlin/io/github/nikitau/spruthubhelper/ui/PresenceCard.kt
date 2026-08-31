package io.github.nikitau.spruthubhelper.ui

import android.os.Build
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.LocationOn
import androidx.compose.material.icons.rounded.MyLocation
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.nikitau.spruthubhelper.presence.PresenceUiState
import io.github.nikitau.spruthubhelper.presence.PresenceZone
import java.text.DateFormat
import java.util.Date
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.collect

@Composable
internal fun PresenceCard(
    presence: PresenceUiState,
    ui: MainUiState,
    viewModel: MainViewModel,
    onRequestForegroundLocation: () -> Unit,
    onOpenBackgroundLocationSettings: () -> Unit,
    expandedByDefault: Boolean = false,
) {
    var expanded by rememberSaveable(expandedByDefault) { mutableStateOf(expandedByDefault) }
    var roomMenu by remember { mutableStateOf(false) }
    var name by rememberSaveable { mutableStateOf("Дом") }
    var latitude by rememberSaveable { mutableStateOf("") }
    var longitude by rememberSaveable { mutableStateOf("") }
    var radius by rememberSaveable { mutableStateOf("150") }
    var publishDistance by rememberSaveable { mutableStateOf(true) }
    var selectedRoomId by remember(ui.catalog.rooms) {
        mutableStateOf(ui.catalog.rooms.firstOrNull()?.id.orEmpty())
    }
    var deletingZone by remember { mutableStateOf<PresenceZone?>(null) }
    val selectedRoom = ui.catalog.rooms.firstOrNull { it.id == selectedRoomId }
    val guidance = presenceGuidance(presence)

    LaunchedEffect(viewModel) {
        viewModel.coordinateResults.collect { (lat, lon) ->
            latitude = "%.6f".format(java.util.Locale.US, lat)
            longitude = "%.6f".format(java.util.Locale.US, lon)
        }
    }

    OutlinedCard(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        shape = RoundedCornerShape(20.dp),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer) {
                    Icon(
                        Icons.Rounded.LocationOn,
                        null,
                        Modifier.padding(9.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
                Spacer(Modifier.size(10.dp))
                Column(Modifier.weight(1f)) {
                    Text("Зоны и присутствие", fontWeight = FontWeight.Bold)
                    Text(
                        if (presence.zones.isEmpty()) "Не настроено" else presence.message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = { expanded = !expanded }) {
                    Icon(if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, null)
                }
            }

            if (presence.zones.isNotEmpty()) {
                Text(
                    "${presence.zones.count(PresenceZone::enabled)} активных из ${presence.zones.size} · " +
                        if (presence.geofencesRegistered) "Android следит за границами" else "геозоны требуют внимания",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            NextActionCard(
                guidance = guidance,
                actionEnabled = !presence.busy,
                onAction = { action ->
                    when (action) {
                        GuidanceAction.REQUEST_FOREGROUND_LOCATION -> onRequestForegroundLocation()
                        GuidanceAction.OPEN_BACKGROUND_LOCATION_SETTINGS -> onOpenBackgroundLocationSettings()
                        GuidanceAction.FILL_CURRENT_LOCATION -> {
                            expanded = true
                            viewModel.requestCurrentCoordinates()
                        }
                        GuidanceAction.SYNC_PRESENCE -> viewModel.syncPresenceZones()
                        else -> Unit
                    }
                },
            )

            if (presence.duplicateZoneNames.isNotEmpty()) {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.errorContainer,
                ) {
                    Text(
                        "Найдены зоны с одинаковым названием: ${presence.duplicateZoneNames.joinToString()}. " +
                            "Helper сохранил их раздельные ID и не будет удалять автоматически; оставьте нужную ниже.",
                        modifier = Modifier.padding(12.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
            }

            AnimatedVisibility(expanded) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Разрешения", fontWeight = FontWeight.SemiBold)
                    PermissionLine(
                        "Точная геопозиция",
                        presence.permissions.preciseGranted,
                        "разрешена",
                        "нужна для радиуса и расстояния",
                    )
                    PermissionLine(
                        "Геопозиция в фоне",
                        presence.permissions.backgroundGranted,
                        "разрешена всегда",
                        "иначе вход и выход могут не прийти в фоне",
                    )
                    if (presence.permissions.preciseGranted && !presence.permissions.backgroundGranted) {
                        Text(
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                                "Android выдаёт доступ «Всегда» только на странице разрешений приложения."
                            } else {
                                "Выберите постоянный доступ к геопозиции."
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    Text(
                        "Координаты хранятся только на этом телефоне. В SprutHub отправляются состояние присутствия и, если включено, расстояние.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    if (presence.zones.isNotEmpty()) {
                        HorizontalDivider()
                        Text("Созданные зоны", fontWeight = FontWeight.SemiBold)
                        presence.zones.forEach { zone ->
                            ZoneRow(zone, presence.busy, viewModel, onDelete = { deletingZone = zone })
                        }
                    }

                    HorizontalDivider()
                    Text("Новая зона", fontWeight = FontWeight.SemiBold)
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text("Название") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                    OutlinedButton(
                        onClick = viewModel::requestCurrentCoordinates,
                        modifier = Modifier.fillMaxWidth(),
                        enabled = presence.permissions.preciseGranted && !presence.busy,
                    ) {
                        Icon(Icons.Rounded.MyLocation, null)
                        Spacer(Modifier.size(8.dp))
                        Text("Использовать текущую точку")
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = latitude,
                            onValueChange = { latitude = it.filterCoordinateCharacters() },
                            label = { Text("Широта") },
                            modifier = Modifier.weight(1f),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            singleLine = true,
                        )
                        OutlinedTextField(
                            value = longitude,
                            onValueChange = { longitude = it.filterCoordinateCharacters() },
                            label = { Text("Долгота") },
                            modifier = Modifier.weight(1f),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            singleLine = true,
                        )
                    }
                    OutlinedTextField(
                        value = radius,
                        onValueChange = { radius = it.filter(Char::isDigit).take(5) },
                        label = { Text("Радиус, м") },
                        supportingText = { Text("Рекомендуется 150 м; минимум 100 м") },
                        modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Передавать расстояние")
                            Text(
                                "Добавит C_Distance в тот же датчик присутствия",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(checked = publishDistance, onCheckedChange = { publishDistance = it })
                    }
                    Box {
                        OutlinedButton(
                            onClick = { roomMenu = true },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = ui.catalog.rooms.isNotEmpty(),
                        ) { Text(selectedRoom?.name ?: "Сначала загрузите комнаты SprutHub") }
                        DropdownMenu(expanded = roomMenu, onDismissRequest = { roomMenu = false }) {
                            ui.catalog.rooms.forEach { room ->
                                DropdownMenuItem(
                                    text = { Text(room.name) },
                                    onClick = {
                                        selectedRoomId = room.id
                                        roomMenu = false
                                    },
                                )
                            }
                        }
                    }
                    val lat = latitude.toDoubleOrNull()
                    val lon = longitude.toDoubleOrNull()
                    val radiusMeters = radius.toDoubleOrNull()
                    Button(
                        onClick = {
                            viewModel.addPresenceZone(
                                name,
                                lat ?: 0.0,
                                lon ?: 0.0,
                                radiusMeters ?: 150.0,
                                selectedRoomId,
                                publishDistance,
                            )
                        },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !presence.busy &&
                            presence.permissions.preciseGranted &&
                            name.isNotBlank() &&
                            lat != null && lat in -90.0..90.0 &&
                            lon != null && lon in -180.0..180.0 &&
                            radiusMeters != null &&
                            radiusMeters in PresenceZone.MIN_RADIUS_METERS..PresenceZone.MAX_RADIUS_METERS &&
                            selectedRoomId.isNotBlank(),
                    ) { Text("Создать присутствие в SprutHub") }
                    Text(
                        "Вход/выход обрабатывает системная геозона даже при спящем приложении. Android может доставить фоновое событие с задержкой в несколько минут; расстояние дополнительно обновляется примерно раз в 15 минут.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }

    deletingZone?.let { zone ->
        AlertDialog(
            onDismissRequest = { deletingZone = null },
            title = { Text("Удалить зону «${zone.name}»?") },
            text = { Text("Приложение удалит геозону с телефона и созданный виртуальный аксессуар из SprutHub.") },
            confirmButton = {
                TextButton(onClick = {
                    deletingZone = null
                    viewModel.removePresenceZone(zone.id)
                }) { Text("Удалить") }
            },
            dismissButton = { TextButton(onClick = { deletingZone = null }) { Text("Отмена") } },
        )
    }
}

@Composable
private fun ZoneRow(
    zone: PresenceZone,
    busy: Boolean,
    viewModel: MainViewModel,
    onDelete: () -> Unit,
) {
    OutlinedCard(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(zone.name, fontWeight = FontWeight.SemiBold)
                    Text(
                        when (zone.isInside) {
                            true -> "В зоне"
                            false -> "Вне зоны"
                            null -> "Состояние ещё не определено"
                        },
                        color = if (zone.isInside == true) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = zone.enabled,
                    onCheckedChange = { viewModel.setPresenceZoneEnabled(zone.id, it) },
                    enabled = !busy,
                )
                IconButton(onClick = onDelete, enabled = !busy) {
                    Icon(Icons.Rounded.DeleteOutline, contentDescription = "Удалить зону")
                }
            }
            val details = buildList {
                add("радиус ${zone.radiusMeters.roundToInt()} м")
                if (zone.publishDistance) {
                    add(zone.lastDistanceMeters?.let { "расстояние ${it.roundToInt()} м" } ?: "расстояние ожидается")
                }
                add(if (zone.binding == null) "SprutHub не привязан" else "SprutHub готов")
            }.joinToString(" · ")
            Text(details, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            zone.lastUpdatedEpochMs?.let { timestamp ->
                Text(
                    "Обновлено ${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(timestamp))}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun PermissionLine(
    title: String,
    ready: Boolean,
    readyText: String,
    missingText: String,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(title, modifier = Modifier.weight(1f))
        Text(
            if (ready) "✓ $readyText" else "• $missingText",
            style = MaterialTheme.typography.labelSmall,
            color = if (ready) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
        )
    }
}

private fun String.filterCoordinateCharacters(): String = filter { character ->
    character.isDigit() || character == '-' || character == '.' || character == ','
}.replace(',', '.').take(14)
