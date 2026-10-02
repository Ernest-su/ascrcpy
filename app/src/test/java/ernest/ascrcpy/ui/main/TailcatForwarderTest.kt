package ernest.ascrcpy.ui.main

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TailcatForwarderTest {
  @Test fun parsesOnlyTheRequestedLoopbackForward() {
    assertEquals(41123, parseForwardedPort("# forwarding 127.0.0.1:41123 -> remote 5555", 5555))
    assertNull(parseForwardedPort("# forwarding 0.0.0.0:41123 -> remote 5555", 5555))
    assertNull(parseForwardedPort("# forwarding 127.0.0.1:41123 -> remote 1234", 5555))
    assertNull(parseForwardedPort("# forwarding 127.0.0.1:0 -> remote 5555", 5555))
    assertNull(parseForwardedPort("# forwarding 127.0.0.1:65536 -> remote 5555", 5555))
  }
}
