package ernest.ascrcpy.ui.main

import ernest.ascrcpy.adb.AdbEndpoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking

class QrPairingTest {
  @Test fun createsDistinctAdbPairingSecrets() {
    val first = QrPairing()
    val second = QrPairing()
    assertTrue(first.serviceName.startsWith("studio-"))
    assertEquals(16, first.password.length)
    assertEquals("WIFI:T:ADB;S:${first.serviceName};P:${first.password};;", first.payload)
    assertNotEquals(first.serviceName, second.serviceName)
    assertNotEquals(first.password, second.password)
  }

  @Test fun matchesConnectionServiceByPairingGuidIgnoringCase() {
    assertTrue(isConnectionServiceForGuid("adb-device-ABC123-random", "abc123"))
    assertTrue(!isConnectionServiceForGuid("adb-other-device", "abc123"))
    assertTrue(!isConnectionServiceForGuid("adb-device", ""))
  }

  @Test fun recognizesTailscaleAddresses() {
    assertTrue("100.64.0.1".isTailscaleAddress())
    assertTrue("100.127.255.254".isTailscaleAddress())
    assertTrue("fd7a:115c:a1e0::1".isTailscaleAddress())
    assertTrue("[fd7a:115c:a1e0::1]".isTailscaleAddress())
    assertTrue(!"100.128.0.1".isTailscaleAddress())
    assertTrue(!"192.168.1.17".isTailscaleAddress())
  }

  @Test fun automaticConnectionKeepsTailscaleAddressAndUsesDiscoveredPort() {
    assertEquals(AdbEndpoint("100.64.1.25", 37123),
      wirelessConnectionEndpoint("100.64.1.25", "192.168.1.25", 37123))
  }

  @Test fun automaticConnectionUsesDiscoveredAddressForWifiPairing() {
    assertEquals(AdbEndpoint("192.168.1.25", 37123),
      wirelessConnectionEndpoint("device.local", "192.168.1.25", 37123))
  }

  @Test fun skipsStaleMatchingServiceAndUsesReachableDuplicate() = runBlocking {
    val services = Channel<Pair<String, Int>>(Channel.UNLIMITED)
    services.trySend("adb-guid" to 34241)
    services.trySend("adb-guid (2)" to 44307)

    val selected = firstReachableWirelessService(
      services,
      matches = { (name) -> name.contains("guid") },
      isReachable = { (_, port) -> port == 44307 },
    )

    assertEquals(44307, selected.second)
    services.close()
    Unit
  }
}
