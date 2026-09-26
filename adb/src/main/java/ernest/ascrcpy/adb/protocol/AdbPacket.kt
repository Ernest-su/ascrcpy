package ernest.ascrcpy.adb.protocol

import ernest.ascrcpy.adb.AdbProtocolException
import ernest.ascrcpy.adb.transport.AdbTransport
import java.nio.ByteBuffer
import java.nio.ByteOrder

internal data class AdbPacket(
    val command: Int,
    val arg0: Int,
    val arg1: Int,
    val payload: ByteArray = byteArrayOf(),
) {
    suspend fun writeTo(transport: AdbTransport) {
        val header = ByteBuffer.allocate(HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(command)
            .putInt(arg0)
            .putInt(arg1)
            .putInt(payload.size)
            .putInt(payload.sumOf { it.toUByte().toInt() })
            .putInt(command xor -1)
            .array()
        transport.write(header)
        if (payload.isNotEmpty()) transport.write(payload)
    }

    companion object {
        const val A_SYNC = 0x434e5953
        const val A_CNXN = 0x4e584e43
        const val A_OPEN = 0x4e45504f
        const val A_OKAY = 0x59414b4f
        const val A_CLSE = 0x45534c43
        const val A_WRTE = 0x45545257
        const val A_AUTH = 0x48545541

        const val AUTH_TOKEN = 1
        const val AUTH_SIGNATURE = 2
        const val AUTH_RSAPUBLICKEY = 3
        const val VERSION = 0x01000001
        const val MAX_DATA = 1024 * 1024
        private const val HEADER_SIZE = 24

        suspend fun readFrom(transport: AdbTransport): AdbPacket {
            val headerBytes = ByteArray(HEADER_SIZE)
            transport.readExactly(headerBytes)
            val header = ByteBuffer.wrap(headerBytes).order(ByteOrder.LITTLE_ENDIAN)
            val command = header.int
            val arg0 = header.int
            val arg1 = header.int
            val length = header.int
            val checksum = header.int
            val magic = header.int
            if (magic != (command xor -1)) throw AdbProtocolException("Invalid ADB packet magic")
            if (length !in 0..MAX_DATA) throw AdbProtocolException("Invalid ADB payload length: $length")
            val payload = ByteArray(length)
            if (length > 0) transport.readExactly(payload)
            val actualChecksum = payload.sumOf { it.toUByte().toInt() }
            // Since protocol 0x01000001 peers may omit checksums and send zero.
            if (checksum != 0 && checksum != actualChecksum) {
                throw AdbProtocolException("Invalid ADB payload checksum")
            }
            return AdbPacket(command, arg0, arg1, payload)
        }
    }
}
