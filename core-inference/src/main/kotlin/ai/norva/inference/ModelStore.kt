package ai.norva.inference

import ai.norva.policy.ModelSpec
import java.io.File

/** Модель считается установленной, только если рядом лежит маркер .sha256 (его пишет загрузчик после проверки). */
class ModelStore(private val root: File) {
    fun dirFor(spec: ModelSpec): File = File(root, spec.id)
    fun fileFor(spec: ModelSpec): File = File(dirFor(spec), spec.fileName)

    fun isInstalled(spec: ModelSpec): Boolean {
        val f = fileFor(spec)
        return f.isFile && File(f.path + ".sha256").isFile
    }

    fun installedIds(catalog: List<ModelSpec>): Set<String> =
        catalog.filter { isInstalled(it) }.map { it.id }.toSet()

    fun delete(spec: ModelSpec): Boolean = dirFor(spec).deleteRecursively()

    fun freeBytes(): Long {
        root.mkdirs()
        return root.usableSpace
    }
}
