package ai.norva.app.ui

import ai.norva.app.AppContainer
import ai.norva.app.vm.ModelsViewModel
import ai.norva.app.vm.VmFactory
import ai.norva.chat.InstallState
import ai.norva.policy.MemoryBudget
import ai.norva.policy.ModelSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel

private fun gb(bytes: Long) = "%.2f".format(bytes / 1_000_000_000.0)
private fun gib(bytes: Long) = "%.1f".format(bytes / MemoryBudget.GIB.toDouble())

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelsScreen(container: AppContainer, onBack: () -> Unit) {
    val vm: ModelsViewModel = viewModel(factory = VmFactory(container))
    val states by vm.states.collectAsStateWithLifecycle()
    var mem by remember(vm) { mutableStateOf(vm.memory()) }
    LaunchedEffect(vm) {
        while (true) {
            mem = vm.memory()
            kotlinx.coroutines.delay(5000)
        }
    }
    var chosen by remember { mutableStateOf(container.settings.selectedModel) }
    val budget = MemoryBudget.budgetBytes(mem)

    AppBackground {
        Scaffold(
            containerColor = Color.Transparent,
            topBar = {
                TopAppBar(
                    title = { Text("Model Hub") },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
                )
            },
        ) { padding ->
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item {
                    GlassPanel(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp)) {
                            Text("Память устройства", style = MaterialTheme.typography.titleSmall)
                            Text(
                                "Всего ${gib(mem.totalBytes)} ГиБ, свободно ${gib(mem.availBytes)} ГиБ. " +
                                    "Бюджет для ИИ сейчас ${gib(budget)} ГиБ.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                item { Text("Для фото установите текстовую модель и её F16-проектор из той же серии (0.8B, 2B или 4B). Для чата на телефоне с 3–4 ГБ начните с 0.8B.", modifier = Modifier.padding(12.dp)) }
                item { TextButton(onClick = { chosen = ""; container.settings.selectedModel = "" }) {
                    Text(if (chosen.isEmpty()) "✓ Автовыбор модели" else "Вернуть автовыбор")
                } }
                items(vm.specs, key = { it.id }) { spec ->
                    if (ai.norva.policy.DefaultCatalog.byId(spec.id) != null) {
                        val bytes = MemoryBudget.estimateBytes(spec, 2048)
                        Text("Оценка ОЗУ при 2048 токенах: ${gib(bytes)} ГиБ", style = MaterialTheme.typography.labelMedium)
                        if (states[spec.id] == InstallState.Installed) TextButton(onClick = {
                            chosen = spec.id; container.settings.selectedModel = spec.id
                        }) { Text(if (chosen == spec.id) "✓ Выбрана для чата" else "Использовать в чате") }
                    }
                    ModelCard(
                        spec = spec,
                        state = states[spec.id] ?: InstallState.NotInstalled,
                        onDownload = { vm.download(spec) },
                        onCancel = { vm.cancel(spec) },
                        onDelete = { if (chosen == spec.id) { chosen = ""; container.settings.selectedModel = "" }; vm.delete(spec) },
                    )
                }
                item {
                    Text(
                        "Модель 9B скрыта: на устройстве с 12 ГБ ОЗУ она не помещается в безопасный бюджет памяти.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun ModelCard(
    spec: ModelSpec,
    state: InstallState,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    onDelete: () -> Unit,
) {
    GlassPanel(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(spec.displayName, style = MaterialTheme.typography.titleMedium)
            Text(
                "~${gb(spec.fileSizeBytes)} ГБ · ${spec.license}" +
                    (if (spec.supportsThinking) " · рассуждение" else "") +
                    (if (!spec.verifiedOnHub) " · репозиторий не проверен" else ""),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            when (state) {
                InstallState.NotInstalled -> Button(onClick = onDownload) { Text("Скачать") }
                InstallState.Resolving -> Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Text("Запрос к Hugging Face…", modifier = Modifier.weight(1f))
                    TextButton(onClick = onCancel) { Text("Отмена") }
                }
                is InstallState.Downloading -> {
                    val fraction = if (state.totalBytes > 0) state.downloadedBytes.toFloat() / state.totalBytes else 0f
                    RacingProgress(fraction)
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Text(
                            "${state.downloadedBytes / 1_000_000} / ${state.totalBytes / 1_000_000} МБ · " +
                                "${state.bytesPerSecond / 1_000_000} МБ/с",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = onCancel) { Text("Пауза") }
                    }
                }
                InstallState.Verifying -> Text("Проверка SHA-256…")
                InstallState.Installed -> Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Text("Установлена", modifier = Modifier.weight(1f))
                    TextButton(onClick = onDelete) { Text("Удалить") }
                }
                is InstallState.Failed -> {
                    Text(state.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    Button(onClick = onDownload) { Text("Повторить") }
                }
            }
        }
    }
}
