package ernest.ascrcpy.ui.main

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TailcatForwarderTest {
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
}
