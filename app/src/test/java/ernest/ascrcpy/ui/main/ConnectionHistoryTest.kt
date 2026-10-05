package ernest.ascrcpy.ui.main

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ConnectionHistoryTest {
  @Test fun historiesAreDeduplicatedByMethodAndReusableParameters() {
    val tcp = ConnectionHistoryEntry(ConnectionMethod.TCP, host = "192.168.1.20", port = "5555")
    val otherPort = tcp.copy(port = "43210")
    val tailcat = ConnectionHistoryEntry(ConnectionMethod.TAILCAT, port = "5555",
      tailcatAddress = "example.tailcat")

    val updated = updateConnectionHistory(listOf(tcp, otherPort), tailcat, limitPerMethod = 20)
    val repeated = updateConnectionHistory(updated, tcp, limitPerMethod = 20)

    assertEquals(listOf(tcp, tailcat, otherPort), repeated)
  }

  @Test fun tailcatWirelessHistoryNeverContainsPairingCode() {
    val entry = ConnectionHistoryEntry(ConnectionMethod.TAILCAT_WIRELESS, port = "42817",
      tailcatAddress = "example.tailcat", pairingPort = "37123")

    assertEquals("example.tailcat · 37123 / 42817", entry.summary)
    assertFalse(entry.key.contains("123456"))
  }

  @Test fun historyHonorsLimit() {
    val entries = (1..25).map {
      ConnectionHistoryEntry(ConnectionMethod.TCP, host = "192.168.1.$it", port = "5555")
    }
    val updated = updateConnectionHistory(entries, entries.last(), limitPerMethod = 20)

    assertEquals(20, updated.size)
    assertEquals(entries.last(), updated.first())
  }

  @Test fun limitsEachConnectionMethodIndependently() {
    val tcp = (1..4).map {
      ConnectionHistoryEntry(ConnectionMethod.TCP, host = "192.168.1.$it", port = "5555")
    }
    val tailcat = (1..4).map {
      ConnectionHistoryEntry(ConnectionMethod.TAILCAT, port = "5555", tailcatAddress = "node-$it")
    }

    val limited = limitConnectionHistory(tcp + tailcat, limitPerMethod = 2)

    assertEquals(2, limited.count { it.method == ConnectionMethod.TCP })
    assertEquals(2, limited.count { it.method == ConnectionMethod.TAILCAT })
  }
}
