package io.github.nikitau.spruthubhelper.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

class HealthPermissionsRationaleActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            SprutHelperTheme {
                HealthPrivacyNotice(onClose = ::finish)
            }
        }
    }
}

@Composable
private fun HealthPrivacyNotice(onClose: () -> Unit) {
    SprutBackdrop {
        Column(
            modifier = Modifier.fillMaxSize().padding(20.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = SprutTileShape,
                colors = CardDefaults.cardColors(containerColor = SprutSurfaceLow),
                border = BorderStroke(1.dp, SprutGlassBorder),
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Text(
                        "Как используются данные здоровья",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        "SprutHub Helper читает только выбранные вами показатели из Health Connect и записывает их в ваш локальный SprutHub по домашней сети.",
                    )
                    Text(
                        "Медицинские данные не отправляются в облако SprutHub, на серверы разработчика или в сторонние аналитические сервисы. Доступ можно в любой момент отозвать в настройках Health Connect.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        "Пароль SprutHub хранится в Android Keystore. Резервное копирование настроек приложения отключено.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Button(onClick = onClose, modifier = Modifier.fillMaxWidth()) { Text("Понятно") }
                }
            }
        }
    }
}
