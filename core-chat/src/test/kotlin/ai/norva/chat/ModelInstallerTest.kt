package ai.norva.chat

import ai.norva.inference.DownloadError
import ai.norva.inference.DownloadEvent
import ai.norva.inference.HubException
import ai.norva.inference.ModelStore
import ai.norva.inference.RemoteFile
import ai.norva.policy.DefaultCatalog
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class ModelInstallerTest {
    private val spec = DefaultCatalog.byId("qwen3.5-4b-q4km")!!
    private fun remote() = RemoteFile(spec.hubRepo, spec.fileName, 10, "ab".repeat(32), "http://x/y")

    @Test fun initialStateReflectsStoreAndInstallSucceeds() = runTest {
        val root = Files.createTempDirectory("inst").toFile()
        try {
            val store = ModelStore(root)
            var resolvedWith: Pair<String, String>? = null
            val installer = ModelInstaller(
                store = store, scope = this,
                resolve = { r, f -> resolvedWith = r to f; remote() },
                download = { _, _ ->
                    flowOf(DownloadEvent.Progress(5, 10, 1), DownloadEvent.Verifying, DownloadEvent.Done(File("x")))
                },
                io = StandardTestDispatcher(testScheduler),
            )
            assertEquals(InstallState.NotInstalled, installer.states.value[spec.id])
            installer.start(spec)
            advanceUntilIdle()
            assertEquals(InstallState.Installed, installer.states.value[spec.id])
            assertEquals(spec.hubRepo to spec.fileName, resolvedWith)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun hubAndDownloadErrorsBecomeFailedState() = runTest {
        val root = Files.createTempDirectory("inst").toFile()
        try {
            val store = ModelStore(root)
            val dispatcher = StandardTestDispatcher(testScheduler)
            val hubFail = ModelInstaller(
                store, this, { _, _ -> throw HubException("Файл не найден") }, { _, _ -> flowOf() }, io = dispatcher,
            )
            hubFail.start(spec)
            advanceUntilIdle()
            assertEquals(InstallState.Failed("Файл не найден"), hubFail.states.value[spec.id])

            val dlFail = ModelInstaller(
                store, this, { _, _ -> remote() },
                { _, _ -> flowOf(DownloadEvent.Failed(DownloadError.CHECKSUM, "хеш")) }, io = dispatcher,
            )
            dlFail.start(spec)
            advanceUntilIdle()
            assertIs<InstallState.Failed>(dlFail.states.value[spec.id])
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun deleteRemovesFilesAndResetsState() = runTest {
        val root = Files.createTempDirectory("inst").toFile()
        try {
            val store = ModelStore(root)
            store.dirFor(spec).mkdirs()
            store.fileFor(spec).writeText("x")
            File(store.fileFor(spec).path + ".sha256").writeText("h")
            val installer = ModelInstaller(
                store, this, { _, _ -> remote() }, { _, _ -> flowOf() }, io = StandardTestDispatcher(testScheduler),
            )
            assertEquals(InstallState.Installed, installer.states.value[spec.id])
            installer.delete(spec)
            assertEquals(InstallState.NotInstalled, installer.states.value[spec.id])
            assertEquals(false, store.isInstalled(spec))
        } finally {
            root.deleteRecursively()
        }
    }
}
