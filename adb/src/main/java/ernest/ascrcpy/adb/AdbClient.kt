package ernest.ascrcpy.adb

import java.io.Closeable
import java.io.InputStream
import kotlinx.coroutines.flow.StateFlow

/** Network address of an Android Debug Bridge daemon. */
data class AdbEndpoint(
    val host: String,
    val port: Int = DEFAULT_ADB_PORT,
) {
    init {
        require(host.isNotBlank()) { "ADB host must not be blank" }
        require(port in 1..65535) { "ADB port must be between 1 and 65535" }
    }

    val serial: String get() = "$host:$port"

    companion object {
        const val DEFAULT_ADB_PORT = 5555
    }
}

data class AdbDevice(
    val endpoint: AdbEndpoint,
    val banner: String,
    val properties: Map<String, String>,
)

sealed interface AdbConnectionState {
    data object Disconnected : AdbConnectionState
    data class Connecting(val endpoint: AdbEndpoint) : AdbConnectionState
    data class Authorizing(val endpoint: AdbEndpoint) : AdbConnectionState
    data class Connected(val device: AdbDevice) : AdbConnectionState
    data class Failed(val endpoint: AdbEndpoint, val cause: Throwable) : AdbConnectionState
}

data class AdbCommandResult(
    val stdout: ByteArray,
    val exitCode: Int? = null,
) {
    fun text(): String = stdout.toString(Charsets.UTF_8)
}

/**
 * Stable, implementation-independent ADB facade consumed by applications.
 *
 * Implementations may use TCP, USB, a local ADB server, or a third-party library.
 */
interface AdbClient : Closeable {
    val state: StateFlow<AdbConnectionState>

    suspend fun connect(endpoint: AdbEndpoint): AdbDevice

    suspend fun disconnect()

    suspend fun shell(command: String): AdbCommandResult

    suspend fun push(source: InputStream, remotePath: String, mode: Int = 0x1A4)

    /** Opens an arbitrary adbd service such as `localabstract:scrcpy_12345678`. */
    suspend fun open(service: String): AdbChannel
}

/** A full-duplex logical stream multiplexed over an ADB transport. */
interface AdbChannel : Closeable {
    suspend fun read(buffer: ByteArray, offset: Int = 0, length: Int = buffer.size): Int
    suspend fun write(buffer: ByteArray, offset: Int = 0, length: Int = buffer.size)
}

fun interface AdbClientFactory {
    fun create(): AdbClient
}

open class AdbException(message: String, cause: Throwable? = null) : Exception(message, cause)

class AdbAuthenticationException(message: String) : AdbException(message)

class AdbProtocolException(message: String) : AdbException(message)
