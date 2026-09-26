package ernest.ascrcpy.adb

import android.content.Context
import ernest.ascrcpy.adb.crypto.FileAdbKeyProvider
import ernest.ascrcpy.adb.protocol.AdbPacket
import ernest.ascrcpy.adb.protocol.AdbPacket.Companion.A_AUTH
import ernest.ascrcpy.adb.protocol.AdbPacket.Companion.A_CLSE
import ernest.ascrcpy.adb.protocol.AdbPacket.Companion.A_CNXN
import ernest.ascrcpy.adb.protocol.AdbPacket.Companion.A_OKAY
import ernest.ascrcpy.adb.protocol.AdbPacket.Companion.A_OPEN
import ernest.ascrcpy.adb.protocol.AdbPacket.Companion.A_WRTE
import ernest.ascrcpy.adb.transport.AdbTransport
import ernest.ascrcpy.adb.transport.AdbTransportFactory
import ernest.ascrcpy.adb.transport.TcpAdbTransport
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class DefaultAdbClient(
    private val keyProvider: AdbKeyProvider,
    private val transportFactory: AdbTransportFactory = AdbTransportFactory { host, port ->
        TcpAdbTransport(host, port)
    },
) : AdbClient {
    private val mutableState = MutableStateFlow<AdbConnectionState>(AdbConnectionState.Disconnected)
    override val state: StateFlow<AdbConnectionState> = mutableState.asStateFlow()

    private val lifecycleMutex = Mutex()
    private val writeMutex = Mutex()
    private val operationMutex = Mutex()
    private val nextLocalId = AtomicInteger(1)
    private val streams = ConcurrentHashMap<Int, Stream>()
    private var transport: AdbTransport? = null
    private var scope: CoroutineScope? = null
    private var readerJob: Job? = null

    override suspend fun connect(endpoint: AdbEndpoint): AdbDevice = lifecycleMutex.withLock {
        disconnectInternal()
        mutableState.value = AdbConnectionState.Connecting(endpoint)
        val newTransport = transportFactory.create(endpoint.host, endpoint.port)
        transport = newTransport
        try {
            newTransport.connect()
            sendPacket(AdbPacket(A_CNXN, AdbPacket.VERSION, AdbPacket.MAX_DATA, CLIENT_BANNER))
            val device = authenticate(newTransport, endpoint)
            mutableState.value = AdbConnectionState.Connected(device)
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            readerJob = scope!!.launch { readLoop(newTransport) }
            device
        } catch (error: Throwable) {
            newTransport.close()
            transport = null
            mutableState.value = AdbConnectionState.Failed(endpoint, error)
            throw if (error is AdbException) error else AdbException("Unable to connect to ${endpoint.serial}", error)
        }
    }

    private suspend fun authenticate(activeTransport: AdbTransport, endpoint: AdbEndpoint): AdbDevice {
        var sentPublicKey = false
        while (true) {
            val packet = AdbPacket.readFrom(activeTransport)
            when (packet.command) {
                A_CNXN -> return parseDevice(endpoint, packet.payload)
                A_AUTH -> {
                    if (packet.arg0 != AdbPacket.AUTH_TOKEN) {
                        throw AdbAuthenticationException("Unsupported ADB authentication challenge")
                    }
                    mutableState.value = AdbConnectionState.Authorizing(endpoint)
                    if (!sentPublicKey) {
                        sendPacket(AdbPacket(A_AUTH, AdbPacket.AUTH_SIGNATURE, 0, keyProvider.sign(packet.payload)))
                        sentPublicKey = true
                    } else {
                        sendPacket(AdbPacket(A_AUTH, AdbPacket.AUTH_RSAPUBLICKEY, 0, keyProvider.encodedPublicKey()))
                    }
                }
                else -> throw AdbProtocolException("Expected CNXN or AUTH, got 0x${packet.command.toString(16)}")
            }
        }
    }

    override suspend fun disconnect() = lifecycleMutex.withLock { disconnectInternal() }

    private fun disconnectInternal() {
        readerJob?.cancel()
        readerJob = null
        scope?.cancel()
        scope = null
        streams.values.forEach { it.remoteClosed() }
        streams.clear()
        transport?.close()
        transport = null
        mutableState.value = AdbConnectionState.Disconnected
    }

    override suspend fun shell(command: String): AdbCommandResult = operationMutex.withLock {
        require('\u0000' !in command) { "Shell command must not contain NUL" }
        val channel = open("shell:$command")
        try {
            AdbCommandResult(channel.readAll())
        } finally {
            channel.close()
        }
    }

    override suspend fun push(source: InputStream, remotePath: String, mode: Int) = operationMutex.withLock {
        require(remotePath.startsWith('/')) { "Remote path must be absolute" }
        val channel = open("sync:")
        try {
            channel.writeSync("SEND", "$remotePath,$mode".toByteArray())
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = source.read(buffer)
                if (count < 0) break
                channel.writeSync("DATA", buffer.copyOf(count))
            }
            channel.writeSync("DONE", ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(Instant.now().epochSecond.toInt()).array(), payloadIsLength = false)
            val status = channel.readExactly(8)
            val id = status.copyOfRange(0, 4).toString(Charsets.US_ASCII)
            val length = ByteBuffer.wrap(status, 4, 4).order(ByteOrder.LITTLE_ENDIAN).int
            if (id == "FAIL") {
                throw AdbException(channel.readExactly(length).toString(Charsets.UTF_8))
            }
            if (id != "OKAY") throw AdbProtocolException("Unexpected sync response: $id")
        } finally {
            channel.close()
        }
    }

    override suspend fun open(service: String): AdbChannel {
        check(transport != null) { "ADB client is not connected" }
        require(service.isNotBlank() && '\u0000' !in service)
        val localId = nextLocalId.getAndIncrement()
        val stream = Stream(localId)
        streams[localId] = stream
        try {
            sendPacket(AdbPacket(A_OPEN, localId, 0, "$service\u0000".toByteArray()))
            stream.opened.await()
            return stream
        } catch (error: Throwable) {
            streams.remove(localId)
            throw error
        }
    }

    private suspend fun readLoop(activeTransport: AdbTransport) {
        try {
            while (true) {
                val packet = AdbPacket.readFrom(activeTransport)
                val stream = streams[packet.arg1] ?: continue
                when (packet.command) {
                    A_OKAY -> stream.markOpened(packet.arg0)
                    A_WRTE -> {
                        stream.receive(packet.payload)
                        sendPacket(AdbPacket(A_OKAY, stream.localId, packet.arg0))
                    }
                    A_CLSE -> {
                        streams.remove(stream.localId)
                        stream.remoteClosed()
                        sendPacket(AdbPacket(A_CLSE, stream.localId, packet.arg0))
                    }
                }
            }
        } catch (error: Throwable) {
            streams.values.forEach { it.remoteClosed(error) }
            streams.clear()
            val current = mutableState.value
            if (current is AdbConnectionState.Connected) {
                mutableState.value = AdbConnectionState.Failed(current.device.endpoint, error)
            }
        }
    }

    private suspend fun sendPacket(packet: AdbPacket) = writeMutex.withLock {
        packet.writeTo(checkNotNull(transport) { "ADB client is not connected" })
    }

    override fun close() {
        disconnectInternal()
    }

    private inner class Stream(val localId: Int) : AdbChannel {
        val opened = CompletableDeferred<Unit>()
        private val incoming = Channel<ByteArray>(Channel.UNLIMITED)
        private var remoteId: Int = 0
        private var current = byteArrayOf()
        private var currentOffset = 0

        fun markOpened(id: Int) {
            remoteId = id
            opened.complete(Unit)
        }

        suspend fun receive(bytes: ByteArray) = incoming.send(bytes)

        fun remoteClosed(error: Throwable? = null) {
            if (!opened.isCompleted && error != null) opened.completeExceptionally(error)
            else if (!opened.isCompleted) opened.completeExceptionally(AdbException("ADB stream rejected"))
            incoming.close(error)
        }

        override suspend fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            require(offset >= 0 && length >= 0 && offset + length <= buffer.size)
            if (length == 0) return 0
            while (currentOffset >= current.size) {
                current = incoming.receiveCatching().getOrNull() ?: return -1
                currentOffset = 0
            }
            val count = minOf(length, current.size - currentOffset)
            current.copyInto(buffer, offset, currentOffset, currentOffset + count)
            currentOffset += count
            return count
        }

        override suspend fun write(buffer: ByteArray, offset: Int, length: Int) {
            require(offset >= 0 && length >= 0 && offset + length <= buffer.size)
            var position = offset
            val end = offset + length
            while (position < end) {
                val count = minOf(AdbPacket.MAX_DATA, end - position)
                sendPacket(AdbPacket(A_WRTE, localId, remoteId, buffer.copyOfRange(position, position + count)))
                position += count
            }
        }

        override fun close() {
            streams.remove(localId)
            scope?.launch { runCatching { sendPacket(AdbPacket(A_CLSE, localId, remoteId)) } }
            incoming.close()
        }
    }

    companion object {
        private val CLIENT_BANNER = "host::features=shell_v2,cmd,stat_v2\u0000".toByteArray()

        fun factory(context: Context): AdbClientFactory {
            val appContext = context.applicationContext
            return AdbClientFactory { DefaultAdbClient(FileAdbKeyProvider.create(appContext)) }
        }
    }
}

