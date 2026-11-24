package ai.norva.app.ui

import ai.norva.app.AppContainer
import ai.norva.app.vm.SettingsViewModel
import ai.norva.app.vm.VmFactory
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(container: AppContainer, onStudio: () -> Unit, onBack: () -> Unit) {
    val vm: SettingsViewModel = viewModel(factory = VmFactory(container))
    var text by remember { mutableStateOf(vm.systemPrompt) }

    var cfg by remember { mutableStateOf(container.cloud.config.value) }
    var feedback by remember { mutableStateOf("") }

    AppBackground {
        Scaffold(
            containerColor = Color.Transparent,
            topBar = {
                TopAppBar(
                    title = { Text("Настройки") },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
                )
            },
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp)) {
                Button(onClick = onStudio) { Text("NØRVA Studio") }
                Text("Личность ассистента (системный промпт)", style = MaterialTheme.typography.titleSmall)
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    minLines = 4,
                )
                Row {
                    Button(onClick = { vm.save(text) }) { Text("Сохранить") }
                    TextButton(onClick = {
                        vm.reset()
                        text = vm.systemPrompt
                    }) { Text("Сбросить") }
                }
                Text("Облачные провайдеры", style = MaterialTheme.typography.titleMedium)
                Text("Локальный чат работает на устройстве. В облачном режиме история и прикреплённый снимок передаются выбранному API. Ключи действуют до закрытия процесса.")
                TextButton(onClick = { cfg = cfg.copy(chatEnabled = !cfg.chatEnabled) }) {
                    Text(if (cfg.chatEnabled) "Чат: облачный" else "Чат: локальный")
                }
                OutlinedTextField(cfg.base, { cfg = cfg.copy(base = it.trim(), chatKey = "") }, label = { Text("Адрес OpenAI-совместимого API") }, singleLine = true)
                OutlinedTextField(cfg.model, { cfg = cfg.copy(model = it.trim()) }, label = { Text("Модель чата с поддержкой фото") }, singleLine = true)
                OutlinedTextField(cfg.chatKey, { cfg = cfg.copy(chatKey = it.trim()) }, label = { Text("Ключ чата") }, singleLine = true, keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Password, autoCorrectEnabled = false), visualTransformation = PasswordVisualTransformation())
                OutlinedTextField(cfg.mediaKey, { cfg = cfg.copy(mediaKey = it.trim()) }, label = { Text("Ключ fal.ai для FLUX и Wan") }, singleLine = true, keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Password, autoCorrectEnabled = false), visualTransformation = PasswordVisualTransformation())
                Button(onClick = {
                    feedback = try { container.cloud.configure(cfg); "Настройки API сохранены" }
                    catch (e: Exception) { e.message ?: "Проверьте настройки" }
                }) { Text("Применить настройки API") }
                if (feedback.isNotEmpty()) Text(feedback)
            }
        }
    }
}
