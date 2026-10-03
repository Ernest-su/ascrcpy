package ernest.ascrcpy.ui.main

import android.content.Context
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.Closeable
import java.io.IOException

/** Owns a Tailcat CLI process that forwards one loopback TCP port to a remote ADB port. */
internal class TailcatForwarder(context: Context) : Closeable {
  private val binary = java.io.File(context.applicationInfo.nativeLibraryDir, "libtailcat.so")
  private val configDirectory = java.io.File(context.noBackupFilesDir, "tailcat-config")
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private var process: Process? = null
  private var outputJob: Job? = null

  suspend fun start(address: String, remotePort: Int): Int {
    require(address.isNotBlank() && !address.any(Char::isWhitespace) && !address.startsWith('-')) {
      "Invalid Tailcat address"
    }
    require(remotePort in 1..65535) { "Invalid remote port" }
    stop()
    if (!binary.isFile || !binary.canExecute()) throw IOException("Tailcat is unavailable for this device ABI")
    val ready = CompletableDeferred<Int>()
    val child = withContext(Dispatchers.IO) {
      if (!configDirectory.isDirectory && !configDirectory.mkdirs()) {
        throw IOException("Unable to create Tailcat configuration directory")
      }
      ProcessBuilder(binary.absolutePath, "forward", address, "0:$remotePort").apply {
        configureTailcatEnvironment(environment(), configDirectory.absolutePath)
      }
        .redirectErrorStream(true)
        .start()
    }
    process = child
    outputJob = scope.launch {
      try {
        child.inputStream.bufferedReader().useLines { lines ->
          lines.forEach { line ->
            if (!ready.isCompleted) parseForwardedPort(line, remotePort)?.let(ready::complete)
          }
        }
        if (!ready.isCompleted) ready.completeExceptionally(IOException("Tailcat exited before forwarding started"))
      } catch (error: IOException) {
        if (!ready.isCompleted) ready.completeExceptionally(error)
      }
    }
    try {
      val localPort = withTimeout(30_000) { ready.await() }
      if (!child.isAlive) throw IOException("Tailcat stopped unexpectedly")
      return localPort
    } catch (error: Exception) {
      stop()
      throw error
    }
  }

  fun stop() {
    process?.destroy()
    process = null
    outputJob?.cancel()
    outputJob = null
  }

  override fun close() {
    stop()
    scope.coroutineContext[Job]?.cancel()
  }
}

/** Go's os.UserConfigDir requires HOME or XDG_CONFIG_HOME, neither of which is
 * guaranteed for a process spawned directly by an Android application. */
internal fun configureTailcatEnvironment(environment: MutableMap<String, String>, configPath: String) {
  environment["HOME"] = configPath
  environment["XDG_CONFIG_HOME"] = configPath
}

private val FORWARD_LINE = Regex(
  "^# forwarding 127\\.0\\.0\\.1:(\\d+) -> remote (?:\\S+:)?(\\d+)\\s*$",
)

internal fun parseForwardedPort(line: String, remotePort: Int): Int? {
  val match = FORWARD_LINE.matchEntire(line) ?: return null
  if (match.groupValues[2].toIntOrNull() != remotePort) return null
  return match.groupValues[1].toIntOrNull()?.takeIf { it in 1..65535 }
}
