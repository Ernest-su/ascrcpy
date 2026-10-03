package ernest.ascrcpy.ui.device

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import ernest.ascrcpy.adb.AdbClient
import ernest.ascrcpy.adb.AdbChannel
import ernest.ascrcpy.adb.AdbException
import ernest.ascrcpy.adb.AdbProtocolException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class AppInfo(val name: String, val packageName: String, val versionName: String, val versionCode: String, val system: Boolean)
data class RemoteFile(val name: String, val path: String, val mode: Int, val size: Long, val modifiedSeconds: Long) {
    val directory: Boolean get() = mode and 0xF000 == 0x4000
    val type: String get() = when (mode and 0xF000) {
        0x4000 -> "directory"
        0x8000 -> "file"
        0xA000 -> "link"
        else -> "other"
    }
}

class DeviceManager(private val context: Context, private val client: AdbClient) {
    suspend fun applications(): List<AppInfo> = withContext(Dispatchers.IO) {
        val packages = shell("pm list packages -f").lineSequence().mapNotNull { line ->
            if (!line.startsWith("package:")) return@mapNotNull null
            val raw = line.removePrefix("package:")
            val split = raw.lastIndexOf('=')
            if (split <= 0) null else raw.substring(split + 1).trim() to raw.substring(0, split)
        }.toList()
        packages.map { (pkg, apk) ->
            AppInfo(pkg.substringAfterLast('.'), pkg, "—", "—",
                listOf("/system/", "/system_ext/", "/product/", "/vendor/", "/odm/")
                    .any { apk.startsWith(it) })
        }.sortedBy { it.name.lowercase() }
    }

    suspend fun files(path: String): List<RemoteFile> = withContext(Dispatchers.IO) {
        val normalized = normalize(path)
        listRemoteFiles(normalized)
            .sortedWith(compareByDescending<RemoteFile> { it.directory }.thenBy { it.name.lowercase() })
    }

    suspend fun download(entry: RemoteFile, treeUri: Uri, progress: (String) -> Unit) = withContext(Dispatchers.IO) {
        val root = requireNotNull(DocumentFile.fromTreeUri(context, treeUri)) { "Unable to access selected folder" }
        require(entry.type == "directory" || entry.type == "file") { "Cannot download ${entry.type}: ${entry.path}" }
        if (entry.directory) pullDirectory(entry.path, root, entry.name, progress) else pullFile(entry.path, root, entry.name, progress)
    }

    suspend fun delete(entry: RemoteFile) = withContext(Dispatchers.IO) {
        val output = client.shell(deleteCommand(entry)).text()
        requireDeleteSuccess(output)
    }

    private suspend fun pullDirectory(remotePath: String, parent: DocumentFile, name: String, progress: (String) -> Unit) {
        val directory = parent.findFile(name)?.takeIf { it.isDirectory } ?: requireNotNull(parent.createDirectory(name)) { "Unable to create $name" }
        files(remotePath).forEach { child ->
            if (child.directory) pullDirectory(child.path, directory, child.name, progress)
            else if (child.type == "file") pullFile(child.path, directory, child.name, progress)
        }
    }

    private suspend fun pullFile(remotePath: String, parent: DocumentFile, name: String, progress: (String) -> Unit) {
        progress(remotePath)
        val file = parent.findFile(name)?.takeIf { it.isFile }
            ?: parent.createFile("application/octet-stream", name)
        requireNotNull(file) { "Unable to create $name" }
        context.contentResolver.openOutputStream(file.uri, "wt").use { output ->
            client.pull(remotePath, requireNotNull(output) { "Unable to open $name" })
        }
    }

    private suspend fun shell(command: String): String = client.shell(command).text()

    /** The published ADB facade has open("sync:") and pull(), but predates listFiles(). */
    private suspend fun listRemoteFiles(path: String): List<RemoteFile> {
        val channel = client.open("sync:")
        try {
            return listSyncFiles(channel, path)
        } finally {
            channel.close()
        }
    }

