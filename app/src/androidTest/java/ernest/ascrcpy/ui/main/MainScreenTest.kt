package ernest.ascrcpy.ui.main

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import ernest.ascrcpy.adb.AdbConnectionState
import org.junit.Rule
import org.junit.Test

class MainScreenTest {
  @get:Rule val composeTestRule = createAndroidComposeRule<ComponentActivity>()

  @Test
  fun disconnectedDeviceShowsConnectAction() {
    composeTestRule.setContent {
      MainScreen(MainUiState(connectionState = AdbConnectionState.Disconnected), {}, {}, {}, {}, {}, {}, {}, {},
        { _, _, _, _, _ -> })
    }
    composeTestRule.onNodeWithText("Connect").assertExists()
  }
}
