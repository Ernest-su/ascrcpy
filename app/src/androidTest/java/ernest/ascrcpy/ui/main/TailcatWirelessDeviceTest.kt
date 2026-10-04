package ernest.ascrcpy.ui.main

import android.util.Log
import android.view.KeyEvent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import ernest.ascrcpy.MainActivity
import ernest.ascrcpy.R
import ernest.ascrcpy.adb.AdbConnectionState
import ernest.ascrcpy.scrcpy.ScrcpyState
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import org.json.JSONObject
import java.io.File
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

/** Opt-in two-device test. Provision the private files/tailcat-device-check.json before running.
 * Credentials are read only from this file and never included in test assertions or logs. */
class TailcatWirelessDeviceTest {
  @get:Rule val rule = createAndroidComposeRule<MainActivity>()

  @Test fun pairsConnectsStreamsControlsAndReconnectsThroughTailcat(): Unit = runBlocking {
    val input = File(rule.activity.filesDir, "tailcat-device-check.json")
    assumeTrue("Provision a live target's private pairing configuration to run this test", input.isFile)
    val settings = JSONObject(input.readText())
    input.delete()
    val viewModel = ViewModelProvider(rule.activity)[MainViewModel::class.java]
    val observing = launch(Dispatchers.Main) { viewModel.uiState.collect { state ->
      val failure = (state.scrcpyState as? ScrcpyState.Failed)?.cause
      Log.i("TailcatDeviceTest", "state adb=${state.connectionState.javaClass.simpleName} busy=${state.busy} scrcpy=${state.scrcpyState.javaClass.simpleName} cause=${failure?.javaClass?.simpleName}")
    } }
    suspend fun awaitState(predicate: (MainUiState) -> Boolean) {
      // Drive Compose's test clock while real network I/O completes, so StateFlow collectors
      // and Surface creation callbacks can run rather than freezing the UI after performClick.
      rule.waitUntil(90_000) { predicate(viewModel.uiState.value) }
    }
    try {
      val context = rule.activity
      rule.onNodeWithText(context.getString(R.string.method_tailcat_wireless)).performScrollTo().performClick()
      fun enter(label: Int, value: String) {
        rule.onNode(hasSetTextAction() and hasText(context.getString(label))).performScrollTo().performTextReplacement(value)
      }
      enter(R.string.tailcat_address, settings.getString("address"))
      enter(R.string.pairing_port, settings.getInt("pairing_port").toString())
      enter(R.string.wireless_connection_port, settings.getInt("connect_port").toString())
      enter(R.string.pairing_code, settings.getString("pairing_code"))
      rule.onNodeWithText(context.getString(R.string.tailcat_pair_connect)).performScrollTo().performClick()
      awaitState { it.connected && it.scrcpyState is ScrcpyState.Streaming && !it.busy }
      assertEquals("", viewModel.uiState.value.pairingCode)
      val size = (viewModel.uiState.value.scrcpyState as ScrcpyState.Streaming).videoSize
      assertTrue(size.width > 0 && size.height > size.width)
      Log.i("TailcatDeviceTest", "Streaming dimensions=${size.width}x${size.height}")
      val api = withTimeout(15_000) { viewModel.deviceClient.shell("getprop ro.build.version.sdk").text().trim() }
      assertEquals("35", api)
      Log.i("TailcatDeviceTest", "shell API35 verified")
      val servers = viewModel.deviceClient.shell("ps -A -o ARGS | grep '[c]om.genymobile.scrcpy.Server'").text()
      assertTrue("scrcpy server must be running", servers.contains("com.genymobile.scrcpy.Server"))
      withContext(Dispatchers.Main) { viewModel.injectKey(KeyEvent.KEYCODE_HOME) }
      rule.waitForIdle()
      withTimeout(10_000) {
        while (!viewModel.deviceClient.shell("dumpsys activity activities | grep -E 'topResumedActivity|mResumedActivity'")
            .text().contains("launcher")) delay(200)
      }
      Log.i("TailcatDeviceTest", "HOME control verified on target")
      withContext(Dispatchers.Main) { viewModel.stopMirroring() }
      awaitState { it.scrcpyState is ScrcpyState.Idle }
      withTimeout(10_000) {
        while (viewModel.deviceClient.shell("ps -A -o ARGS | grep '[c]om.genymobile.scrcpy.Server'")
            .text().contains("com.genymobile.scrcpy.Server")) delay(200)
      }
      Log.i("TailcatDeviceTest", "server exited after stop")
      withContext(Dispatchers.Main) { viewModel.disconnect() }
      awaitState { !it.connected && !it.busy }
      withContext(Dispatchers.Main) { viewModel.connect() }
      awaitState { it.connected && it.scrcpyState is ScrcpyState.Streaming && !it.busy }
      Log.i("TailcatDeviceTest", "paired identity reconnected without code")
    } finally {
      withContext(Dispatchers.Main) { viewModel.disconnect() }
      awaitState { !it.connected && !it.busy }
      observing.cancelAndJoin()
    }
    Log.i("TailcatDeviceTest", "PASS two-device wireless session")
  }
}
