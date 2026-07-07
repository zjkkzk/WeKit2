package dev.ujhhgtg.wekit.agent.workspace

import java.io.File
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * Coroutine-context element carrying the active [WorkspaceVfs] for a turn. Installed by the caller
 * (WeAgentService) around [dev.ujhhgtg.wekit.agent.engine.AgentSessionEngine.runTurn]; the fs
 * `@AgentTool` functions read it via `coroutineContext[VfsContext]`. It propagates into the engine's
 * `channelFlow` because that block runs in the collector's context.
 */
class VfsContext(val vfs: WorkspaceVfs) : AbstractCoroutineContextElement(VfsContext) {
    companion object Key : CoroutineContext.Key<VfsContext>
}

/**
 * Virtual filesystem that the model sees as two fixed roots (§7, §8):
 *  - `/workspace/…` → the session's bound workspace directory (null when unbound).
 *  - `/memory/…`    → the shared memory directory (null when memory is disabled).
 *
 * Path resolution strictly clamps every access inside its root via canonical-path prefix checks, so
 * a model can never escape via `..` or absolute paths. Every operation returns a model-readable
 * string (including on error) rather than throwing, so the engine needn't special-case failures.
 */
class WorkspaceVfs(
    private val workspaceRoot: File?,
    private val memoryRoot: File?,
) {
    companion object {
        const val WORKSPACE_PREFIX = "/workspace/"
        const val MEMORY_PREFIX = "/memory/"
        const val MEMORY_INDEX = "MEMORY.md"
        private const val WORKSPACE_ROOT_PATH = "/workspace"
        private const val MEMORY_ROOT_PATH = "/memory"
        private const val MAX_READ_BYTES = 256 * 1024
        private const val MAX_SEARCH_MATCHES = 200
    }

    private enum class Root { WORKSPACE, MEMORY }

    private class Resolved(val file: File, val root: Root) {
        val isMemoryIndex: Boolean get() = root == Root.MEMORY && file.name == MEMORY_INDEX
    }

    private class VfsException(message: String) : Exception(message)

    val hasWorkspace: Boolean get() = workspaceRoot != null
    val hasMemory: Boolean get() = memoryRoot != null

    // -----------------------------------------------------------------------------------------
    // Public file operations (each returns a model-readable string)
    // -----------------------------------------------------------------------------------------

    fun readFile(path: String): String = guarded {
        val r = resolve(path)
        if (!r.file.exists()) return@guarded "File not found: $path"
        if (r.file.isDirectory) return@guarded "Path is a directory, not a file: $path"
        if (r.file.length() > MAX_READ_BYTES) return@guarded "File too large (> ${MAX_READ_BYTES / 1024} KiB): $path"
        r.file.readText()
    }

    fun listDir(path: String): String = guarded {
        val r = resolve(path)
        if (!r.file.exists()) return@guarded "Directory not found: $path"
        if (!r.file.isDirectory) return@guarded "Path is a file, not a directory: $path"
        val entries = r.file.listFiles()?.sortedBy { it.name } ?: emptyList()
        if (entries.isEmpty()) "(empty directory)"
        else entries.joinToString("\n") { if (it.isDirectory) "${it.name}/" else it.name }
    }

    fun searchFiles(path: String, query: String): String = guarded {
        val r = resolve(path)
        if (!r.file.exists()) return@guarded "Path not found: $path"
        if (query.isEmpty()) return@guarded "Empty search query."
        val needle = query.lowercase()
        val base = r.file
        val matches = ArrayList<String>()
        base.walkTopDown().filter { it.isFile }.forEach { f ->
            if (matches.size >= MAX_SEARCH_MATCHES) return@forEach
            runCatching {
                if (f.length() > MAX_READ_BYTES) return@runCatching
                f.readText().lineSequence().forEachIndexed { i, line ->
                    if (matches.size < MAX_SEARCH_MATCHES && line.lowercase().contains(needle)) {
                        val virtual = virtualOf(r.root, f)
                        matches.add("$virtual:${i + 1}: ${line.trim().take(200)}")
                    }
                }
            }
        }
        if (matches.isEmpty()) "No matches for '$query'." else matches.joinToString("\n")
    }

    fun writeFile(path: String, content: String): String = guarded {
        val r = resolve(path)
        if (r.file.isDirectory) return@guarded "Path is a directory: $path"
        r.file.parentFile?.mkdirs()
        r.file.writeText(content)
        "Wrote ${content.toByteArray().size} bytes to $path"
    }

    fun appendFile(path: String, content: String): String = guarded {
        val r = resolve(path)
        if (r.file.isDirectory) return@guarded "Path is a directory: $path"
        r.file.parentFile?.mkdirs()
        r.file.appendText(content)
        "Appended ${content.toByteArray().size} bytes to $path"
    }

    fun deleteFile(path: String): String = guarded {
        val r = resolve(path)
        if (r.isMemoryIndex) return@guarded "Refusing to delete the protected memory index ${MEMORY_INDEX}."
        if (!r.file.exists()) return@guarded "File not found: $path"
        if (r.file.isDirectory) return@guarded "Refusing to delete a directory: $path"
        if (r.file.delete()) "Deleted $path" else "Failed to delete $path"
    }

    fun moveFile(from: String, to: String): String = guarded {
        val src = resolve(from)
        val dst = resolve(to)
        if (src.isMemoryIndex) return@guarded "Refusing to move the protected memory index ${MEMORY_INDEX}."
        if (!src.file.exists()) return@guarded "Source not found: $from"
        dst.file.parentFile?.mkdirs()
        if (src.file.renameTo(dst.file)) "Moved $from -> $to"
        else {
            // Cross-device fallback: copy + delete.
            runCatching {
                src.file.copyTo(dst.file, overwrite = true)
                src.file.delete()
            }.map { "Moved $from -> $to" }.getOrElse { "Failed to move $from -> $to: ${it.message}" }
        }
    }

    // -----------------------------------------------------------------------------------------
    // Internals
    // -----------------------------------------------------------------------------------------

    private inline fun guarded(block: () -> String): String =
        try { block() } catch (e: VfsException) { "Error: ${e.message}" }
            catch (e: Throwable) { "File operation failed: ${e.message ?: e.javaClass.simpleName}" }

    private fun resolve(virtualPath: String): Resolved {
        val normalized = virtualPath.trim()
        val (root, relative) = when {
            normalized == WORKSPACE_ROOT_PATH || normalized.startsWith(WORKSPACE_PREFIX) ->
                Root.WORKSPACE to normalized.removePrefix(WORKSPACE_ROOT_PATH).trimStart('/')

            normalized == MEMORY_ROOT_PATH || normalized.startsWith(MEMORY_PREFIX) ->
                Root.MEMORY to normalized.removePrefix(MEMORY_ROOT_PATH).trimStart('/')

            else -> throw VfsException(
                "Path must start with '$WORKSPACE_PREFIX' or '$MEMORY_PREFIX': '$virtualPath'"
            )
        }

        val rootDir = when (root) {
            Root.WORKSPACE -> workspaceRoot
                ?: throw VfsException("No workspace is bound to this session; '/workspace/' is unavailable.")
            Root.MEMORY -> memoryRoot
                ?: throw VfsException("Memory is disabled; '/memory/' is unavailable.")
        }

        val rootPath = rootDir.canonicalFile.path
        val target = File(rootDir, relative).canonicalFile
        if (target.path != rootPath && !target.path.startsWith(rootPath + File.separator)) {
            throw VfsException("Path escapes its root and was rejected: '$virtualPath'")
        }
        return Resolved(target, root)
    }

    private fun virtualOf(root: Root, file: File): String {
        val rootDir = (if (root == Root.WORKSPACE) workspaceRoot else memoryRoot)!!.canonicalFile
        val prefix = if (root == Root.WORKSPACE) WORKSPACE_ROOT_PATH else MEMORY_ROOT_PATH
        val rel = file.canonicalFile.path.removePrefix(rootDir.path).replace(File.separatorChar, '/')
        return (prefix + rel).ifEmpty { prefix }
    }
}
