package ai.norva.app

import ai.norva.app.data.MIGRATION_2_3
import ai.norva.app.data.MIGRATION_1_2
import ai.norva.app.data.NorvaDatabase
import ai.norva.app.media.MediaStudio
import ai.norva.app.data.RoomChatRepository
import ai.norva.app.platform.AndroidMemoryInfoProvider
import ai.norva.app.platform.AndroidPowerStateProvider
import ai.norva.app.platform.SettingsStore
import ai.norva.chat.ChatOrchestrator
import ai.norva.chat.ChatRepository
import ai.norva.chat.ModelInstaller
import ai.norva.engine.LlamaEngine
import ai.norva.inference.HfHub
import ai.norva.inference.ModelDownloader
import ai.norva.inference.ModelStore
import android.content.Context
import androidx.room.Room
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.io.File

/** Ручная сборка зависимостей без DI-фреймворка: меньше мест, где может сломаться сборка. */
class AppContainer(context: Context) {
    private val app = context.applicationContext

    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val db = Room.databaseBuilder(app, NorvaDatabase::class.java, "norva.db").addMigrations(MIGRATION_1_2, MIGRATION_2_3).build()

    val mediaConversationIds = db.chatDao().conversationsWithMedia()
    val repo: ChatRepository = RoomChatRepository(db.chatDao())
    val settings = SettingsStore(app)
    val personal = ai.norva.app.platform.PersonalMemory(app)
    val sync = ai.norva.app.data.CrossSync(db, settings)
    val cloud = ai.norva.app.cloud.CloudApi(app)
    val media = MediaStudio(app, repo, File(app.filesDir, "media"), cloud)
    val modelStore = ModelStore(File(app.filesDir, "models"))
    val engine = LlamaEngine()
    val memory = AndroidMemoryInfoProvider(app)
    val power = AndroidPowerStateProvider(app)

    private val hub = HfHub()
    private val downloader = ModelDownloader()

    val installer = ModelInstaller(
        store = modelStore,
        scope = appScope,
        resolve = { repoName, fileName -> hub.resolve(repoName, fileName) },
        download = { remote, dest -> downloader.download(remote, dest) },
    )

    val workbench = ai.norva.chat.Workbench(engine, { modelStore.fileFor(it).absolutePath }, memory::current, power::current, android.os.Debug::getPss)

    val orchestrator = ChatOrchestrator(
        repo = repo,
        engine = engine,
        store = modelStore,
        power = power,
        memory = memory,
        scope = appScope,
        preferredModel = { settings.selectedModel },
        turbo = { settings.turbo },
        personalization = personal::contextFor,
        remoteModel = { cloud.config.value.takeIf { it.chatEnabled }?.model },
        remoteChat = cloud::chat,
        systemPrompt = { settings.systemPrompt },
    )
}
