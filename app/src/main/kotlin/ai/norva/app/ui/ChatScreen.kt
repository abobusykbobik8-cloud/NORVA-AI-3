package ai.norva.app.ui

import ai.norva.app.AppContainer
import ai.norva.app.vm.ChatViewModel
import ai.norva.app.vm.VmFactory
import ai.norva.chat.ChatStatus
import ai.norva.chat.MessageStatus
import ai.norva.chat.StoredMessage
import ai.norva.inference.Role
import ai.norva.policy.ThinkingMode
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(container: AppContainer, conversationId: Long, onBack: () -> Unit, onModels: () -> Unit) {
    val vm: ChatViewModel = viewModel(key = "chat-$conversationId", factory = VmFactory(container, conversationId))
    val messages by vm.messages.collectAsStateWithLifecycle()
    val status by vm.status.collectAsStateWithLifecycle()
    val mode by vm.mode.collectAsStateWithLifecycle()
    val title by vm.title.collectAsStateWithLifecycle()
    val modelReady by vm.modelReady.collectAsStateWithLifecycle()
    var input by rememberSaveable { mutableStateOf("") }
    val listState = rememberLazyListState()
    val busy = status !is ChatStatus.Idle

    val last = messages.lastOrNull()
    LaunchedEffect(messages.size, last?.content?.length, last?.thinking?.length) {
        if (messages.isNotEmpty()) listState.scrollToItem(messages.lastIndex)
    }

    AppBackground {
        Scaffold(
            containerColor = Color.Transparent,
            topBar = {
                TopAppBar(
                    title = { Text(title.ifEmpty { "Чат" }, maxLines = 1) },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
                )
            },
            bottomBar = {
                Column(Modifier.navigationBarsPadding().imePadding().padding(horizontal = 12.dp, vertical = 8.dp)) {
                    statusLine(status)?.let {
                        Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ThinkingMode.entries.forEach { m ->
                            FilterChip(
                                selected = m == mode,
                                onClick = { vm.setMode(m) },
                                label = { Text(modeTitle(m)) },
                            )
                        }
                    }
                    Row(verticalAlignment = Alignment.Bottom) {
                        OutlinedTextField(
                            value = input,
                            onValueChange = { input = it },
                            modifier = Modifier.weight(1f),
                            placeholder = { Text("Сообщение") },
                            maxLines = 5,
                        )
                        Spacer(Modifier.width(8.dp))
                        if (busy) {
                            Button(onClick = { vm.stop() }) { Text("Стоп") }
                        } else {
                            IconButton(
                                onClick = {
                                    vm.send(input)
                                    input = ""
                                },
                                enabled = input.isNotBlank() && modelReady,
                            ) {
                                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Отправить")
                            }
                        }
                    }
                }
            },
        ) { padding ->
            Column(Modifier.padding(padding)) {
                if (!modelReady) {
                    GlassPanel(Modifier.fillMaxWidth().padding(12.dp), tint = NorvaRed) {
                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("Нет установленной модели.", modifier = Modifier.weight(1f))
                            TextButton(onClick = onModels) { Text("Модели") }
                        }
                    }
                }
                LazyColumn(
                    state = listState,
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(messages, key = { it.id }) { m ->
                        MessageBubble(
                            m = m,
                            canRegenerate = !busy && m.id == last?.id && m.role == Role.ASSISTANT,
                            onRegenerate = { vm.regenerate() },
                        )
                    }
                }
            }
        }
    }
}

private fun modeTitle(m: ThinkingMode): String = when (m) {
    ThinkingMode.FAST -> "Fast"
    ThinkingMode.NORMAL -> "Normal"
    ThinkingMode.DEEP -> "Deep"
}

private fun statusLine(s: ChatStatus): String? = when (s) {
    is ChatStatus.Idle -> null
    is ChatStatus.LoadingModel -> "Загрузка модели ${s.modelName}…"
    is ChatStatus.Generating -> "${s.modelName}: генерация…"
}

@Composable
private fun MessageBubble(m: StoredMessage, canRegenerate: Boolean, onRegenerate: () -> Unit) {
    val isUser = m.role == Role.USER
    Column(Modifier.fillMaxWidth(), horizontalAlignment = if (isUser) Alignment.End else Alignment.Start) {
        GlassPanel(Modifier.widthIn(max = 340.dp), tint = if (isUser) NorvaBlue else Color.White) {
            Column(Modifier.padding(12.dp)) {
                if (m.thinking.isNotBlank()) {
                    ThinkingBlock(text = m.thinking, live = m.status == MessageStatus.STREAMING && m.content.isEmpty())
                }
                if (m.content.isNotEmpty()) {
                    SelectionContainer { Text(m.content) }
                } else if (m.status == MessageStatus.STREAMING && m.thinking.isBlank()) {
                    Text("…")
                }
            }
        }
        m.errorMessage?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 4.dp))
        }
        val tps = m.tokensPerSecond
        if (!isUser && tps != null && tps > 0) {
            Text(
                "%.1f ток/с".format(tps),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        if (canRegenerate) TextButton(onClick = onRegenerate) { Text("Повторить") }
    }
}

@Composable
private fun ThinkingBlock(text: String, live: Boolean) {
    var open by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.padding(bottom = 6.dp)) {
        val label = when {
            open -> "Рассуждение ▲"
            live -> "Думает… ▼"
            else -> "Рассуждение ▼"
        }
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.clickable { open = !open }.padding(vertical = 4.dp),
        )
        if (open) {
            Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
