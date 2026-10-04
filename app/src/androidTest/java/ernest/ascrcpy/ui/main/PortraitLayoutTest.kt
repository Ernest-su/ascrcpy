package ernest.ascrcpy.ui.main

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import android.content.res.Configuration
import java.util.Locale
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import ernest.ascrcpy.R
import ernest.ascrcpy.adb.AdbConnectionState
import ernest.ascrcpy.adb.AdbDevice
import ernest.ascrcpy.adb.AdbEndpoint
import ernest.ascrcpy.theme.AScrcpyTheme
import android.graphics.Bitmap
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test

class PortraitLayoutTest {
  @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

  @Test fun connectedActionsRemainSingleLineAndDoNotOverlap() {
    val dark = mutableStateOf(false)
    val language = mutableStateOf("en")
    fun localizedContext() = rule.activity.createConfigurationContext(Configuration(rule.activity.resources.configuration).apply { setLocale(Locale.forLanguageTag(language.value)) })
    rule.setContent {
      CompositionLocalProvider(LocalContext provides localizedContext()) {
        AScrcpyTheme(darkTheme = dark.value) {
          MainScreen(MainUiState(connectionState = AdbConnectionState.Connected(
            AdbDevice(AdbEndpoint("192.168.1.20"), "", emptyMap()))),
            {}, {}, {}, {}, {}, {}, {}, {}, {}, { _, _, _, _, _ -> }, {}, {})
        }
      }
    }
    rule.onNodeWithContentDescription(localizedContext().getString(R.string.floating_remote_control)).performClick()
    rule.onNodeWithContentDescription(localizedContext().getString(R.string.toggle_fullscreen)).performClick()
    for (locale in listOf("en", "zh")) for (night in listOf(false, true)) {
      rule.runOnIdle { dark.value = night; language.value = locale }
      val captions = listOf(R.string.device_management, R.string.start_mirroring, R.string.disconnect)
      captions.forEach { id ->
        val caption = rule.onNodeWithText(localizedContext().getString(id), useUnmergedTree = true)
        caption.performScrollTo()
        val results = mutableListOf<TextLayoutResult>()
        caption.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
        assertEquals("Button must stay single line", 1, results.single().lineCount)
        assertFalse("Button label clipped: ${localizedContext().getString(id)}, locale=$locale, size=${results.single().size}, textWidth=${results.single().getLineRight(0)}", results.single().hasVisualOverflow)
      }
      // All actions fit in the same viewport after scrolling the group into view.
      val finalBounds = captions.map { rule.onNodeWithText(localizedContext().getString(it)).fetchSemanticsNode().boundsInRoot }
      for (i in finalBounds.indices) for (j in i + 1 until finalBounds.size) {
        assertFalse("Actions overlap: $finalBounds", finalBounds[i].overlaps(finalBounds[j]))
      }
      val file = File(rule.activity.getExternalFilesDir(null), "portrait-$locale-${if (night) "dark" else "light"}.png")
      file.outputStream().use {
        rule.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
      }
    }
  }

  @Test fun connectionFormsKeepActionLabelsReadable() {
    val state = mutableStateOf(MainUiState())
    val dark = mutableStateOf(false)
    rule.setContent {
      AScrcpyTheme(darkTheme = dark.value) {
        MainScreen(state.value, {}, {}, {}, {}, {}, {}, {}, {}, {}, { _, _, _, _, _ -> }, {}, {})
      }
    }
    val cases = listOf(
      MainUiState(method = ConnectionMethod.TCP) to R.string.connect,
      MainUiState(method = ConnectionMethod.TAILCAT) to R.string.connect,
      MainUiState(method = ConnectionMethod.TAILCAT_WIRELESS) to R.string.tailcat_pair_connect,
      MainUiState(method = ConnectionMethod.TAILCAT_WIRELESS) to R.string.tailcat_connect_paired,
      MainUiState(method = ConnectionMethod.WIRELESS_CODE) to R.string.pair_wireless,
      MainUiState(method = ConnectionMethod.WIRELESS_CODE, wirelessCodePaired = true) to R.string.connect_saved_wireless_device,
      MainUiState(method = ConnectionMethod.WIRELESS_QR) to R.string.pair_qr,
      MainUiState(method = ConnectionMethod.USB) to R.string.connect_usb,
    )
    for ((index, case) in cases.withIndex()) for (night in listOf(false, true)) {
      rule.runOnIdle { state.value = case.first; dark.value = night }
      val action = rule.onNodeWithText(rule.activity.getString(case.second), useUnmergedTree = true)
      action.performScrollTo()
      val layouts = mutableListOf<TextLayoutResult>()
      action.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
      assertEquals(1, layouts.single().lineCount)
      assertFalse("Clipped form action: ${layouts.single().layoutInput.text}, size=${layouts.single().size}, right=${layouts.single().getLineRight(0)}, height=${layouts.single().didOverflowHeight}", layouts.single().hasVisualOverflow)
      val file = File(rule.activity.getExternalFilesDir(null), "form-$index-${if (night) "dark" else "light"}.png")
      file.outputStream().use {
        rule.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
      }
    }
  }

}
