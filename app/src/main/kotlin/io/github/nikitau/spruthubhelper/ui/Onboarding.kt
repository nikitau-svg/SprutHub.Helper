package io.github.nikitau.spruthubhelper.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DevicesOther
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Smartphone
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.nikitau.spruthubhelper.data.ConnectionPhase

internal enum class OnboardingStep {
    WELCOME,
    SURFACES,
    CONNECTION,
    READY,
}

internal fun advanceOnboardingStep(
    current: OnboardingStep,
    ui: MainUiState,
): OnboardingStep {
    if (current != OnboardingStep.CONNECTION) return current
    val connected = ui.connection.phase == ConnectionPhase.CONNECTED_LOCAL ||
        ui.connection.phase == ConnectionPhase.CONNECTED_CLOUD
    return if (connected && ui.catalog.controls.isNotEmpty()) OnboardingStep.READY else current
}

internal fun OnboardingStep.subtitle(): String = when (this) {
    OnboardingStep.WELCOME -> "Короткое знакомство"
    OnboardingStep.SURFACES -> "Три места для управления"
    OnboardingStep.CONNECTION -> "Единственный обязательный шаг"
    OnboardingStep.READY -> "Подключение подтверждено"
}

@Composable
internal fun OnboardingContent(
    step: OnboardingStep,
    ui: MainUiState,
    busy: Boolean,
    viewModel: MainViewModel,
    modifier: Modifier = Modifier,
    onStepChange: (OnboardingStep) -> Unit,
    onComplete: () -> Unit,
) {
    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(top = 12.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        when (step) {
            OnboardingStep.WELCOME -> item {
                OnboardingWelcomeCard(
                    onContinue = { onStepChange(OnboardingStep.SURFACES) },
                )
            }

            OnboardingStep.SURFACES -> item {
                OnboardingSurfaceGuideCard(
                    onContinue = { onStepChange(OnboardingStep.CONNECTION) },
                    onSkip = { onStepChange(OnboardingStep.CONNECTION) },
                )
            }

            OnboardingStep.CONNECTION -> {
                item {
                    OnboardingMessageCard(
                        eyebrow = "1 обязательный шаг",
                        title = "Подключите SprutHub",
                        detail = "Выберите режим и заполните данные. Helper сам проверит вход и каталог; до успешной проверки старые настройки не меняются.",
                        icon = Icons.Rounded.Settings,
                        accent = SprutAccent,
                    )
                }
                item {
                    ConnectionCard(
                        ui = ui,
                        busy = busy,
                        viewModel = viewModel,
                        expandedByDefault = true,
                    )
                }
            }

            OnboardingStep.READY -> item {
                OnboardingReadyCard(
                    ui = ui,
                    busy = busy,
                    onComplete = onComplete,
                )
            }
        }
    }
}

@Composable
private fun OnboardingWelcomeCard(onContinue: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        shape = SprutTileShape,
        colors = CardDefaults.cardColors(containerColor = SprutSurfaceLow),
        border = BorderStroke(1.dp, SprutGlassBorder),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = SprutAccent.copy(alpha = 0.13f),
                border = BorderStroke(1.dp, SprutAccent.copy(alpha = 0.20f)),
            ) {
                Icon(
                    Icons.Rounded.DevicesOther,
                    contentDescription = null,
                    modifier = Modifier.padding(12.dp).size(28.dp),
                    tint = SprutAccent,
                )
            }
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    "SprutHub — там, где удобно",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    "Helper переносит устройства, сценарии и датчики в системные поверхности Android. Полный интерфейс хаба остаётся в SprutHub.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            OnboardingFeatureRow(
                icon = Icons.Rounded.Security,
                title = "Без отдельного сервера",
                detail = "Дома приложение идёт прямо к хабу, вне дома — через выбранное beta-облако.",
            )
            OnboardingFeatureRow(
                icon = Icons.Rounded.Smartphone,
                title = "Только то, что выберете",
                detail = "Телефон, Health Connect и геозоны необязательны и настраиваются позже отдельно.",
            )
            OnboardingFeatureRow(
                icon = Icons.Rounded.Settings,
                title = "Три понятных формата",
                detail = "Сначала покажем разницу между виджетом, быстрой кнопкой и панелью. Затем останется один обязательный шаг подключения.",
            )
            Button(
                onClick = onContinue,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Начать настройку")
            }
            Text(
                "Обычно это занимает пару минут. Знакомство можно повторить из настроек.",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun OnboardingReadyCard(
    ui: MainUiState,
    busy: Boolean,
    onComplete: () -> Unit,
) {
    val channel = when (ui.connection.phase) {
        ConnectionPhase.CONNECTED_LOCAL -> "локальное подключение"
        ConnectionPhase.CONNECTED_CLOUD -> "облачное подключение"
        else -> "подключение"
    }
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        shape = SprutTileShape,
        colors = CardDefaults.cardColors(containerColor = SprutSurfaceLow),
        border = BorderStroke(1.dp, SprutGlassBorder),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = SprutSuccess.copy(alpha = 0.13f),
                border = BorderStroke(1.dp, SprutSuccess.copy(alpha = 0.22f)),
            ) {
                Icon(
                    Icons.Rounded.DevicesOther,
                    contentDescription = null,
                    modifier = Modifier.padding(12.dp).size(28.dp),
                    tint = SprutSuccess,
                )
            }
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    "Обязательная настройка завершена",
                    style = MaterialTheme.typography.labelLarge,
                    color = SprutSuccess,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    "SprutHub Helper готов",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    "Подтверждено $channel. Загружено элементов каталога: ${ui.catalog.controls.size}.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = Color.White.copy(alpha = 0.045f),
                border = BorderStroke(1.dp, SprutGlassBorder),
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    Text("Что дальше", fontWeight = FontWeight.SemiBold)
                    Text(
                        "На главном экране выберите «В панель» или «В шторку». Виджет добавляется через меню рабочего стола. Наглядную инструкцию всегда можно снова открыть в настройках.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Button(
                onClick = onComplete,
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Перейти к устройствам")
            }
        }
    }
}

@Composable
private fun OnboardingMessageCard(
    eyebrow: String,
    title: String,
    detail: String,
    icon: ImageVector,
    accent: Color,
) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        shape = SprutTileShape,
        colors = CardDefaults.cardColors(containerColor = SprutSurfaceLow),
        border = BorderStroke(1.dp, SprutGlassBorder),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = accent.copy(alpha = 0.12f),
                border = BorderStroke(1.dp, accent.copy(alpha = 0.18f)),
            ) {
                Icon(
                    icon,
                    contentDescription = null,
                    modifier = Modifier.padding(10.dp).size(22.dp),
                    tint = accent,
                )
            }
            Spacer(Modifier.size(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    eyebrow,
                    style = MaterialTheme.typography.labelLarge,
                    color = accent,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(title, fontWeight = FontWeight.Bold)
                Text(
                    detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun OnboardingFeatureRow(
    icon: ImageVector,
    title: String,
    detail: String,
) {
    Row(verticalAlignment = Alignment.Top) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = Color.White.copy(alpha = 0.055f),
        ) {
            Icon(
                icon,
                contentDescription = null,
                modifier = Modifier.padding(9.dp).size(20.dp),
                tint = SprutText,
            )
        }
        Spacer(Modifier.size(11.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, fontWeight = FontWeight.SemiBold)
            Text(
                detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
