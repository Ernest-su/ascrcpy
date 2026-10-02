package ernest.ascrcpy.ui.main

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

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
}
