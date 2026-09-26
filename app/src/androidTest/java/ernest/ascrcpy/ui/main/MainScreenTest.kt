package ernest.ascrcpy.ui.main

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import ernest.ascrcpy.R
import ernest.ascrcpy.adb.AdbConnectionState
import ernest.ascrcpy.adb.AdbDevice
import ernest.ascrcpy.adb.AdbEndpoint
import ernest.ascrcpy.scrcpy.ScrcpyState
import ernest.ascrcpy.scrcpy.VideoSize
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
}
