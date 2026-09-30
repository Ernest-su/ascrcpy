package ernest.ascrcpy.ui.main

import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import ernest.ascrcpy.R
import ernest.ascrcpy.adb.AdbConnectionState
import ernest.ascrcpy.adb.AdbDevice
import ernest.ascrcpy.adb.AdbEndpoint
import ernest.ascrcpy.scrcpy.ScrcpyState
import ernest.ascrcpy.scrcpy.VideoSize
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class MainScreenTest {
  @get:Rule val composeTestRule = createAndroidComposeRule<ComponentActivity>()

  @Test
  fun disconnectedDeviceShowsConnectAction() {
    composeTestRule.setContent {
      MainScreen(MainUiState(connectionState = AdbConnectionState.Disconnected), {}, {}, {}, {}, {}, {}, {}, {},
        {}, { _, _, _, _, _ -> }, {}, {})
    }
    composeTestRule.onNodeWithText(composeTestRule.activity.getString(R.string.connect)).assertExists()
    composeTestRule.onNodeWithText(composeTestRule.activity.getString(R.string.preview_controls_title)).assertExists()
  }

  @Test
  fun savedHostCanBeDeletedFromSuggestions() {
    var deletedHost: String? = null
    composeTestRule.setContent {
      MainScreen(MainUiState(host = "", hostHistory = listOf("192.168.1.20")), {}, {}, {}, {}, {}, {}, {}, {},
        {}, { _, _, _, _, _ -> }, {}, { deletedHost = it })
    }

    composeTestRule.onNodeWithText(composeTestRule.activity.getString(R.string.ip_address_or_host)).performClick()
    composeTestRule.onNodeWithContentDescription(
      composeTestRule.activity.getString(R.string.delete_host, "192.168.1.20")
    ).performClick()
    composeTestRule.runOnIdle { assert(deletedHost == "192.168.1.20") }
  }

  @Test
  fun editingHostKeepsKeyboardFocusWhileSuggestionsAreShown() {
    val host = mutableStateOf("")
    composeTestRule.setContent {
      MainScreen(MainUiState(host = host.value, hostHistory = listOf("192.168.1.20", "192.168.1.21")),
        { host.value = it }, {}, {}, {}, {}, {}, {}, {},
        {}, { _, _, _, _, _ -> }, {}, {})
    }

    val field = composeTestRule.onAllNodes(hasSetTextAction())[0]
    field.performClick()
    composeTestRule.onNodeWithText("192.168.1.20").assertExists()

    // Typing and deleting re-filter the suggestion list. The list lives in a non-focusable popup, so
    // it must not take window focus away from the field, which is what dismisses the keyboard.
    field.performTextInput("192.168.1.2")
    composeTestRule.onNodeWithText("192.168.1.2").assertExists()
    field.performTextClearance()

    assertEquals("", host.value)
    composeTestRule.onNodeWithText("192.168.1.21").assertExists()
    field.assertIsFocused()
  }

  @Test
  fun expandedRemoteShowsPowerAndVolumeControls() {
    val connected = AdbConnectionState.Connected(AdbDevice(AdbEndpoint("192.168.1.20"), "", emptyMap()))
    composeTestRule.setContent {
      MainScreen(MainUiState(connectionState = connected, scrcpyState = ScrcpyState.Streaming(VideoSize(1920, 1080))),
        {}, {}, {}, {}, {}, {}, {}, {}, {}, { _, _, _, _, _ -> }, {}, {})
    }

    composeTestRule.onNodeWithContentDescription(composeTestRule.activity.getString(R.string.floating_remote_control)).performClick()
    composeTestRule.onNodeWithContentDescription(composeTestRule.activity.getString(R.string.power)).assertExists()
    composeTestRule.onNodeWithContentDescription(composeTestRule.activity.getString(R.string.volume_down)).assertExists()
    composeTestRule.onNodeWithContentDescription(composeTestRule.activity.getString(R.string.volume_up)).assertExists()
  }

  @Test
  fun draggedRemoteStaysWhereItWasDropped() {
    val connected = AdbConnectionState.Connected(AdbDevice(AdbEndpoint("192.168.1.20"), "", emptyMap()))
    composeTestRule.setContent {
      MainScreen(MainUiState(connectionState = connected, scrcpyState = ScrcpyState.Streaming(VideoSize(1920, 1080))),
        {}, {}, {}, {}, {}, {}, {}, {}, {}, { _, _, _, _, _ -> }, {}, {})
    }

    val root = composeTestRule.onRoot()
    val screen = root.fetchSemanticsNode().boundsInRoot
    root.performTouchInput {
      swipe(start = Offset(screen.right - 30f, screen.center.y),
        end = Offset(screen.center.x, screen.center.y), durationMillis = 400)
    }

    // Releasing away from an edge grows the panel around the dropped icon. Re-docking it against
    // the right edge would make the remote jump away from wherever the user just put it.
    val power = composeTestRule.onNodeWithContentDescription(composeTestRule.activity.getString(R.string.power))
    power.assertExists()
    val panel = power.fetchSemanticsNode().boundsInRoot
    assertTrue("panel re-docked to the right edge: $panel", panel.right < screen.right - screen.width * 0.1f)
    assertTrue("panel did not follow the drag: $panel", panel.left > 0f)
  }

  @Test
  fun repeatedDragsDoNotSnapTheRemoteBackToWhereItWasExpanded() {
    val connected = AdbConnectionState.Connected(AdbDevice(AdbEndpoint("192.168.1.20"), "", emptyMap()))
    composeTestRule.setContent {
      MainScreen(MainUiState(connectionState = connected, scrcpyState = ScrcpyState.Streaming(VideoSize(1920, 1080))),
        {}, {}, {}, {}, {}, {}, {}, {}, {}, { _, _, _, _, _ -> }, {}, {})
    }

    val root = composeTestRule.onRoot()
    val screen = root.fetchSemanticsNode().boundsInRoot
    val power = composeTestRule.onNodeWithContentDescription(composeTestRule.activity.getString(R.string.power))

    // Grow the panel away from the docked edge.
    root.performTouchInput {
      swipe(start = Offset(screen.right - 30f, screen.center.y),
        end = Offset(screen.center.x, screen.center.y), durationMillis = 400)
    }
    val expanded = power.fetchSemanticsNode().boundsInRoot

    fun dragPanel(dx: Float) {
      val current = power.fetchSemanticsNode().boundsInRoot
      root.performTouchInput {
        swipe(start = current.center, end = Offset(current.center.x + dx, current.center.y), durationMillis = 400)
      }
    }

    dragPanel(-300f)
    val movedAway = power.fetchSemanticsNode().boundsInRoot
    dragPanel(300f)
    val movedBack = power.fetchSemanticsNode().boundsInRoot

    // Two opposite drags of equal size must cancel out. A gesture that re-seeds the position from a
    // stale snapshot instead of the live one sends the panel back to where it was expanded.
    assertTrue("left drag did not move the panel: $expanded -> $movedAway",
      movedAway.left < expanded.left - 100f)
    assertTrue("right drag snapped the panel back: $movedAway -> $movedBack",
      abs(movedBack.left - expanded.left) <= 24f)
  }
}
