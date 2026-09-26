package ernest.ascrcpy.adb.transport

import java.io.EOFException
import java.net.InetSocketAddress
import java.net.Socket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class TcpAdbTransport(
    private val host: String,
    private val port: Int,
    private val connectTimeoutMillis: Int = 10_000,
) : AdbTransport {
    private var socket: Socket? = null

    override suspend fun connect() = withContext(Dispatchers.IO) {
        check(socket == null) { "Transport is already connected" }
        socket = Socket().apply {
            tcpNoDelay = true
            keepAlive = true
            connect(InetSocketAddress(host, port), connectTimeoutMillis)
        }
    }

    override suspend fun readExactly(buffer: ByteArray, offset: Int, length: Int) {
        requireRange(buffer, offset, length)
        withContext(Dispatchers.IO) {
            val input = checkNotNull(socket).getInputStream()
            var position = offset
            val end = offset + length
            while (position < end) {
                val count = input.read(buffer, position, end - position)
                if (count < 0) throw EOFException("ADB transport closed")
                position += count
            }
        }
    }

    override suspend fun write(buffer: ByteArray, offset: Int, length: Int) {
        requireRange(buffer, offset, length)
        withContext(Dispatchers.IO) {
            checkNotNull(socket).getOutputStream().apply {
                write(buffer, offset, length)
                flush()
            }
        }
    }

    override fun close() {
        socket?.close()
        socket = null
    }

    private fun requireRange(buffer: ByteArray, offset: Int, length: Int) {
        require(offset >= 0 && length >= 0 && offset + length <= buffer.size)
    }
}
