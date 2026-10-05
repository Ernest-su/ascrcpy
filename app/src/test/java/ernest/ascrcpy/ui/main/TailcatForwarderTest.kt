package ernest.ascrcpy.ui.main

import ernest.ascrcpy.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TailcatForwarderTest {
  @Test fun tailcatConnectionFailureDistinguishesPairingOutcome() {
    assertEquals(R.string.log_tailcat_paired_connection_failed,
      tailcatConnectionFailureMessage(pairingConfirmed = true, pairingUnconfirmed = false))
    assertEquals(R.string.log_tailcat_pairing_unconfirmed,
      tailcatConnectionFailureMessage(pairingConfirmed = false, pairingUnconfirmed = true))
    assertEquals(R.string.log_tailcat_connection_failed,
      tailcatConnectionFailureMessage(pairingConfirmed = false, pairingUnconfirmed = false))
  }

  @Test fun suppliesWritableConfigEnvironmentForAndroidChildProcess() {
    val environment = mutableMapOf("HOME" to "/", "XDG_CONFIG_HOME" to "/read-only")

    configureTailcatEnvironment(environment, "/data/user/0/ernest.ascrcpy/no_backup/tailcat-config")

    assertEquals("/data/user/0/ernest.ascrcpy/no_backup/tailcat-config", environment["HOME"])
    assertEquals("/data/user/0/ernest.ascrcpy/no_backup/tailcat-config", environment["XDG_CONFIG_HOME"])
  }

  @Test fun parsesOnlyTheRequestedLoopbackForward() {
    assertEquals(41123, parseForwardedPort("# forwarding 127.0.0.1:41123 -> remote 5555", 5555))
    assertEquals(41123, parseForwardedPort("# forwarding 127.0.0.1:41123 -> remote localhost:5555", 5555))
    assertNull(parseForwardedPort("# forwarding 0.0.0.0:41123 -> remote 5555", 5555))
    assertNull(parseForwardedPort("# forwarding 127.0.0.1:41123 -> remote 1234", 5555))
    assertNull(parseForwardedPort("# forwarding 127.0.0.1:41123 -> remote localhost:1234", 5555))
    assertNull(parseForwardedPort("# forwarding 127.0.0.1:0 -> remote 5555", 5555))
    assertNull(parseForwardedPort("# forwarding 127.0.0.1:65536 -> remote 5555", 5555))
  }
  @Test fun pairingNeedsTwoCurrentDistinctRemotePorts() {
    assertEquals(TailcatPairingPorts(37123, 42817), tailcatPairingPorts("37123", "42817"))
    for ((pair, connect) in listOf("0" to "42817", "37123" to "65536", "37123" to "37123",
      "" to "42817", "not-a-port" to "42817")) assertNull(tailcatPairingPorts(pair, connect))
  }

  @Test fun multiPortOutputCannotConfusePairingWithConnection() {
    val pairLine = "# forwarding 127.0.0.1:44001 -> remote localhost:37123"
    val tlsLine = "# forwarding 127.0.0.1:44002 -> remote localhost:42817"
    assertEquals(44001, parseForwardedPort(pairLine, 37123))
    assertEquals(44002, parseForwardedPort(tlsLine, 42817))
    assertNull(parseForwardedPort(pairLine, 42817))
    assertNull(parseForwardedPort(tlsLine, 37123))
    assertNull(parseForwardedPort("credential # forwarding 127.0.0.1:44001 -> remote 37123", 37123))
  }

  @Test fun rejectsAddressesThatCouldBecomeOptionsOrSplitArguments() {
    org.junit.Assert.assertTrue(validTailcatAddress("tc-example-address"))
    for (address in listOf("", " ", "-option", "tc address", "tc\naddress")) {
      org.junit.Assert.assertFalse(validTailcatAddress(address))
    }
  }

}