private fun parseDevice(endpoint: AdbEndpoint, payload: ByteArray): AdbDevice {
    val banner = payload.toString(Charsets.UTF_8).trimEnd('\u0000')
    val properties = banner.substringAfter("::", "").split(';').mapNotNull { item ->
        val separator = item.indexOf('=')
        if (separator <= 0) null else item.substring(0, separator) to item.substring(separator + 1)
    }.toMap()
    return AdbDevice(endpoint, banner, properties)
}

private suspend fun AdbChannel.readAll(): ByteArray {
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(16 * 1024)
    while (true) {
        val count = read(buffer)
        if (count < 0) return output.toByteArray()
        output.write(buffer, 0, count)
    }
}

private suspend fun AdbChannel.readExactly(length: Int): ByteArray {
    val result = ByteArray(length)
    var offset = 0
    while (offset < length) {
        val count = read(result, offset, length - offset)
        if (count < 0) throw AdbProtocolException("Unexpected end of ADB stream")
        offset += count
    }
    return result
}

private suspend fun AdbChannel.writeSync(id: String, payload: ByteArray, payloadIsLength: Boolean = true) {
    require(id.length == 4)
    val header = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
        .put(id.toByteArray(Charsets.US_ASCII))
        .putInt(if (payloadIsLength) payload.size else ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN).int)
        .array()
    write(header)
    if (payloadIsLength) write(payload)
}
