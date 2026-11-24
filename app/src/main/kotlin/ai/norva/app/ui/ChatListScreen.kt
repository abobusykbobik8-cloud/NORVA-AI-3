package ai.norva.app.ui

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
) {
    val vm: ChatListViewModel = viewModel(factory = VmFactory(container))
    val chats by vm.conversations.collectAsStateWithLifecycle()
    var toDelete by remember { mutableStateOf<Conversation?>(null) }

    AppBackground {
        Scaffold(
            containerColor = Color.Transparent,
            topBar = {
                TopAppBar(
                    title = { Text("NØRVA AI", fontWeight = FontWeight.Black, fontStyle = FontStyle.Italic, letterSpacing = 3.sp) },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
                    actions = {
                        TextButton(onClick = onModels) { Text("Модели") }
                        IconButton(onClick = onSettings) { Icon(Icons.Default.Settings, contentDescription = "Настройки") }
                    },
                )
            },
            floatingActionButton = {
                FloatingActionButton(onClick = { vm.create(onOpen) }) {
                    Icon(Icons.Default.Add, contentDescription = "Новый чат")
                }
            },
        ) { padding ->
            if (chats.isEmpty()) {
                Box(Modifier.fillMaxSize().padding(padding).padding(32.dp), contentAlignment = Alignment.Center) {
                    Text(
                        "Пока нет чатов. Нажмите «+», чтобы начать. Сначала установите модель на экране «Модели».",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(padding),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(chats, key = { it.id }) { chat ->
                        GlassPanel(Modifier.fillMaxWidth().clickable { onOpen(chat.id) }) {
                            Row(
                                Modifier.fillMaxWidth().padding(start = 16.dp, top = 4.dp, bottom = 4.dp, end = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(chat.title, modifier = Modifier.weight(1f), maxLines = 2)
                                IconButton(onClick = { toDelete = chat }) {
                                    Icon(Icons.Default.Delete, contentDescription = "Удалить чат")
                                }
                            }
                        }
                    }
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
                    vm.delete(target.id)
                    toDelete = null
                }) { Text("Удалить") }
            },
            dismissButton = { TextButton(onClick = { toDelete = null }) { Text("Отмена") } },
        )
    }
}
