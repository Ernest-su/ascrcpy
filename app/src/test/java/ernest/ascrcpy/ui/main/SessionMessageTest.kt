package ernest.ascrcpy.ui.main

import ernest.ascrcpy.R
import ernest.ascrcpy.scrcpy.ScrcpyState
import ernest.ascrcpy.scrcpy.VideoSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SessionMessageTest {
  @Test fun derivesConsoleMessageFromTheSameScrcpyState() {
    assertNull(scrcpySessionMessage(ScrcpyState.Idle, "unknown"))
    assertEquals(R.string.scrcpy_installing,
      scrcpySessionMessage(ScrcpyState.InstallingServer, "unknown")?.resourceId)
    assertEquals(R.string.scrcpy_starting,
      scrcpySessionMessage(ScrcpyState.StartingServer, "unknown")?.resourceId)
    assertEquals(R.string.scrcpy_connecting_streams,
      scrcpySessionMessage(ScrcpyState.ConnectingStreams, "unknown")?.resourceId)

    val streaming = scrcpySessionMessage(ScrcpyState.Streaming(VideoSize(864, 1920)), "unknown")
    assertEquals(R.string.scrcpy_streaming, streaming?.resourceId)
    assertEquals(listOf(864, 1920), streaming?.arguments)
  }

  @Test fun suppliesFallbackForFailureWithoutMessage() {
    val failed = scrcpySessionMessage(ScrcpyState.Failed(Exception()), "unknown")
    assertEquals(R.string.scrcpy_failed, failed?.resourceId)
    assertEquals(listOf("unknown"), failed?.arguments)
  }
}
