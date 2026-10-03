package ernest.ascrcpy.ui.device

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import ernest.ascrcpy.R
import ernest.ascrcpy.adb.AdbChannel
import ernest.ascrcpy.adb.AdbClient
import ernest.ascrcpy.adb.AdbCommandResult
import ernest.ascrcpy.adb.AdbConnectionState
import ernest.ascrcpy.adb.AdbDevice
import ernest.ascrcpy.adb.AdbEndpoint
import ernest.ascrcpy.theme.AScrcpyTheme
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class DeviceScreenTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test fun appActionsRequireConfirmationAndReportSuccess() {
        val client = FakeClient()
        rule.setContent { AScrcpyTheme { DeviceScreen(client, {}, {}) } }
        rule.onNodeWithText(rule.activity.getString(R.string.tab_apps)).performClick()
        rule.waitUntil(10_000) { rule.onAllNodesWithText("com.example.notes").fetchSemanticsNodes().isNotEmpty() }

        rule.onNodeWithText(rule.activity.getString(R.string.clear_app_data)).performClick()
        rule.onNodeWithText(rule.activity.getString(R.string.clear_app_confirm_title)).assertExists()
        rule.runOnIdle { assertEquals(0, client.actions.size) }
        val clearButtons = rule.onAllNodesWithText(rule.activity.getString(R.string.clear_app_data))
        clearButtons[clearButtons.fetchSemanticsNodes().lastIndex].performClick()
        rule.waitUntil(10_000) { client.actions.size == 1 }
        rule.runOnIdle { assertEquals("pm clear --user 0 'com.example.notes'", client.actions.single()) }

        rule.onNodeWithText(rule.activity.getString(R.string.disable_app)).performClick()
        rule.onNodeWithText(rule.activity.getString(R.string.disable_app_confirm_title)).assertExists()
        rule.runOnIdle { assertEquals(1, client.actions.size) }
        val disableButtons = rule.onAllNodesWithText(rule.activity.getString(R.string.disable_app))
        disableButtons[disableButtons.fetchSemanticsNodes().lastIndex].performClick()
        rule.waitUntil(10_000) { client.actions.size == 2 }
        rule.onNodeWithText(rule.activity.getString(R.string.app_disabled)).assertExists()
        rule.runOnIdle { assertEquals("pm disable-user --user 0 'com.example.notes'", client.actions.last()) }

        rule.onNodeWithText(rule.activity.getString(R.string.enable_app)).performClick()
        rule.onNodeWithText(rule.activity.getString(R.string.enable_app_confirm_title)).assertExists()
        val enableButtons = rule.onAllNodesWithText(rule.activity.getString(R.string.enable_app))
        enableButtons[enableButtons.fetchSemanticsNodes().lastIndex].performClick()
        rule.waitUntil(10_000) { client.actions.size == 3 }
        rule.runOnIdle { assertEquals("pm enable --user 0 'com.example.notes'", client.actions.last()) }

        rule.onNodeWithText(rule.activity.getString(R.string.uninstall_app)).performClick()
        rule.onNodeWithText(rule.activity.getString(R.string.uninstall_app_confirm_title)).assertExists()
        val uninstallButtons = rule.onAllNodesWithText(rule.activity.getString(R.string.uninstall_app))
        uninstallButtons[uninstallButtons.fetchSemanticsNodes().lastIndex].performClick()
        rule.waitUntil(10_000) { client.actions.size == 4 }
        rule.runOnIdle { assertEquals("pm uninstall --user 0 'com.example.notes'", client.actions.last()) }
    }

    private class FakeClient : AdbClient {
        override val state = MutableStateFlow<AdbConnectionState>(
            AdbConnectionState.Connected(AdbDevice(AdbEndpoint("192.168.1.20"), "", emptyMap())))
        val actions = CopyOnWriteArrayList<String>()

        override suspend fun connect(endpoint: AdbEndpoint): AdbDevice = error("unused")
        override suspend fun disconnect() = Unit
        override suspend fun shell(command: String): AdbCommandResult {
            val output = when (command) {
                "pm list packages -f --user 0" -> "package:/data/app/notes/base.apk=com.example.notes\n"
                "pm list packages -d --user 0" -> ""
                "am get-current-user" -> "0\n"
                else -> { actions += command; when {
                    command.startsWith("pm disable-user") -> "Package com.example.notes new state: disabled-user\n"
                    command.startsWith("pm enable") -> "Package com.example.notes new state: enabled\n"
                    else -> "Success\n"
                } }
            }
            return AdbCommandResult(output.toByteArray(), 0)
        }
        override suspend fun push(source: InputStream, remotePath: String, mode: Int) = error("unused")
        override suspend fun pull(remotePath: String, destination: OutputStream) = error("unused")
        override suspend fun open(service: String): AdbChannel = error("unused")
        override fun close() = Unit
    }
}
