package ai.norva.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.style.TextOverflow
import ai.norva.app.vm.ChatListViewModel
import kotlinx.coroutines.launch
import ai.norva.app.AppContainer
import ai.norva.app.vm.ChatViewModel
import ai.norva.app.vm.VmFactory
import ai.norva.chat.ChatStatus
import ai.norva.chat.MessageStatus
import ai.norva.chat.StoredMessage
import ai.norva.inference.Role
import ai.norva.policy.ThinkingMode
import androidx.compose.foundation.clickable
import ai.norva.app.media.ImageIo
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.text.font.FontWeight
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
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
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
fun ChatScreen(container: AppContainer, conversationId: Long, onOpenChat: (Long) -> Unit, onChatDeleted: () -> Unit, onSettings: () -> Unit, onModels: () -> Unit) {
    val vm: ChatViewModel = viewModel(key = "chat-$conversationId", factory = VmFactory(container, conversationId))
    val messages by vm.messages.collectAsStateWithLifecycle()
    val status by vm.status.collectAsStateWithLifecycle()
    val mode by vm.mode.collectAsStateWithLifecycle()
    val title by vm.title.collectAsStateWithLifecycle()
    val cloud by container.cloud.config.collectAsStateWithLifecycle()
    val localReady by vm.modelReady.collectAsStateWithLifecycle()
    val modelReady = localReady || cloud.chatEnabled
    var input by rememberSaveable { mutableStateOf("") }
    val listState = rememberLazyListState()
    val busy = status !is ChatStatus.Idle
    val pending by vm.pending.collectAsStateWithLifecycle()
    val mediaBusy by vm.mediaBusy.collectAsStateWithLifecycle()
    val localRendering by container.media.localRunning.collectAsStateWithLifecycle()
    val notice by vm.notice.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var menuOpen by remember { mutableStateOf(false) }
    var captureUri by rememberSaveable { mutableStateOf<Uri?>(null) }
    val takePicture = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val uri = captureUri
        if (ok && uri != null) vm.importPhoto(uri)
    }
    val pickPhoto = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) vm.importPhoto(uri)
    }
    LaunchedEffect(notice) {
        notice?.let {
            Toast.makeText(context, it, Toast.LENGTH_LONG).show()
            vm.clearNotice()
        }
    }

    val last = messages.lastOrNull()
    LaunchedEffect(messages.size, last?.content?.length, last?.thinking?.length) {
        if (messages.isNotEmpty()) listState.scrollToItem(messages.lastIndex)
    }

    var live by remember { mutableStateOf(false) }
    if (live) LiveDialog(messages, vm::send, vm::stop) { live = false }
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val chats: ChatListViewModel = viewModel(factory = VmFactory(container))
    var showModes by rememberSaveable { mutableStateOf(false) }
    var more by remember { mutableStateOf(false) }
    ModalNavigationDrawer(drawerState = drawer, drawerContent = {
        val drawerWidth = (LocalConfiguration.current.screenWidthDp.dp * 0.80f).coerceAtMost(360.dp)
        ModalDrawerSheet(modifier = Modifier.width(drawerWidth).fillMaxHeight(),
            drawerContainerColor = NorvaNavy,
            windowInsets = androidx.compose.foundation.layout.WindowInsets(0, 0, 0, 0)) {
            ChatListScreen(container, onOpen = { id ->
                scope.launch { drawer.close(); if (id != conversationId) onOpenChat(id) }
            }, onModels = { scope.launch { drawer.close(); onModels() } },
                onSettings = { scope.launch { drawer.close(); onSettings() } },
                onDeleted = { if (it == conversationId) onChatDeleted() })
        }
    }) {
        AppBackground {
            Scaffold(
                containerColor = Color.Transparent,
                topBar = {
                    Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 12.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        GlassPanel(shape = CircleShape) {
                            IconButton(onClick = { scope.launch { drawer.open() } }) { Icon(Icons.Default.Menu, "История и меню") }
                        }
                        GlassPanel(modifier = Modifier.weight(1f).clickable { showModes = !showModes }, shape = CircleShape) {
                            Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                                Text(title.ifEmpty { "NØRVA AI" }, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.titleMedium)
                                Text(modeTitle(mode) + " · " + if (cloud.chatEnabled) "Облако" else "Локально",
                                    maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        GlassPanel(shape = CircleShape) {
                            Row {
                                IconButton(onClick = { chats.create(onOpenChat) }) { Icon(Icons.Default.Edit, "Новый чат") }
                                Box {
                                    IconButton(onClick = { more = true }) { Icon(Icons.Default.MoreVert, "Действия") }
                                    DropdownMenu(more, { more = false }) {
                                    DropdownMenuItem(text = { Text("NØRVA Live") }, enabled = modelReady && !busy && mediaBusy == null && pending == null, onClick = { more = false; live = true })
                                        DropdownMenuItem(text = { Text("Режим ответа") }, onClick = { more = false; showModes = !showModes })
                                        DropdownMenuItem(text = { Text("Модели") }, onClick = { more = false; onModels() })
                                        DropdownMenuItem(text = { Text("Настройки") }, onClick = { more = false; onSettings() })
                                    }
                                }
                            }
                        }
                    }
                },
                bottomBar = {
                    Column(Modifier.navigationBarsPadding().imePadding().padding(horizontal = 12.dp, vertical = 8.dp)) {
                        (mediaBusy ?: statusLine(status))?.let {
                            Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        pending?.let { p ->
                            GlassPanel(Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
                                Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Box(Modifier.width(64.dp)) { AttachmentImage(p.path) }
                                    Text(
                                        "Фото прикреплено. Задайте вопрос или отправьте снимок.",
                                        modifier = Modifier.weight(1f).padding(horizontal = 10.dp),
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                    TextButton(onClick = { vm.clearPending() }) { Text("Убрать") }
                                }
                            }
                        }
                        if (showModes) ModeSelector(
                            selected = ThinkingMode.entries.indexOf(mode),
                            labels = ThinkingMode.entries.map { modeTitle(it) },
                            onSelect = { vm.setMode(ThinkingMode.entries[it]); showModes = false },
                            modifier = Modifier.padding(vertical = 8.dp),
                        )
                        GlassPanel(Modifier.fillMaxWidth(), shape = RoundedCornerShape(28.dp)) {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box {
                                IconButton(enabled = !busy && mediaBusy == null, onClick = { menuOpen = true }) {
                                    Icon(Icons.Default.Add, contentDescription = "Добавить")
                                }
                                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                                    DropdownMenuItem(text = { Text("Локально · изображение SD 1.5") },
                                        enabled = input.isNotBlank() && pending == null,
                                        onClick = { menuOpen = false; if (container.media.startLocal(conversationId, input, false)) input = "" })
                                    DropdownMenuItem(text = { Text(if (pending == null) "Локально · видео Wan 2.1" else "Локально · видео по фото") },
                                        enabled = input.isNotBlank(),
                                        onClick = { menuOpen = false; if (container.media.startLocal(conversationId, input, true, pending?.path)) { input = ""; vm.clearPending() } })
                                    DropdownMenuItem(text = { Text("Облако · изображение FLUX") },
                                        enabled = input.isNotBlank() && cloud.mediaKey.isNotBlank(),
                                        onClick = { menuOpen = false; vm.neural(input, false) })
                                    DropdownMenuItem(text = { Text(if (pending == null) "Облако · видео Wan" else "Облако · видео из этого фото") },
                                        enabled = input.isNotBlank() && cloud.mediaKey.isNotBlank(),
                                        onClick = { menuOpen = false; vm.neural(input, true) })
                                    DropdownMenuItem(
                                        text = { Text("Сделать фото") },
                                        onClick = {
                                            menuOpen = false
                                            try {
                                                val uri = ImageIo.newCaptureUri(context)
                                                captureUri = uri
                                                takePicture.launch(uri)
                                            } catch (e: Exception) {
                                                Toast.makeText(context, "Камера недоступна: ${e.message}", Toast.LENGTH_LONG).show()
                                            }
                                        },
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Выбрать из галереи") },
                                        onClick = {
                                            menuOpen = false
                                            pickPhoto.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                                        },
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Canvas · процедурный рисунок") },
                                        enabled = input.isNotBlank(),
                                        onClick = {
                                            menuOpen = false
                                            vm.generateImage(input)
                                            input = ""
                                        },
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Canvas · анимированный узор") },
                                        enabled = input.isNotBlank(),
                                        onClick = {
                                            menuOpen = false
                                            vm.generateVideo(input)
                                            input = ""
                                        },
                                    )
                                }
                            }
                            BasicTextField(value = input, onValueChange = { input = it },
                                modifier = Modifier.weight(1f).padding(vertical = 10.dp),
                                textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                                cursorBrush = SolidColor(MaterialTheme.colorScheme.onSurface), maxLines = 5,
                                decorationBox = { field -> Box {
                                    if (input.isEmpty()) Text("Сообщение", color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    field()
                                } })
                            val canSend = (input.isNotBlank() || pending != null) && modelReady && mediaBusy == null
                            IconButton(enabled = localRendering || busy || canSend, onClick = {
                                if (localRendering) container.media.stopLocal() else if (busy) vm.stop() else if (vm.send(input)) input = ""
                            }) {
                                Box(Modifier.size(36.dp).background(if (localRendering || busy || canSend) NorvaBlue else MaterialTheme.colorScheme.surfaceVariant, CircleShape),
                                    contentAlignment = Alignment.Center) {
                                    if (localRendering || busy) Box(Modifier.size(12.dp).background(Color.White, RoundedCornerShape(2.dp))
                                        .semantics { contentDescription = "Стоп" })
                                    else Icon(Icons.AutoMirrored.Filled.Send, "Отправить", Modifier.size(19.dp), tint = Color.White)
                                }
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
                            Box(Modifier.animateItem()) {
                                MessageBubble(
                                    m = m,
                                    canRegenerate = !busy && m.id == last?.id && m.role == Role.ASSISTANT,
                                    onRegenerate = { vm.regenerate() },
                                    onSave = { vm.save(it) },
                                )
                            }
                        }
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
private fun MessageBubble(m: StoredMessage, canRegenerate: Boolean, onRegenerate: () -> Unit, onSave: (StoredMessage) -> Unit) {
    val isUser = m.role == Role.USER
    Column(Modifier.fillMaxWidth(), horizontalAlignment = if (isUser) Alignment.End else Alignment.Start) {
        val body: @Composable () -> Unit = {
            Column(Modifier.padding(if (isUser) 14.dp else 6.dp).animateContentSize()) {
                m.attachmentPath?.let { path ->
                    if (m.attachmentKind == "VIDEO") AttachmentVideo(path) else AttachmentImage(path)
                    Spacer(Modifier.height(8.dp))
                }
                if (m.thinking.isNotBlank()) {
                    ThinkingBlock(text = m.thinking, live = m.status == MessageStatus.STREAMING && m.content.isEmpty())
                }
                if (m.content.isNotEmpty()) {
                    SelectionContainer { Text(m.content) }
                } else if (m.status == MessageStatus.STREAMING && m.thinking.isBlank()) {
                    TypingDots()
                }
            }
        }
        if (isUser) GlassPanel(Modifier.widthIn(max = 340.dp), tint = NorvaBlue) { body() } else body()
        m.errorMessage?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 4.dp))
        }
        val tps = m.tokensPerSecond
        if (!isUser && tps != null && tps > 0) {
            Text(
                "%.1f ток/с".format(tps),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = NorvaYellow,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        if (m.attachmentPath != null) TextButton(onClick = { onSave(m) }) { Text("Сохранить в галерею") }
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
        AnimatedVisibility(visible = open) {
            Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
