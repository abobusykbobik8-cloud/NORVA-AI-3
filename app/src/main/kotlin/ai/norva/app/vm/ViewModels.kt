package ai.norva.app.vm

import ai.norva.app.AppContainer
import ai.norva.chat.ChatOrchestrator
import ai.norva.chat.ChatRepository
import ai.norva.chat.ChatStatus
import ai.norva.chat.Conversation
import ai.norva.chat.InstallState
import ai.norva.chat.StoredMessage
import ai.norva.policy.DefaultCatalog
import ai.norva.policy.DeviceMemory
import ai.norva.policy.ModelSpec
import ai.norva.policy.ThinkingMode
import ai.norva.app.platform.SettingsStore
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class ChatListViewModel(private val repo: ChatRepository) : ViewModel() {
    val conversations: StateFlow<List<Conversation>> =
        repo.observeConversations().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun create(onCreated: (Long) -> Unit) {
        viewModelScope.launch { onCreated(repo.createConversation(ChatOrchestrator.DEFAULT_TITLE)) }
    }

    fun delete(id: Long) {
        viewModelScope.launch { repo.deleteConversation(id) }
    }
}

class ChatViewModel(private val conversationId: Long, private val container: AppContainer) : ViewModel() {
    val messages: StateFlow<List<StoredMessage>> =
        container.repo.observeMessages(conversationId)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val title: StateFlow<String> =
        container.repo.observeConversations()
            .map { list -> list.firstOrNull { it.id == conversationId }?.title ?: "" }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "")

    val modelReady: StateFlow<Boolean> =
        container.installer.states
            .map { states -> states.values.any { it == InstallState.Installed } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val status: StateFlow<ChatStatus> = container.orchestrator.status

    private val _mode = MutableStateFlow(container.settings.mode)
    val mode: StateFlow<ThinkingMode> = _mode.asStateFlow()

    fun setMode(mode: ThinkingMode) {
        _mode.value = mode
        container.settings.mode = mode
    }

    fun send(text: String) = container.orchestrator.startSend(conversationId, text, _mode.value)
    fun regenerate() = container.orchestrator.startRegenerate(conversationId, _mode.value)
    fun stop() = container.orchestrator.stop()
}

class ModelsViewModel(private val container: AppContainer) : ViewModel() {
    val states: StateFlow<Map<String, InstallState>> = container.installer.states

    /** Экспериментальные модели (9B) не показываем: на 12 ГБ они не помещаются в бюджет памяти. */
    val specs: List<ModelSpec> = DefaultCatalog.models.filter { !it.experimental }

    fun memory(): DeviceMemory = container.memory.current()

    fun download(spec: ModelSpec) = container.installer.start(spec)
    fun cancel(spec: ModelSpec) = container.installer.cancel(spec)

    fun delete(spec: ModelSpec) {
        viewModelScope.launch {
            container.orchestrator.releaseIfIdle()
            container.installer.delete(spec)
        }
    }
}

class SettingsViewModel(private val settings: SettingsStore) : ViewModel() {
    var systemPrompt: String
        get() = settings.systemPrompt
        private set(value) {
            settings.systemPrompt = value
        }

    fun save(text: String) {
        systemPrompt = text.trim().ifEmpty { SettingsStore.DEFAULT_SYSTEM_PROMPT }
    }

    fun reset() {
        systemPrompt = SettingsStore.DEFAULT_SYSTEM_PROMPT
    }
}

class VmFactory(
    private val container: AppContainer,
    private val conversationId: Long = 0L,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = when {
        modelClass.isAssignableFrom(ChatListViewModel::class.java) -> ChatListViewModel(container.repo) as T
        modelClass.isAssignableFrom(ChatViewModel::class.java) -> ChatViewModel(conversationId, container) as T
        modelClass.isAssignableFrom(ModelsViewModel::class.java) -> ModelsViewModel(container) as T
        modelClass.isAssignableFrom(SettingsViewModel::class.java) -> SettingsViewModel(container.settings) as T
        else -> throw IllegalArgumentException("Неизвестная ViewModel: ${modelClass.name}")
    }
}
