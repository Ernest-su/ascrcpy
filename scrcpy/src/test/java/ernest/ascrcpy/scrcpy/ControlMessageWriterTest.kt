package ernest.ascrcpy.scrcpy

import ernest.ascrcpy.adb.AdbChannel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Test

class ControlMessageWriterTest {
    @Test fun displayPowerUsesScrcpy4BooleanPayload(): Unit = runBlocking {
        val messages = mutableListOf<ByteArray>()
        val channel = object : AdbChannel {
            override suspend fun read(buffer: ByteArray, offset: Int, length: Int): Int = -1
            override suspend fun write(buffer: ByteArray, offset: Int, length: Int) {
                messages += buffer.copyOfRange(offset, offset + length)
            }
            override fun close() = Unit
        }
        val writer = ControlMessageWriter(channel)
        writer.setDisplayPower(false)
        writer.setDisplayPower(true)
        writer.setDisplayPower(false)
        assertArrayEquals(byteArrayOf(10, 0), messages[0])
        assertArrayEquals(byteArrayOf(10, 1), messages[1])
        assertArrayEquals(byteArrayOf(10, 0), messages[2])
    }
}