    companion object {
        private const val DELETE_EXIT_MARKER = "__ADB_DELETE_EXIT__:"
        internal fun deleteCommand(entry: RemoteFile): String {
            require(entry.path.startsWith('/') && entry.path != "/" && '\u0000' !in entry.path &&
                entry.path.split('/').none { it == "." || it == ".." }) {
                "Invalid remote path"
            }
            val option = if (entry.directory) "-r " else ""
            return "rm ${option}-- ${quote(entry.path)} 2>&1; printf '\\n${DELETE_EXIT_MARKER}%s\\n' \"${'$'}?\""
        }
        internal fun requireDeleteSuccess(output: String) {
            val markerAt = output.lastIndexOf(DELETE_EXIT_MARKER)
            val exitCode = if (markerAt >= 0)
                output.substring(markerAt + DELETE_EXIT_MARKER.length).trim().toIntOrNull() else null
            if (exitCode != 0) {
                val detail = if (markerAt >= 0) output.substring(0, markerAt).trim() else output.trim()
                throw IllegalStateException(detail.ifBlank { "Remote delete failed (exit code ${exitCode ?: "unknown"})" })
            }
        }
        fun normalize(path: String): String = (if (path.startsWith('/')) path else "/$path").replace(Regex("/+"), "/").removeSuffix("/").ifEmpty { "/" }
        fun parent(path: String): String = normalize(path).substringBeforeLast('/', "").ifEmpty { "/" }
        fun child(parent: String, name: String): String = if (parent == "/") "/$name" else "$parent/$name"
        fun quote(value: String) = "'${value.replace("'", "'\\''")}'"
    }
}

internal suspend fun listSyncFiles(channel: AdbChannel, path: String): List<RemoteFile> {
        val request = path.toByteArray(Charsets.UTF_8)
        require(path.startsWith('/') && '\u0000' !in path && request.size <= 4096) { "Invalid remote path" }
            channel.write(ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
                .put("LIST".toByteArray(Charsets.US_ASCII)).putInt(request.size).array())
            channel.write(request)
            val entries = mutableListOf<RemoteFile>()
            while (true) {
                val id = channel.readExact(4).toString(Charsets.US_ASCII)
                if (id == "FAIL") {
                    val length = ByteBuffer.wrap(channel.readExact(4)).order(ByteOrder.LITTLE_ENDIAN).int
                    if (length !in 0..65536) throw AdbProtocolException("Invalid sync error length: $length")
                    throw AdbException(channel.readExact(length).toString(Charsets.UTF_8))
                }
                if (id != "DENT" && id != "DONE") throw AdbProtocolException("Unexpected sync list response: $id")
                val header = ByteBuffer.wrap(channel.readExact(16)).order(ByteOrder.LITTLE_ENDIAN)
                if (id == "DONE") break
                val mode = header.int
                val size = header.int.toLong() and 0xffffffffL
                val modified = header.int.toLong() and 0xffffffffL
                val nameLength = header.int
                if (nameLength !in 1..255) throw AdbProtocolException("Invalid sync filename length: $nameLength")
                val name = channel.readExact(nameLength).toString(Charsets.UTF_8)
                if (name != "." && name != "..") {
                    val entryPath = if (path == "/") "/$name" else "$path/$name"
                    entries += RemoteFile(name, entryPath, mode, size, modified)
                }
            }
            return entries
}

private suspend fun AdbChannel.readExact(length: Int): ByteArray {
    val result = ByteArray(length)
    var offset = 0
    while (offset < length) {
        val count = read(result, offset, length - offset)
        if (count <= 0) throw AdbProtocolException("Unexpected end of ADB sync stream")
        offset += count
    }
    return result
}

fun formatBytes(value: Long): String {
    if (value < 1024) return "$value B"
    val units = arrayOf("KB", "MB", "GB", "TB")
    var size = value.toDouble()
    var unit = -1
    do { size /= 1024; unit++ } while (size >= 1024 && unit < units.lastIndex)
    return "%.1f %s".format(size, units[unit])
}

fun formatTime(seconds: Long): String = if (seconds <= 0) "—" else
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault())
        .format(Instant.ofEpochSecond(seconds))
