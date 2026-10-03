package ernest.ascrcpy.ui.device

import ernest.ascrcpy.adb.AdbChannel
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceManagerTest {
    @Test fun listsSyncEntriesAndSkipsDotDirectory() = runBlocking {
        val input = dent(".", 0x4000, 0, 0) + dent("hello.txt", 0x8000, 42, 123) + done()
        val channel = FakeChannel(input)
        val entries = listSyncFiles(channel, "/sdcard")
        assertEquals(1, entries.size)
        assertEquals(RemoteFile("hello.txt", "/sdcard/hello.txt", 0x8000, 42, 123), entries.single())
        assertEquals("LIST", channel.written.copyOfRange(0, 4).toString(Charsets.US_ASCII))
        assertEquals("/sdcard", channel.written.copyOfRange(8, channel.written.size).toString(Charsets.UTF_8))
    }

    @Test fun rejectsMalformedSyncNameLength() = runBlocking {
        val bad = "DENT".toByteArray() + ints(0x8000, 0, 0, 256)
        assertTrue(runCatching { listSyncFiles(FakeChannel(bad), "/") }.isFailure)
    }

    @Test fun deletesOnlySafePathsAndChecksExitStatus() {
        val entry = RemoteFile("a'b", "/sdcard/a'b", 0x8000, 0, 0)
        assertTrue(DeviceManager.deleteCommand(entry).contains("'/sdcard/a'\\''b'"))
        assertTrue(runCatching { DeviceManager.deleteCommand(entry.copy(path = "/sdcard/../x")) }.isFailure)
        DeviceManager.requireDeleteSuccess("\n__ADB_DELETE_EXIT__:0\n")
        assertTrue(runCatching { DeviceManager.requireDeleteSuccess("denied\n__ADB_DELETE_EXIT__:1\n") }.isFailure)
    }

    @Test fun appActionsValidatePackagesAndRequireSuccess() {
        assertEquals("pm clear --user 10 'com.example.notes'",
            DeviceManager.appCommand(AppAction.CLEAR_DATA, "com.example.notes", 10))
        assertEquals("pm uninstall --user 0 'com.example.notes'",
            DeviceManager.appCommand(AppAction.UNINSTALL, "com.example.notes", 0))
        assertEquals("pm disable-user --user 0 'com.example.notes'",
            DeviceManager.appCommand(AppAction.DISABLE, "com.example.notes", 0))
        assertEquals("pm enable --user 0 'com.example.notes'",
            DeviceManager.appCommand(AppAction.ENABLE, "com.example.notes", 0))
        assertTrue(runCatching { DeviceManager.appCommand(AppAction.UNINSTALL, "com.example.x;reboot", 0) }.isFailure)
        assertTrue(runCatching { DeviceManager.appCommand(AppAction.CLEAR_DATA, "com.example.x", -1) }.isFailure)
        DeviceManager.requireAppSuccess(AppAction.CLEAR_DATA, "com.example.notes", "Success\n", 0)
        DeviceManager.requireAppSuccess(AppAction.DISABLE, "com.example.notes",
            "Package com.example.notes new state: disabled-user\n", 0)
        DeviceManager.requireAppSuccess(AppAction.ENABLE, "com.example.notes",
            "Package com.example.notes new state: enabled\n", 0)
        assertTrue(runCatching { DeviceManager.requireAppSuccess(AppAction.DISABLE, "com.example.notes",
            "Package com.example.notes new state: enabled", 0) }.isFailure)
        assertTrue(runCatching { DeviceManager.requireAppSuccess(AppAction.UNINSTALL, "com.example.notes",
            "Failure [DELETE_FAILED]", 0) }.isFailure)
        assertTrue(runCatching { DeviceManager.requireAppSuccess(AppAction.CLEAR_DATA, "com.example.notes",
            "Success", 1) }.isFailure)
    }

    private fun dent(name: String, mode: Int, size: Int, modified: Int): ByteArray =
        "DENT".toByteArray() + ints(mode, size, modified, name.toByteArray().size) + name.toByteArray()

    private fun done(): ByteArray = "DONE".toByteArray() + ints(0, 0, 0, 0)

    private fun ints(vararg values: Int): ByteArray = ByteBuffer.allocate(values.size * 4)
        .order(ByteOrder.LITTLE_ENDIAN).apply { values.forEach(::putInt) }.array()

    private class FakeChannel(private val input: ByteArray) : AdbChannel {
        private var offset = 0
        private val output = mutableListOf<Byte>()
        val written: ByteArray get() = output.toByteArray()
        override suspend fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (this.offset == input.size) return -1
            val count = minOf(length, 3, input.size - this.offset)
            input.copyInto(buffer, offset, this.offset, this.offset + count)
            this.offset += count
            return count
        }
        override suspend fun write(buffer: ByteArray, offset: Int, length: Int) {
            output.addAll(buffer.copyOfRange(offset, offset + length).toList())
        }
        override fun close() = Unit
    }
}
