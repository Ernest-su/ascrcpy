package ernest.ascrcpy.adb.transport

import java.io.Closeable

/** Raw byte transport, deliberately independent of java.net and Android USB APIs. */
interface AdbTransport : Closeable {
    suspend fun connect()
    suspend fun readExactly(buffer: ByteArray, offset: Int = 0, length: Int = buffer.size)
    suspend fun write(buffer: ByteArray, offset: Int = 0, length: Int = buffer.size)
}

fun interface AdbTransportFactory {
    fun create(host: String, port: Int): AdbTransport
}
