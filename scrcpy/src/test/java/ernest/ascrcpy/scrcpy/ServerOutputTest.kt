package ernest.ascrcpy.scrcpy

import ernest.ascrcpy.adb.AdbChannel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerOutputTest {
    @Test fun consumesLargeOutputWithoutGrowingOrWritingToTheChannel(): Unit = runBlocking {
        var reads = 0
        var buffers = mutableSetOf<Int>()
        val channel = fake { buffer, _, count ->
            assertEquals(8192, buffer.size)
            assertEquals(buffer.size, count)
            buffers.add(System.identityHashCode(buffer))
            if (++reads <= 200) count else -1
        }
        drainServerOutput(channel)
        assertEquals(201, reads)
        assertEquals(1, buffers.size)
    }

    @Test fun propagatesFailureAndCancellationToTheSessionOwner(): Unit = runBlocking {
        for (error in listOf(IOException("closed"), CancellationException("cancelled"))) {
            var caught: Exception? = null
            try { drainServerOutput(fake { _, _, _ -> throw error }) }
            catch (actual: Exception) { caught = actual }
            assertTrue(caught === error)
        }
    }

    private fun fake(reader: suspend (ByteArray, Int, Int) -> Int) = object : AdbChannel {
        override suspend fun read(buffer: ByteArray, offset: Int, length: Int): Int = reader(buffer, offset, length)
        override suspend fun write(buffer: ByteArray, offset: Int, length: Int) {
            throw AssertionError("Output draining must never write to the server channel")
        }
        override fun close() = Unit
    }
}
