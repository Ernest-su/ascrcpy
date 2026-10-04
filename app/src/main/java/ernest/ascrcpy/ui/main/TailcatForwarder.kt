package ernest.ascrcpy.ui.main

import android.content.Context
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.Closeable
import java.io.IOException

/** Owns a Tailcat CLI process that forwards explicit remote ports to randomly allocated loopback ports. */
internal class TailcatForwarder(context: Context) : Closeable {
  private val binary = java.io.File(context.applicationInfo.nativeLibraryDir, "libtailcat.so")
  private val configDirectory = java.io.File(context.noBackupFilesDir, "tailcat-config")
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private val ownership = Any()
  @Volatile private var closed = false
  private var process: Process? = null
  private var outputJob: Job? = null

  suspend fun start(address: String, remotePort: Int): Int = start(address, listOf(remotePort)).getValue(remotePort)

  suspend fun start(address: String, remotePorts: List<Int>): Map<Int, Int> {
    check(!closed) { "Tailcat forwarder is closed" }
    require(validTailcatAddress(address)) { "Invalid Tailcat address" }
    require(remotePorts.isNotEmpty() && remotePorts.all { it in 1..65535 } &&
      remotePorts.distinct().size == remotePorts.size) { "Invalid remote ports" }
    stop()
    if (!binary.isFile || !binary.canExecute()) throw IOException("Tailcat is unavailable for this device ABI")
    val fingerprint = java.security.MessageDigest.getInstance("SHA-256")
      .digest(address.toByteArray(Charsets.UTF_8)).take(6).joinToString("") { "%02x".format(it) }
    android.util.Log.i("TailcatSession", "request route_fingerprint=$fingerprint remote_ports=${remotePorts.joinToString(",")}")
    val ready = CompletableDeferred<Map<Int, Int>>()
    try {
      // Assign ownership even when cancellation arrives while ProcessBuilder is starting.
      val child = withContext(Dispatchers.IO + NonCancellable) {
        if (!configDirectory.isDirectory && !configDirectory.mkdirs()) {
          throw IOException("Unable to create Tailcat configuration directory")
        }
        ProcessBuilder(listOf(binary.absolutePath, "forward", address) + remotePorts.map { "0:$it" }).apply {
          configureTailcatEnvironment(environment(), configDirectory.absolutePath)
        }.redirectErrorStream(true).start().also { child ->
          synchronized(ownership) {
            if (closed) {
              child.destroyForcibly()
              child.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)
              throw IOException("Tailcat forwarder is closed")
            }
            process = child
          }
        }
      }
      currentCoroutineContext().ensureActive()
      outputJob = scope.launch {
        val mappings = linkedMapOf<Int, Int>()
        try {
          child.inputStream.bufferedReader().useLines { lines ->
            lines.forEach { line ->
              if (!ready.isCompleted) {
                remotePorts.forEach { remote ->
                  parseForwardedPort(line, remote)?.let { local -> mappings[remote] = local }
                }
                if (mappings.size == remotePorts.size && mappings.values.distinct().size == mappings.size) {
                  // Log only ports, never the address or raw child output (which may include credentials).
                  mappings.forEach { (remote, local) ->
                    android.util.Log.i("TailcatSession", "forward remote=$remote local=$local")
                  }
                  ready.complete(mappings.toMap())
                }
              }
            }
          }
          if (!ready.isCompleted) ready.completeExceptionally(IOException("Tailcat exited before forwarding started"))
        } catch (_: IOException) {
          if (!ready.isCompleted) ready.completeExceptionally(IOException("Tailcat forwarding failed"))
        }
      }
      val mappings = withTimeout(30_000) { ready.await() }
      if (!child.isAlive) throw IOException("Tailcat stopped unexpectedly")
      return mappings
    } catch (error: Exception) {
      stop()
      throw error
    }
  }

  fun stop() {
    val child = synchronized(ownership) { process.also { process = null } }
    child?.destroy()
    if (child != null) CoroutineScope(Dispatchers.IO).launch {
      if (!child.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)) {
        child.destroyForcibly()
        child.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)
      }
    }
    outputJob?.cancel()
    outputJob = null
  }

  override fun close() {
    synchronized(ownership) { closed = true }
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

internal fun validTailcatAddress(address: String): Boolean =
  address.isNotBlank() && !address.any(Char::isWhitespace) && !address.startsWith('-')

internal data class TailcatPairingPorts(val pairing: Int, val connection: Int)

internal fun tailcatPairingPorts(pairing: String, connection: String): TailcatPairingPorts? {
  val pair = pairing.toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
  val connect = connection.toIntOrNull()?.takeIf { it in 1..65535 && it != pair } ?: return null
  return TailcatPairingPorts(pair, connect)
}
