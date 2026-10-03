package ernest.ascrcpy.ui.main

import androidx.annotation.StringRes
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.unit.dp
import ernest.ascrcpy.R
import ernest.ascrcpy.adb.AdbConnectionState
import ernest.ascrcpy.adb.AdbDevice
import ernest.ascrcpy.adb.AdbEndpoint
import ernest.ascrcpy.scrcpy.ScrcpyState
import ernest.ascrcpy.scrcpy.VideoSize
import ernest.ascrcpy.theme.AScrcpyTheme
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class MainScreenTest {
  @get:Rule val composeTestRule = createAndroidComposeRule<ComponentActivity>()

  private fun text(@StringRes id: Int): String = composeTestRule.activity.getString(id)

  private fun connectedState() = AdbConnectionState.Connected(AdbDevice(AdbEndpoint("192.168.1.20"), "", emptyMap()))

  @Test
  fun disconnectedDeviceShowsConnectAction() {
    composeTestRule.setContent {
      MainScreen(MainUiState(connectionState = AdbConnectionState.Disconnected), {}, {}, {}, {}, {}, {}, {}, {},
        {}, { _, _, _, _, _ -> }, {}, {})
    }
    composeTestRule.onNodeWithText(text(R.string.connect)).assertExists()
    composeTestRule.onNodeWithText(text(R.string.preview_controls_title)).assertExists()
  }

  @Test
  fun wirelessCodeShowsOnlyPairingFieldsBeforePairing() {
    composeTestRule.setContent {
      MainScreen(MainUiState(method = ConnectionMethod.WIRELESS_CODE), {}, {}, {}, {}, {}, {}, {}, {},
        {}, { _, _, _, _, _ -> }, {}, {})
    }

    composeTestRule.onNodeWithText(text(R.string.pairing_host)).assertExists()
    composeTestRule.onNodeWithText(text(R.string.pairing_port)).assertExists()
    composeTestRule.onNodeWithText(text(R.string.pairing_code)).assertExists()
    composeTestRule.onNodeWithText(text(R.string.ip_address_or_host)).assertDoesNotExist()
    composeTestRule.onNodeWithText(text(R.string.port)).assertDoesNotExist()
    composeTestRule.onNodeWithText(text(R.string.connect)).assertDoesNotExist()
  }

  @Test
  fun wirelessCodeShowsOnlyConnectionPortAfterPairing() {
    composeTestRule.setContent {
      MainScreen(MainUiState(method = ConnectionMethod.WIRELESS_CODE, pairingHost = "192.168.1.20",
        wirelessCodePaired = true), {}, {}, {}, {}, {}, {}, {}, {},
        {}, { _, _, _, _, _ -> }, {}, {})
    }

    composeTestRule.onNodeWithText(text(R.string.wireless_connection_port)).assertExists()
    composeTestRule.onNodeWithText(text(R.string.pairing_host)).assertDoesNotExist()
    composeTestRule.onNodeWithText(text(R.string.pairing_port)).assertDoesNotExist()
    composeTestRule.onNodeWithText(text(R.string.pairing_code)).assertDoesNotExist()
    composeTestRule.onNodeWithText(text(R.string.ip_address_or_host)).assertDoesNotExist()
    composeTestRule.onNodeWithText(text(R.string.connect)).assertExists()
    composeTestRule.onNodeWithText(text(R.string.pair_different_wireless_device)).assertExists()
    composeTestRule.onNodeWithText(text(R.string.connect_saved_wireless_device)).assertExists()
  }

  @Test
  fun usbShowsNoNetworkFields() {
    composeTestRule.setContent {
      MainScreen(MainUiState(method = ConnectionMethod.USB), {}, {}, {}, {}, {}, {}, {}, {},
        {}, { _, _, _, _, _ -> }, {}, {})
    }

    composeTestRule.onNodeWithText(text(R.string.ip_address_or_host)).assertDoesNotExist()
    composeTestRule.onNodeWithText(text(R.string.port)).assertDoesNotExist()
    composeTestRule.onNodeWithText(text(R.string.pairing_host)).assertDoesNotExist()
  }

  @Test
  fun savedWirelessDevicesCanBeSelectedByName() {
    var selected: String? = null
    composeTestRule.setContent {
      MainScreen(MainUiState(method = ConnectionMethod.WIRELESS_CODE, wirelessCodePaired = true,
        pairingHost = "192.168.1.20", savedWirelessDevices = listOf(
          SavedWirelessDevice("guid-phone", "Living room phone", "192.168.1.20", "37123"),
          SavedWirelessDevice("guid-tv", "Bedroom TV", "100.64.1.8", "38234"),
        ), selectedWirelessGuid = "guid-phone"), {}, {}, {}, {}, {}, {}, {}, {},
        {}, { _, _, _, _, _ -> }, {}, {}, onSelectSavedWireless = { selected = it })
    }

    composeTestRule.onNodeWithText("Living room phone").assertExists()
    composeTestRule.onNodeWithText("Bedroom TV").performClick()
    composeTestRule.runOnIdle { assertEquals("guid-tv", selected) }
  }

  @Test
  fun savedHostCanBeDeletedFromSuggestions() {
    var deletedHost: String? = null
    composeTestRule.setContent {
      MainScreen(MainUiState(host = "", hostHistory = listOf("192.168.1.20")), {}, {}, {}, {}, {}, {}, {}, {},
        {}, { _, _, _, _, _ -> }, {}, { deletedHost = it })
    }

    composeTestRule.onNodeWithText(text(R.string.ip_address_or_host)).performClick()
    composeTestRule.onNodeWithContentDescription(composeTestRule.activity.getString(R.string.delete_host, "192.168.1.20"))
      .performClick()
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
    composeTestRule.setContent {
      MainScreen(MainUiState(connectionState = connectedState(), scrcpyState = ScrcpyState.Streaming(VideoSize(1920, 1080))),
        {}, {}, {}, {}, {}, {}, {}, {}, {}, { _, _, _, _, _ -> }, {}, {})
    }

    composeTestRule.onNodeWithContentDescription(text(R.string.floating_remote_control)).performClick()
    composeTestRule.onNodeWithContentDescription(text(R.string.power)).assertExists()
    composeTestRule.onNodeWithContentDescription(text(R.string.volume_down)).assertExists()
    composeTestRule.onNodeWithContentDescription(text(R.string.volume_up)).assertExists()
  }

  @Test
  fun draggedRemoteStaysWhereItWasDropped() {
    composeTestRule.setContent {
      MainScreen(MainUiState(connectionState = connectedState(), scrcpyState = ScrcpyState.Streaming(VideoSize(1920, 1080))),
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
    val power = composeTestRule.onNodeWithContentDescription(text(R.string.power))
    power.assertExists()
    val panel = power.fetchSemanticsNode().boundsInRoot
    assertTrue("panel re-docked to the right edge: $panel", panel.right < screen.right - screen.width * 0.1f)
    assertTrue("panel did not follow the drag: $panel", panel.left > 0f)
  }

  @Test
  fun repeatedDragsDoNotSnapTheRemoteBackToWhereItWasExpanded() {
    composeTestRule.setContent {
      MainScreen(MainUiState(connectionState = connectedState(), scrcpyState = ScrcpyState.Streaming(VideoSize(1920, 1080))),
        {}, {}, {}, {}, {}, {}, {}, {}, {}, { _, _, _, _, _ -> }, {}, {})
    }

    val root = composeTestRule.onRoot()
    val screen = root.fetchSemanticsNode().boundsInRoot
    val power = composeTestRule.onNodeWithContentDescription(text(R.string.power))

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

  @Test
  fun shortAreasShrinkTheRemoteInsteadOfOverflowing() {
    composeTestRule.setContent {
      AScrcpyTheme {
        // Landscape full screen leaves roughly 280dp of height on a phone, less than the panel needs.
        Box(Modifier.size(760.dp, 320.dp).testTag(AREA)) {
          MainScreen(MainUiState(connectionState = connectedState(), scrcpyState = ScrcpyState.Streaming(VideoSize(1920, 1080))),
            {}, {}, {}, {}, {}, {}, {}, {}, {}, { _, _, _, _, _ -> }, {}, {})
        }
      }
    }

    val root = composeTestRule.onRoot()
    val area = composeTestRule.onNodeWithTag(AREA).fetchSemanticsNode().boundsInRoot
    composeTestRule.onNodeWithContentDescription(text(R.string.floating_remote_control)).performClick()

    // The same portrait column is used at every scale, so every key stays reachable.
    remoteKeys.forEach { composeTestRule.onNodeWithContentDescription(text(it)).assertExists() }

    // A panel that overflowed the area would be glued to the top edge with no vertical drag range.
    val up = composeTestRule.onNodeWithContentDescription(text(R.string.direction_up)).fetchSemanticsNode().boundsInRoot
    val menu = composeTestRule.onNodeWithContentDescription(text(R.string.menu)).fetchSemanticsNode().boundsInRoot
    assertTrue("panel overflowed the area: up=$up menu=$menu area=$area",
      up.top >= area.top && up.bottom <= area.bottom && menu.bottom <= area.bottom)

    root.performTouchInput {
      swipe(start = menu.center, end = Offset(menu.center.x, menu.center.y + 150f), durationMillis = 400)
    }
    val after = composeTestRule.onNodeWithContentDescription(text(R.string.menu)).fetchSemanticsNode().boundsInRoot
    assertTrue("panel did not move vertically: $menu -> $after", after.top > menu.top)
  }

  private companion object {
    const val AREA = "short-area"
    val remoteKeys = listOf(
      R.string.power, R.string.volume_down, R.string.volume_up, R.string.back, R.string.home, R.string.menu,
      R.string.direction_up, R.string.direction_down, R.string.direction_left, R.string.direction_right,
      R.string.confirm,
    )
  }
}
