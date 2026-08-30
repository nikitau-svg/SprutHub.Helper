package io.github.nikitau.spruthubhelper.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
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
            MaterialTheme {
                HealthPrivacyNotice(onClose = ::finish)
            }
        }
    }
}

@Composable
private fun HealthPrivacyNotice(onClose: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Как используются данные здоровья", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text(
            "SprutHub Helper читает только выбранные вами показатели из Health Connect и записывает их в ваш локальный SprutHub по домашней сети.",
        )
        Text(
            "Медицинские данные не отправляются в облако SprutHub, на серверы разработчика или в сторонние аналитические сервисы. Доступ можно в любой момент отозвать в настройках Health Connect.",
        )
        Text(
            "Пароль SprutHub хранится в Android Keystore. Резервное копирование настроек приложения отключено.",
        )
        Button(onClick = onClose) { Text("Понятно") }
    }
}
