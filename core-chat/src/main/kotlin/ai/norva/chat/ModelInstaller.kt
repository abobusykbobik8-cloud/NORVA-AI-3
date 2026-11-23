package ai.norva.chat

import ai.norva.inference.DownloadEvent
import ai.norva.inference.ModelStore
import ai.norva.inference.RemoteFile
import ai.norva.policy.DefaultCatalog
import ai.norva.policy.ModelSpec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

sealed interface InstallState {
    data object NotInstalled : InstallState
    data object Resolving : InstallState
    data class Downloading(val downloadedBytes: Long, val totalBytes: Long, val bytesPerSecond: Long) : InstallState
    data object Verifying : InstallState
    data object Installed : InstallState
    data class Failed(val message: String) : InstallState
}

/**
 * Состояние установки моделей каталога. start/cancel/delete вызываются с главного потока.
 * Загрузка идёт в прикладном scope и живёт, пока жив процесс; .part сохраняется и докачивается при повторном запуске.
 */
class ModelInstaller(
    private val store: ModelStore,
    private val scope: CoroutineScope,
    private val resolve: (repo: String, fileName: String) -> RemoteFile,
    private val download: (RemoteFile, File) -> Flow<DownloadEvent>,
    private val catalog: List<ModelSpec> = DefaultCatalog.models,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    private val _states = MutableStateFlow(initialStates())
    val states: StateFlow<Map<String, InstallState>> = _states.asStateFlow()
    private val jobs = HashMap<String, Job>()

    fun start(spec: ModelSpec) {
        if (jobs[spec.id]?.isActive == true) return
        jobs[spec.id] = scope.launch { run(spec) }
    }

    fun cancel(spec: ModelSpec) {
        jobs.remove(spec.id)?.cancel()
        set(spec.id, if (store.isInstalled(spec)) InstallState.Installed else InstallState.NotInstalled)
    }

    fun delete(spec: ModelSpec) {
        jobs.remove(spec.id)?.cancel()
        store.delete(spec)
        set(spec.id, InstallState.NotInstalled)
    }

    private suspend fun run(spec: ModelSpec) {
        set(spec.id, InstallState.Resolving)
        try {
            val remote = withContext(io) { resolve(spec.hubRepo, spec.fileName) }
            download(remote, store.fileFor(spec)).collect { ev ->
                when (ev) {
                    is DownloadEvent.Progress ->
                        set(spec.id, InstallState.Downloading(ev.downloadedBytes, ev.totalBytes, ev.bytesPerSecond))
                    is DownloadEvent.Verifying -> set(spec.id, InstallState.Verifying)
                    is DownloadEvent.Done -> set(spec.id, InstallState.Installed)
                    is DownloadEvent.Failed -> set(spec.id, InstallState.Failed(ev.message))
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            set(spec.id, InstallState.Failed(e.message ?: "Ошибка сети"))
        }
    }

    private fun set(id: String, state: InstallState) {
        _states.update { it + (id to state) }
    }

    private fun initialStates(): Map<String, InstallState> =
        catalog.associate { it.id to (if (store.isInstalled(it)) InstallState.Installed else InstallState.NotInstalled) }
}
