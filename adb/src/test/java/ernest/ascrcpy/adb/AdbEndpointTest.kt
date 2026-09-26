package ernest.ascrcpy.adb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class AdbEndpointTest {
    @Test
    fun serialCombinesHostAndPort() {
        assertEquals("192.168.1.12:5555", AdbEndpoint("192.168.1.12").serial)
    }

    @Test
    fun rejectsInvalidPort() {
        assertThrows(IllegalArgumentException::class.java) { AdbEndpoint("host", 0) }
    }
}
