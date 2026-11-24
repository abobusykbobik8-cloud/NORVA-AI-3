package ai.norva.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.text.style.TextOverflow
import ai.norva.app.AppContainer
import ai.norva.app.vm.ChatListViewModel
import ai.norva.app.vm.VmFactory
import ai.norva.chat.Conversation
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatListScreen(
    container: AppContainer,
    onOpen: (Long) -> Unit,
    onModels: () -> Unit,
    onSettings: () -> Unit,
    onDeleted: (Long) -> Unit = {},
) {
    val vm: ChatListViewModel = viewModel(factory = VmFactory(container))
    val chats by vm.conversations.collectAsStateWithLifecycle()
    var toDelete by remember { mutableStateOf<Conversation?>(null) }

    var searching by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var onlyMedia by rememberSaveable { mutableStateOf(false) }
    val mediaIds by container.mediaConversationIds.collectAsStateWithLifecycle(emptyList())
    val mediaSet = remember(mediaIds) { mediaIds.toSet() }
    val status by container.orchestrator.status.collectAsStateWithLifecycle()
    val mediaBusy by container.media.busy.collectAsStateWithLifecycle()
    val activeChat = when (val current = status) {
        is ai.norva.chat.ChatStatus.Generating -> current.conversationId
        is ai.norva.chat.ChatStatus.LoadingModel -> current.conversationId
        ai.norva.chat.ChatStatus.Idle -> null
    }
    val visible = remember(chats, query, onlyMedia, mediaSet) {
        chats.filter { (!onlyMedia || it.id in mediaSet) && it.title.contains(query.trim(), ignoreCase = true) }
    }

    AppBackground {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding().padding(horizontal = 20.dp)) {
            Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("NØRVA AI", modifier = Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Black, fontStyle = FontStyle.Italic, letterSpacing = 3.sp)
                GlassPanel(shape = CircleShape) {
                    IconButton(onClick = { searching = !searching; query = "" }) {
                        Icon(if (searching) Icons.Default.Close else Icons.Default.Search, if (searching) "Закрыть поиск" else "Поиск чатов")
                    }
                }
            }
            if (searching) {
                OutlinedTextField(query, { query = it }, singleLine = true, shape = CircleShape,
                    modifier = Modifier.fillMaxWidth(), placeholder = { Text("Поиск по истории") })
            }
            LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                item {
                    TextButton(onClick = { onlyMedia = !onlyMedia }) {
                        Icon(if (onlyMedia) Icons.Default.Close else Icons.Default.Star, null)
                        Spacer(Modifier.width(14.dp))
                        Text(if (onlyMedia) "Показать все чаты" else "Чаты с фото и видео", style = MaterialTheme.typography.titleMedium)
                    }
                    TextButton(onClick = onModels) {
                        Icon(Icons.AutoMirrored.Filled.List, null)
                        Spacer(Modifier.width(14.dp))
                        Text("Модели", style = MaterialTheme.typography.titleMedium)
                    }
                    HorizontalDivider(Modifier.padding(vertical = 12.dp), color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.18f))
                }
                items(visible, key = { it.id }) { chat ->
                    Row(Modifier.fillMaxWidth().clickable { onOpen(chat.id) }.padding(start = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(chat.title, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        IconButton(enabled = activeChat != chat.id && mediaBusy == null, onClick = { toDelete = chat }) { Icon(Icons.Default.Delete, "Удалить чат") }
                    }
                }
                if (visible.isEmpty()) item {
                    Text(if (query.isNotBlank()) "Чаты не найдены" else if (onlyMedia) "Пока нет чатов с вложениями" else "Здесь появится история чатов",
                        Modifier.padding(8.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically) {
                RacingButton(onClick = { vm.create(onOpen) }) {
                    Row(Modifier.padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Edit, null)
                        Spacer(Modifier.width(8.dp))
                        Text("Чат", fontWeight = FontWeight.Bold)
                    }
                }
                GlassPanel(shape = CircleShape) {
                    IconButton(onClick = onSettings) { Icon(Icons.Default.Settings, "Настройки") }
                }
            }
        }
    }

    val target = toDelete
    if (target != null) {
        AlertDialog(
            onDismissRequest = { toDelete = null },
            title = { Text("Удалить чат?") },
            text = { Text("«${target.title}» и вся его история будут удалены без возможности восстановления.") },
            confirmButton = {
                TextButton(onClick = {
                    vm.delete(target.id, onDeleted)
                    toDelete = null
                }) { Text("Удалить") }
            },
            dismissButton = { TextButton(onClick = { toDelete = null }) { Text("Отмена") } },
        )
    }
}
