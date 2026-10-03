package ernest.ascrcpy.ui.main

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.annotation.StringRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.Image
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ernest.ascrcpy.R
import ernest.ascrcpy.adb.AdbConnectionState
import ernest.ascrcpy.scrcpy.ScrcpyState
import ernest.ascrcpy.scrcpy.VideoSize
import ernest.ascrcpy.theme.AScrcpyTheme
import ernest.ascrcpy.theme.WeChatBrand
import ernest.ascrcpy.theme.WeChatDanger
import ernest.ascrcpy.theme.WeChatOverlayStrong
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import android.graphics.Bitmap
import kotlin.math.abs
import kotlin.math.roundToInt

@Composable
fun MainScreen(viewModel: MainViewModel, modifier: Modifier = Modifier) {
  val state by viewModel.uiState.collectAsStateWithLifecycle()
  val context = LocalContext.current
  val usbManager = remember(context) { context.getSystemService(Context.USB_SERVICE) as UsbManager }
  val usbAction = remember(context) { "${context.packageName}.USB_PERMISSION" }
  DisposableEffect(context) {
    val receiver = object : BroadcastReceiver() {
      override fun onReceive(receivedContext: Context, intent: Intent) {
        if (intent.action != usbAction) return
        @Suppress("DEPRECATION")
        val device = intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
        if (device != null && intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false))
          viewModel.connectUsb(device)
        else viewModel.reportUsbError(context.getString(R.string.log_usb_denied))
      }
    }
    if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(receiver, IntentFilter(usbAction), Context.RECEIVER_NOT_EXPORTED)
    else { @Suppress("DEPRECATION") context.registerReceiver(receiver, IntentFilter(usbAction)) }
    onDispose { context.unregisterReceiver(receiver) }
  }
  val connectUsb = {
    val devices = viewModel.usbDevices()
    if (devices.isEmpty()) viewModel.reportUsbError(context.getString(R.string.log_usb_missing))
    else {
      val device = devices.first()
      if (viewModel.hasUsbPermission(device)) viewModel.connectUsb(device)
      else usbManager.requestPermission(device, PendingIntent.getBroadcast(context, 0,
        Intent(usbAction).setPackage(context.packageName),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE))
    }
  }
  MainScreen(state, viewModel::setHost, viewModel::setPort, viewModel::connect,
    viewModel::disconnect, viewModel::probe, viewModel::startMirroring,
    viewModel::stopMirroring, viewModel::attachSurface, viewModel::detachSurface, viewModel::injectTouch,
    viewModel::injectKey, viewModel::deleteHost, modifier,
    viewModel::setMethod, viewModel::setPairingHost, viewModel::setPairingPort,
    viewModel::setPairingCode, viewModel::pairWithCode, viewModel::startQrPairing,
    viewModel::stopQrPairing, connectUsb, viewModel::setTailcatAddress, viewModel::prepareWirelessPairing,
    viewModel::reconnectSavedWirelessDevice, viewModel::selectSavedWirelessDevice)
}

@Composable
internal fun MainScreen(
  state: MainUiState,
  onHostChange: (String) -> Unit,
  onPortChange: (String) -> Unit,
  onConnect: () -> Unit,
  onDisconnect: () -> Unit,
  onProbe: () -> Unit,
  onStartMirroring: () -> Unit,
  onStopMirroring: () -> Unit,
  onSurfaceCreated: (Surface) -> Unit,
  onSurfaceDestroyed: (Surface) -> Unit,
  onTouch: (Int, Long, Int, Int, Float) -> Unit,
  onKey: (Int) -> Unit,
  onDeleteHost: (String) -> Unit,
  modifier: Modifier = Modifier,
  onMethodChange: (ConnectionMethod) -> Unit = {},
  onPairingHostChange: (String) -> Unit = {},
  onPairingPortChange: (String) -> Unit = {},
  onPairingCodeChange: (String) -> Unit = {},
  onPair: () -> Unit = {},
  onStartQr: () -> Unit = {},
  onStopQr: () -> Unit = {},
  onConnectUsb: () -> Unit = {},
  onTailcatAddressChange: (String) -> Unit = {},
  onPrepareWirelessPairing: () -> Unit = {},
  onReconnectSavedWireless: () -> Unit = {},
  onSelectSavedWireless: (String) -> Unit = {},
) {
  var fullscreen by remember(state.connected) { mutableStateOf(state.connected) }
  val remoteState = rememberSaveable(saver = FloatingRemoteStateSaver) { FloatingRemoteState() }
  ImmersiveMode(state.connected && fullscreen)
  val videoSize = (state.scrcpyState as? ScrcpyState.Streaming)?.videoSize

  if (state.connected && fullscreen) {
    Box(modifier.fillMaxSize().background(Color.Black)) {
      AspectRatioRemoteSurface(videoSize, onSurfaceCreated, onSurfaceDestroyed, onTouch, Modifier.fillMaxSize())
      FloatingRemote(onKey, { fullscreen = false }, remoteState, Modifier.fillMaxSize().safeDrawingPadding())
    }
  } else {
    NormalScreen(state, videoSize, onHostChange, onPortChange, onConnect, onDisconnect,
      onProbe, onStartMirroring, onStopMirroring, onSurfaceCreated, onSurfaceDestroyed, onTouch, onDeleteHost, modifier,
      onMethodChange, onPairingHostChange, onPairingPortChange, onPairingCodeChange, onPair, onStartQr, onConnectUsb,
      onTailcatAddressChange, onPrepareWirelessPairing, onReconnectSavedWireless, onSelectSavedWireless)
    if (state.connected) {
      FloatingRemote(onKey, { fullscreen = true }, remoteState, Modifier.fillMaxSize().safeDrawingPadding())
    }
  }
  val payload = state.qrPayload
  if (payload != null) {
    val bitmap = remember(payload) {
      val size = 640
      val bits = QRCodeWriter().encode(payload, BarcodeFormat.QR_CODE, size, size)
      val pixels = IntArray(size * size) { index -> if (bits[index % size, index / size]) android.graphics.Color.BLACK else android.graphics.Color.WHITE }
      Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888).asImageBitmap()
    }
    AlertDialog(onDismissRequest = onStopQr, title = { Text(stringResource(R.string.qr_title)) },
      text = { Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(stringResource(R.string.qr_description))
        Image(bitmap, contentDescription = stringResource(R.string.qr_image_description), modifier = Modifier.fillMaxWidth())
      } },
      confirmButton = { TextButton(onClick = onStopQr) { Text(stringResource(R.string.stop)) } })
  }
}

@Composable
private fun NormalScreen(
  state: MainUiState,
  videoSize: VideoSize?,
  onHostChange: (String) -> Unit,
  onPortChange: (String) -> Unit,
  onConnect: () -> Unit,
  onDisconnect: () -> Unit,
  onProbe: () -> Unit,
  onStartMirroring: () -> Unit,
  onStopMirroring: () -> Unit,
  onSurfaceCreated: (Surface) -> Unit,
  onSurfaceDestroyed: (Surface) -> Unit,
  onTouch: (Int, Long, Int, Int, Float) -> Unit,
  onDeleteHost: (String) -> Unit,
  modifier: Modifier,
  onMethodChange: (ConnectionMethod) -> Unit,
  onPairingHostChange: (String) -> Unit,
  onPairingPortChange: (String) -> Unit,
  onPairingCodeChange: (String) -> Unit,
  onPair: () -> Unit,
  onStartQr: () -> Unit,
  onConnectUsb: () -> Unit,
  onTailcatAddressChange: (String) -> Unit,
  onPrepareWirelessPairing: () -> Unit,
  onReconnectSavedWireless: () -> Unit,
  onSelectSavedWireless: (String) -> Unit,
) {
  Scaffold(modifier.fillMaxSize()) { padding ->
    Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())
      .padding(horizontal = 16.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
      Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
        Text(stringResource(R.string.app_tagline), color = MaterialTheme.colorScheme.onSurfaceVariant)
      }
      Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
          containerColor = MaterialTheme.colorScheme.primaryContainer,
          contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ),
      ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
          Text(stringResource(R.string.preview_controls_title), style = MaterialTheme.typography.titleMedium)
          Text(stringResource(R.string.preview_controls_description), style = MaterialTheme.typography.bodyMedium)
        }
      }
      Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
          Text(stringResource(R.string.adb_device), style = MaterialTheme.typography.titleLarge)
          if (!state.connected) {
            ConnectionMethod.entries.forEach { method ->
              Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()
                .clickable(enabled = !state.busy) { onMethodChange(method) }) {
                RadioButton(selected = state.method == method, onClick = { onMethodChange(method) }, enabled = !state.busy)
                Text(stringResource(when (method) {
                  ConnectionMethod.TCP -> R.string.method_tcp
                  ConnectionMethod.TAILCAT -> R.string.method_tailcat
                  ConnectionMethod.WIRELESS_CODE -> R.string.method_wireless_code
                  ConnectionMethod.WIRELESS_QR -> R.string.method_wireless_qr
                  ConnectionMethod.USB -> R.string.method_usb
                }))
              }
            }
          }
          if (state.method == ConnectionMethod.TAILCAT) {
            OutlinedTextField(state.tailcatAddress, onTailcatAddressChange, Modifier.fillMaxWidth(),
              label = { Text(stringResource(R.string.tailcat_address)) },
              singleLine = true, enabled = !state.busy && !state.connected)
            Text(stringResource(R.string.tailcat_description), color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedTextField(state.port, onPortChange, Modifier.fillMaxWidth(),
              label = { Text(stringResource(R.string.tailcat_remote_port)) },
              singleLine = true, enabled = !state.busy && !state.connected)
          }
          if (state.method == ConnectionMethod.TCP && !state.connected) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
              HostHistoryField(state, onHostChange, onDeleteHost, Modifier.weight(1f))
              Spacer(Modifier.width(12.dp))
              OutlinedTextField(state.port, onPortChange, Modifier.width(112.dp), label = { Text(stringResource(R.string.port)) },
                singleLine = true, enabled = !state.busy && !state.connected)
            }
          }
          if (state.method == ConnectionMethod.WIRELESS_CODE && !state.connected &&
            state.savedWirelessDevices.isNotEmpty()) {
            Text(stringResource(R.string.saved_wireless_devices), style = MaterialTheme.typography.titleMedium)
            state.savedWirelessDevices.forEach { device ->
              Row(Modifier.fillMaxWidth().clickable(enabled = !state.busy) {
                onSelectSavedWireless(device.guid)
              }, verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = state.selectedWirelessGuid == device.guid,
                  onClick = { onSelectSavedWireless(device.guid) }, enabled = !state.busy)
                Column(Modifier.weight(1f)) {
                  Text(device.name)
                  Text(device.routeHost, style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
              }
            }
          }
          if (state.method == ConnectionMethod.WIRELESS_CODE && !state.connected && !state.wirelessCodePaired) {
            Text(stringResource(R.string.pairing_description), color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedTextField(state.pairingHost, onPairingHostChange, Modifier.fillMaxWidth(),
              label = { Text(stringResource(R.string.pairing_host)) }, singleLine = true, enabled = !state.busy)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
              OutlinedTextField(state.pairingPort, onPairingPortChange, Modifier.weight(1f),
                label = { Text(stringResource(R.string.pairing_port)) }, singleLine = true, enabled = !state.busy)
              OutlinedTextField(state.pairingCode, onPairingCodeChange, Modifier.weight(1f),
                label = { Text(stringResource(R.string.pairing_code)) }, singleLine = true, enabled = !state.busy)
            }
            OutlinedButton(onPair, enabled = !state.busy) { Text(stringResource(R.string.pair_wireless)) }
          }
          if (state.method == ConnectionMethod.WIRELESS_CODE && !state.connected && state.wirelessCodePaired) {
            Text(stringResource(R.string.wireless_connection_description, state.pairingHost),
              color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedTextField(state.port, onPortChange, Modifier.fillMaxWidth(),
              label = { Text(stringResource(R.string.wireless_connection_port)) },
              singleLine = true, enabled = !state.busy)
            OutlinedButton(onClick = onReconnectSavedWireless, enabled = !state.busy) {
              Text(stringResource(R.string.connect_saved_wireless_device))
            }
            TextButton(onClick = onPrepareWirelessPairing, enabled = !state.busy) {
              Text(stringResource(R.string.pair_different_wireless_device))
            }
          }
          if (state.method == ConnectionMethod.WIRELESS_QR && !state.connected) {
            Text(stringResource(R.string.qr_description), color = MaterialTheme.colorScheme.onSurfaceVariant)
            Button(onStartQr, enabled = !state.busy) { Text(stringResource(R.string.pair_qr)) }
          }
          if (state.method == ConnectionMethod.USB && !state.connected) {
            Text(stringResource(R.string.usb_description), color = MaterialTheme.colorScheme.onSurfaceVariant)
            Button(onConnectUsb, enabled = !state.busy) { Text(stringResource(R.string.connect_usb)) }
          }
          StatusPill(state.connectionState)
          Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (state.connected) {
              Button(onProbe, enabled = !state.busy) { Text(stringResource(R.string.test_shell)) }
              Button(onStartMirroring, enabled = !state.busy && state.scrcpyState !is ScrcpyState.Streaming) { Text(stringResource(R.string.start_mirroring)) }
              OutlinedButton(onDisconnect, enabled = !state.busy) { Text(stringResource(R.string.disconnect)) }
            } else {
              if (state.method == ConnectionMethod.TCP || state.method == ConnectionMethod.TAILCAT ||
                (state.method == ConnectionMethod.WIRELESS_CODE && state.wirelessCodePaired))
              Button(onConnect, enabled = !state.busy &&
                (if (state.method == ConnectionMethod.TAILCAT) state.tailcatAddress.isNotBlank() else state.host.isNotBlank())) {
                if (state.busy) { CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp); Spacer(Modifier.width(8.dp)) }
                Text(stringResource(if (state.busy) R.string.connecting else R.string.connect))
              }
            }
          }
        }
      }
      if (state.connected) {
        Card(Modifier.fillMaxWidth()) {
          Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
              Text(stringResource(R.string.remote_display), style = MaterialTheme.typography.titleLarge)
              if (state.scrcpyState !is ScrcpyState.Idle) OutlinedButton(onStopMirroring) { Text(stringResource(R.string.stop)) }
            }
            AspectRatioRemoteSurface(videoSize, onSurfaceCreated, onSurfaceDestroyed, onTouch, Modifier.fillMaxWidth().height(420.dp))
            Text(scrcpyStatus(state.scrcpyState), color = MaterialTheme.colorScheme.onSurfaceVariant)
          }
        }
      }
      if (state.console.isNotBlank()) {
        Card(Modifier.fillMaxWidth()) {
          Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.session_log), style = MaterialTheme.typography.titleMedium)
            Text(state.console, fontFamily = FontFamily.Monospace)
          }
        }
      }
    }
  }
}

@Composable
private fun HostHistoryField(
  state: MainUiState,
  onHostChange: (String) -> Unit,
  onDeleteHost: (String) -> Unit,
  modifier: Modifier = Modifier,
) {
  var focused by remember { mutableStateOf(false) }
  var dismissed by remember { mutableStateOf(false) }
  val candidates = state.hostHistory.filter { state.host.isBlank() || it.contains(state.host, ignoreCase = true) }
  Box(modifier) {
    OutlinedTextField(
      value = state.host,
      onValueChange = { dismissed = false; onHostChange(it) },
      modifier = Modifier.fillMaxWidth().onFocusChanged {
        focused = it.isFocused
        if (it.isFocused) dismissed = false
      },
      label = { Text(stringResource(R.string.ip_address_or_host)) },
      singleLine = true,
      enabled = !state.busy && !state.connected,
    )
    if (focused && !dismissed && candidates.isNotEmpty() && !state.connected) {
      HostSuggestions(candidates, onSelect = { onHostChange(it); dismissed = true },
        onDeleteHost = onDeleteHost, onDismissRequest = { dismissed = true })
    }
  }
}

/**
 * Host history suggestions anchored under the host field.
 *
 * The menu lives in a deliberately non-focusable popup. A focusable popup takes window focus away
 * from the activity, so the host field loses focus and the IME is dismissed every time the filtered
 * suggestion list re-renders while the user is typing or deleting characters. The list still closes
 * when the field loses focus or when a suggestion is picked.
 */
@Composable
private fun HostSuggestions(
  candidates: List<String>,
  onSelect: (String) -> Unit,
  onDeleteHost: (String) -> Unit,
  onDismissRequest: () -> Unit,
) {
  val gap = with(LocalDensity.current) { 4.dp.roundToPx() }
  Popup(
    onDismissRequest = onDismissRequest,
    popupPositionProvider = remember(gap) { BelowAnchorPositionProvider(gap) },
    properties = PopupProperties(focusable = false, dismissOnBackPress = false, dismissOnClickOutside = true),
  ) {
    Surface(shape = RoundedCornerShape(12.dp), tonalElevation = 3.dp, shadowElevation = 3.dp,
      modifier = Modifier.widthIn(min = 280.dp)) {
      Column(Modifier.padding(vertical = 4.dp)) {
        candidates.forEach { host ->
          val deleteDescription = stringResource(R.string.delete_host, host)
          DropdownMenuItem(
            text = { Text(host) },
            onClick = { onSelect(host) },
            trailingIcon = {
              IconButton(
                onClick = { onDeleteHost(host) },
                modifier = Modifier.size(40.dp).semantics { contentDescription = deleteDescription },
              ) {
                DeleteIcon()
              }
            },
          )
        }
      }
    }
  }
}

/** Places the suggestion popup directly below its anchor and keeps it inside the window. */
private class BelowAnchorPositionProvider(private val gap: Int) : PopupPositionProvider {
  override fun calculatePosition(
    anchorBounds: IntRect,
    windowSize: IntSize,
    layoutDirection: LayoutDirection,
    popupContentSize: IntSize,
  ): IntOffset {
    val maxX = (windowSize.width - popupContentSize.width).coerceAtLeast(0)
    val maxY = (windowSize.height - popupContentSize.height).coerceAtLeast(0)
    return IntOffset(anchorBounds.left.coerceIn(0, maxX), (anchorBounds.bottom + gap).coerceIn(0, maxY))
  }
}

@Composable
private fun DeleteIcon() {
  val color = MaterialTheme.colorScheme.onSurfaceVariant
  Canvas(Modifier.size(20.dp)) {
    val stroke = size.minDimension * .09f
    drawLine(color, Offset(size.width * .32f, size.height * .35f), Offset(size.width * .38f, size.height * .84f), stroke, StrokeCap.Round)
    drawLine(color, Offset(size.width * .68f, size.height * .35f), Offset(size.width * .62f, size.height * .84f), stroke, StrokeCap.Round)
    drawLine(color, Offset(size.width * .27f, size.height * .31f), Offset(size.width * .73f, size.height * .31f), stroke, StrokeCap.Round)
    drawLine(color, Offset(size.width * .4f, size.height * .2f), Offset(size.width * .6f, size.height * .2f), stroke, StrokeCap.Round)
    drawLine(color, Offset(size.width * .37f, size.height * .84f), Offset(size.width * .63f, size.height * .84f), stroke, StrokeCap.Round)
  }
}

@Composable
private fun ImmersiveMode(enabled: Boolean) {
  val activity = LocalContext.current.findActivity() ?: return
  DisposableEffect(activity, enabled) {
    WindowCompat.setDecorFitsSystemWindows(activity.window, !enabled)
    val controller = WindowCompat.getInsetsController(activity.window, activity.window.decorView)
    if (enabled) {
      controller.hide(WindowInsetsCompat.Type.systemBars())
      controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    } else controller.show(WindowInsetsCompat.Type.systemBars())
    onDispose {
      if (enabled) {
        WindowCompat.setDecorFitsSystemWindows(activity.window, true)
        controller.show(WindowInsetsCompat.Type.systemBars())
      }
    }
  }
}

private fun Context.findActivity(): Activity? = when (this) {
  is Activity -> this
  is ContextWrapper -> baseContext.findActivity()
  else -> null
}

@Composable
private fun FloatingRemote(
  onKey: (Int) -> Unit,
  onToggleFullscreen: () -> Unit,
  state: FloatingRemoteState,
  modifier: Modifier = Modifier,
) {
  BoxWithConstraints(modifier) {
    val density = LocalDensity.current
    val areaWidth = with(density) { maxWidth.toPx() }
    val areaHeight = with(density) { maxHeight.toPx() }
    val icon = RemoteBounds(areaWidth, areaHeight,
      with(density) { RemoteLayout.IconSize.toPx() }, with(density) { RemoteLayout.IconSize.toPx() })
    val panelHeightPx = remotePanelHeight(density)
    val scale = remoteScale(areaHeight, panelHeightPx)
    val metrics = remember(scale) { RemoteLayout.metrics(scale) }
    val panel = RemoteBounds(areaWidth, areaHeight,
      with(density) { metrics.width.toPx() }, with(density) { metrics.height.toPx() })
    val bounds = if (state.collapsed) icon else panel
    val edgeThreshold = with(density) { RemoteLayout.EdgeThreshold.toPx() }

    // The remote is remembered by the point at its centre, so growing or shrinking the panel never
    // teleports it to a screen edge; an untouched remote still docks to the right-hand side.
    val restingCenter = if (state.center.x.isNaN()) Offset(icon.maxX + icon.itemWidth / 2f, areaHeight / 2f)
      else state.center
    var dragged by remember { mutableStateOf<Offset?>(null) }
    var dragOrigin by remember { mutableStateOf(Offset.Zero) }
    val actual = dragged ?: bounds.topLeft(restingCenter)

    // The drag gesture outlives size changes, so it reads the live geometry instead of capturing
    // the values that were current when the gesture started.
    val liveTopLeft by rememberUpdatedState(actual)
    val liveBounds by rememberUpdatedState(bounds)
    val liveIcon by rememberUpdatedState(icon)
    val livePanel by rememberUpdatedState(panel)
    val liveThreshold by rememberUpdatedState(edgeThreshold)
    val liveCollapsed by rememberUpdatedState(state.collapsed)

    val dragModifier = Modifier
      .offset { IntOffset(actual.x.roundToInt(), actual.y.roundToInt()) }
      .size(if (state.collapsed) RemoteLayout.IconSize else metrics.width,
        if (state.collapsed) RemoteLayout.IconSize else metrics.height)
      .pointerInput(Unit) {
        detectDragGestures(
          onDragStart = { dragOrigin = liveTopLeft; dragged = liveTopLeft },
          onDrag = { change, drag ->
            change.consume()
            val anchor = dragged ?: liveTopLeft
            dragged = liveBounds.clamp(anchor.x + drag.x, anchor.y + drag.y)
          },
          onDragEnd = {
            val dropped = dragged ?: liveTopLeft
            state.apply(settleRemote(dropped, dragOrigin.x, liveBounds, liveIcon, livePanel, liveThreshold, liveCollapsed))
            dragged = null
          },
          onDragCancel = { dragged = null },
        )
      }

    if (state.collapsed) {
      val remoteDescription = stringResource(R.string.floating_remote_control)
      Box(dragModifier.background(WeChatOverlayStrong, CircleShape)
        .semantics { contentDescription = remoteDescription }
        .combinedClickable(onClick = { state.apply(expandRemote(actual, icon, panel)) },
          onLongClick = onToggleFullscreen), contentAlignment = Alignment.Center) {
        RemoteGlyph(RemoteIcon.RemoteControl, Color.White.copy(.92f), Modifier.size(23.dp))
      }
    } else {
      MiniRemote(onKey, metrics, dragModifier)
    }
  }
}

/**
 * Sizes of the floating remote at one scale.
 *
 * There is a single layout: the portrait column of [MiniRemote]. A scale below one shrinks every
 * token proportionally, so the very same arrangement also fits a short area such as landscape instead
 * of needing a layout of its own. [width] and [height] are derived from [contentHeight] and
 * [contentWidth] so the fixed panel box can never clip the column at any scale.
 */
internal data class RemoteMetrics(
  val scale: Float,
  val width: Dp,
  val height: Dp,
  val padding: Dp,
  val keySpacing: Dp,
  val navSpacing: Dp,
  val keySize: Dp,
  val keyIconSize: Dp,
  val powerSize: Dp,
  val powerIconSize: Dp,
  val volumeSize: Dp,
  val volumeIconSize: Dp,
) {
  val contentHeight: Dp get() = padding * 2 + powerSize + keySize * 4 + volumeSize + keySpacing * 7
  val contentWidth: Dp get() = padding * 2 + keySize * 3 + navSpacing * 2
}

internal object RemoteLayout {
  /** Full-size reference metrics; [metrics] derives every other scale from these. */
  val Padding = 12.dp
  val KeySpacing = 4.dp
  val NavSpacing = 8.dp
  val KeySize = 52.dp
  val KeyIconSize = 24.dp
  val PowerSize = 42.dp
  val PowerIconSize = 21.dp
  val VolumeSize = 44.dp
  val VolumeIconSize = 19.dp

  /** The docked icon and the docking gesture keep their size at every scale. */
  val IconSize = 40.dp
  val EdgeThreshold = 24.dp
  val IconTint = Color.White.copy(.94f)

  /**
   * Share of the available height a shrunk panel may take. The remainder is the room left to drag it,
   * so a panel can never grow to the height that would pin it to the top edge.
   */
  const val UsableHeight = 0.92f
  const val MaxScale = 1f

  /** Frame left around the column so the rounded background is not flush with the keys. */
  private val WidthSlack = 8.dp
  private val HeightSlack = 2.dp

  fun metrics(scale: Float): RemoteMetrics {
    fun Dp.scaled() = (value * scale).roundToInt().dp
    val column = RemoteMetrics(
      scale = scale,
      width = 0.dp,
      height = 0.dp,
      padding = Padding.scaled(),
      keySpacing = KeySpacing.scaled(),
      navSpacing = NavSpacing.scaled(),
      keySize = KeySize.scaled(),
      keyIconSize = KeyIconSize.scaled(),
      powerSize = PowerSize.scaled(),
      powerIconSize = PowerIconSize.scaled(),
      volumeSize = VolumeSize.scaled(),
      volumeIconSize = VolumeIconSize.scaled(),
    )
    return column.copy(width = column.contentWidth + WidthSlack, height = column.contentHeight + HeightSlack)
  }
}

/**
 * Scale that makes the panel fit [areaHeight] pixels without needing a different layout.
 *
 * There is deliberately no lower bound: clamping the scale upwards is what would make the panel
 * taller than the area again and collapse its vertical drag range to nothing. Keys are already only
 * 42-52dp at full size, so a short area trades a little key size for a remote that still fits and
 * still moves.
 */
internal fun remoteScale(areaHeight: Float, panelHeight: Float): Float = when {
  areaHeight <= 0f || panelHeight <= 0f -> RemoteLayout.MaxScale
  else -> (areaHeight * RemoteLayout.UsableHeight / panelHeight).coerceAtMost(RemoteLayout.MaxScale)
}

/** Height of the full-size panel in pixels, the reference the scale is computed against. */
internal fun remotePanelHeight(density: Density): Float = with(density) {
  RemoteLayout.metrics(RemoteLayout.MaxScale).height.toPx()
}


/**
 * Pixel-space area the floating remote may occupy together with the size of the item inside it.
 *
 * Both the docked icon and the expanded panel are clamped against their own size, so a placement
 * computed for one size never silently gets re-clamped by the other.
 */
internal data class RemoteBounds(val width: Float, val height: Float, val itemWidth: Float, val itemHeight: Float) {
  val maxX: Float get() = (width - itemWidth).coerceAtLeast(0f)
  val maxY: Float get() = (height - itemHeight).coerceAtLeast(0f)

  fun clamp(x: Float, y: Float): Offset = Offset(x.coerceIn(0f, maxX), y.coerceIn(0f, maxY))

  /** Top-left corner for a remote centred on [center], kept fully inside the area. */
  fun topLeft(center: Offset): Offset = clamp(center.x - itemWidth / 2f, center.y - itemHeight / 2f)

  /** Centre of a remote whose top-left corner is [topLeft]. */
  fun centerOf(topLeft: Offset): Offset = Offset(topLeft.x + itemWidth / 2f, topLeft.y + itemHeight / 2f)
}

/** Resting placement of the floating remote: whether it is docked and where its centre sits. */
internal data class RemotePlacement(val collapsed: Boolean, val center: Offset)

/** Grows the docked icon into the full panel around the point the icon occupied. */
internal fun expandRemote(dropped: Offset, icon: RemoteBounds, panel: RemoteBounds): RemotePlacement {
  val topLeft = panel.topLeft(icon.centerOf(dropped))
  return RemotePlacement(collapsed = false, center = panel.centerOf(topLeft))
}

/** Shrinks the panel back to its icon and snaps it against the edge it was dropped on. */
internal fun dockRemote(dropped: Offset, icon: RemoteBounds, nearLeft: Boolean, nearRight: Boolean): RemotePlacement {
  val x = when {
    nearLeft -> 0f
    nearRight -> icon.maxX
    else -> dropped.x
  }
  val topLeft = icon.clamp(x, dropped.y)
  return RemotePlacement(collapsed = true, center = icon.centerOf(topLeft))
}

/** Leaves a plain drop exactly where the user put it. */
internal fun keepRemote(dropped: Offset, bounds: RemoteBounds, collapsed: Boolean): RemotePlacement =
  RemotePlacement(collapsed, bounds.centerOf(bounds.clamp(dropped.x, dropped.y)))

/**
 * Resolves the resting placement for a drag that ended on [dropped] having started at [dragStartX].
 *
 * Docking needs deliberate horizontal intent. A panel that merely sits flush against an edge because
 * it is wider than the space left next to the icon there - which is common in landscape - must not
 * collapse when the user only drags it vertically.
 */
internal fun settleRemote(
  dropped: Offset,
  dragStartX: Float,
  bounds: RemoteBounds,
  icon: RemoteBounds,
  panel: RemoteBounds,
  edgeThreshold: Float,
  collapsed: Boolean,
): RemotePlacement {
  val nearLeft = dropped.x <= edgeThreshold
  val nearRight = dropped.x >= bounds.maxX - edgeThreshold
  val pushedToEdge = abs(dropped.x - dragStartX) >= edgeThreshold
  return when {
    collapsed && !nearLeft && !nearRight -> expandRemote(dropped, icon, panel)
    !collapsed && pushedToEdge && (nearLeft || nearRight) -> dockRemote(dropped, icon, nearLeft, nearRight)
    else -> keepRemote(dropped, bounds, collapsed)
  }
}

/**
 * Placement of the floating remote.
 *
 * It is hoisted into [MainScreen] and saved so switching between the full-screen preview and the
 * main screen, or recreating the composition, keeps the remote where the user dropped it instead of
 * re-docking it to the right-hand edge.
 */
internal class FloatingRemoteState(collapsed: Boolean = true, center: Offset = Offset(Float.NaN, Float.NaN)) {
  var collapsed by mutableStateOf(collapsed)
  var center by mutableStateOf(center)

  fun apply(placement: RemotePlacement) {
    collapsed = placement.collapsed
    center = placement.center
  }
}

private val FloatingRemoteStateSaver = listSaver<FloatingRemoteState, Any>(
  save = { listOf(it.collapsed, it.center.x, it.center.y) },
  restore = { FloatingRemoteState(it[0] as Boolean, Offset(it[1] as Float, it[2] as Float)) },
)

/**
 * The floating remote: one portrait column of keys, rendered at [metrics] scale.
 *
 * Landscape and other short areas pass a smaller scale rather than a different arrangement, so the
 * remote always looks and behaves the same and only its proportions shrink to fit.
 */
@Composable
private fun MiniRemote(onKey: (Int) -> Unit, metrics: RemoteMetrics, modifier: Modifier = Modifier) {
  Column(modifier.background(WeChatOverlayStrong, RoundedCornerShape(20.dp)).padding(metrics.padding),
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.spacedBy(metrics.keySpacing)) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
      RemoteKey(RemoteIcon.Power, R.string.power, KeyEvent.KEYCODE_POWER, onKey, metrics,
        buttonSize = metrics.powerSize, iconSize = metrics.powerIconSize, tint = WeChatDanger)
    }
    RemoteKey(RemoteIcon.Up, R.string.direction_up, KeyEvent.KEYCODE_DPAD_UP, onKey, metrics)
    Row(verticalAlignment = Alignment.CenterVertically) {
      RemoteKey(RemoteIcon.Left, R.string.direction_left, KeyEvent.KEYCODE_DPAD_LEFT, onKey, metrics)
      RemoteKey(RemoteIcon.Confirm, R.string.confirm, KeyEvent.KEYCODE_DPAD_CENTER, onKey, metrics)
      RemoteKey(RemoteIcon.Right, R.string.direction_right, KeyEvent.KEYCODE_DPAD_RIGHT, onKey, metrics)
    }
    RemoteKey(RemoteIcon.Down, R.string.direction_down, KeyEvent.KEYCODE_DPAD_DOWN, onKey, metrics)
    Spacer(Modifier.height(metrics.keySpacing))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
      RemoteKey(RemoteIcon.VolumeDown, R.string.volume_down, KeyEvent.KEYCODE_VOLUME_DOWN, onKey, metrics,
        buttonSize = metrics.volumeSize, iconSize = metrics.volumeIconSize)
      Spacer(Modifier.size(metrics.volumeSize))
      RemoteKey(RemoteIcon.VolumeUp, R.string.volume_up, KeyEvent.KEYCODE_VOLUME_UP, onKey, metrics,
        buttonSize = metrics.volumeSize, iconSize = metrics.volumeIconSize)
    }
    Row(horizontalArrangement = Arrangement.spacedBy(metrics.navSpacing)) {
      RemoteKey(RemoteIcon.Back, R.string.back, KeyEvent.KEYCODE_BACK, onKey, metrics)
      RemoteKey(RemoteIcon.Home, R.string.home, KeyEvent.KEYCODE_HOME, onKey, metrics)
      RemoteKey(RemoteIcon.Menu, R.string.menu, KeyEvent.KEYCODE_MENU, onKey, metrics)
    }
  }
}

private enum class RemoteIcon { Up, Down, Left, Right, Confirm, Back, Home, Menu, Power, VolumeDown, VolumeUp, RemoteControl }

/** A remote key wired to a single Android key code, sized from [metrics]. */
@Composable
private fun RemoteKey(
  icon: RemoteIcon,
  @StringRes labelRes: Int,
  keyCode: Int,
  onKey: (Int) -> Unit,
  metrics: RemoteMetrics,
  buttonSize: Dp = metrics.keySize,
  iconSize: Dp = metrics.keyIconSize,
  tint: Color = RemoteLayout.IconTint,
) {
  RemoteButton(icon, stringResource(labelRes), buttonSize, iconSize, tint) { onKey(keyCode) }
}

@Composable
private fun RemoteButton(icon: RemoteIcon, description: String, buttonSize: Dp = RemoteLayout.KeySize,
  iconSize: Dp = RemoteLayout.KeyIconSize, tint: Color = RemoteLayout.IconTint, onClick: () -> Unit) {
  // A plain clickable Box rather than IconButton: IconButton enforces a 48dp minimum touch target on
  // top of whatever size it is handed, which shrinks its reported layout box while still drawing the
  // larger circle. The panel is sized from these metrics, so a key must be exactly what it claims.
  Box(
    modifier = Modifier.size(buttonSize).clip(CircleShape).background(Color.White.copy(.11f))
      .clickable(role = Role.Button, onClick = onClick)
      .semantics { contentDescription = description },
    contentAlignment = Alignment.Center,
  ) {
    RemoteGlyph(icon, tint, Modifier.size(iconSize))
  }
}

@Composable
private fun RemoteGlyph(icon: RemoteIcon, tint: Color, modifier: Modifier = Modifier) {
  Canvas(modifier) {
      val white = tint; val stroke = size.minDimension * .1f
      when (icon) {
        RemoteIcon.Up, RemoteIcon.Down, RemoteIcon.Left, RemoteIcon.Right -> {
          val p = Path()
          when (icon) {
            RemoteIcon.Up -> { p.moveTo(size.width / 2, size.height * .2f); p.lineTo(size.width * .2f, size.height * .65f); p.lineTo(size.width * .8f, size.height * .65f) }
            RemoteIcon.Down -> { p.moveTo(size.width / 2, size.height * .8f); p.lineTo(size.width * .2f, size.height * .35f); p.lineTo(size.width * .8f, size.height * .35f) }
            RemoteIcon.Left -> { p.moveTo(size.width * .2f, size.height / 2); p.lineTo(size.width * .65f, size.height * .2f); p.lineTo(size.width * .65f, size.height * .8f) }
            RemoteIcon.Right -> { p.moveTo(size.width * .8f, size.height / 2); p.lineTo(size.width * .35f, size.height * .2f); p.lineTo(size.width * .35f, size.height * .8f) }
            else -> Unit
          }
          p.close(); drawPath(p, white)
        }
        RemoteIcon.Confirm -> drawCircle(white, size.minDimension * .3f, style = Stroke(stroke))
        RemoteIcon.Back -> {
          val p = Path().apply { moveTo(size.width * .18f, size.height * .48f); lineTo(size.width * .45f, size.height * .22f); moveTo(size.width * .18f, size.height * .48f); lineTo(size.width * .45f, size.height * .74f) }
          drawPath(p, white, style = Stroke(stroke, cap = StrokeCap.Round))
          drawLine(white, Offset(size.width * .2f, size.height * .48f), Offset(size.width * .82f, size.height * .48f), stroke, StrokeCap.Round)
        }
        RemoteIcon.Home -> {
          val p = Path().apply { moveTo(size.width * .15f, size.height * .5f); lineTo(size.width * .5f, size.height * .2f); lineTo(size.width * .85f, size.height * .5f); lineTo(size.width * .76f, size.height * .5f); lineTo(size.width * .76f, size.height * .82f); lineTo(size.width * .24f, size.height * .82f); lineTo(size.width * .24f, size.height * .5f); close() }
          drawPath(p, white, style = Stroke(stroke, cap = StrokeCap.Round))
        }
        RemoteIcon.Menu -> repeat(3) { i ->
          val y = size.height * (.28f + i * .22f)
          drawLine(white, Offset(size.width * .2f, y), Offset(size.width * .8f, y), stroke, StrokeCap.Round)
        }
        RemoteIcon.Power -> {
          drawArc(white, -52f, 284f, false, topLeft = Offset(size.width * .17f, size.height * .2f),
            size = androidx.compose.ui.geometry.Size(size.width * .66f, size.height * .66f), style = Stroke(stroke, cap = StrokeCap.Round))
          drawLine(white, Offset(size.width * .5f, size.height * .08f), Offset(size.width * .5f, size.height * .48f), stroke, StrokeCap.Round)
        }
        RemoteIcon.VolumeDown, RemoteIcon.VolumeUp -> {
          val speaker = Path().apply { moveTo(size.width * .12f, size.height * .4f); lineTo(size.width * .32f, size.height * .4f); lineTo(size.width * .53f, size.height * .22f); lineTo(size.width * .53f, size.height * .78f); lineTo(size.width * .32f, size.height * .6f); lineTo(size.width * .12f, size.height * .6f); close() }
          drawPath(speaker, white)
          drawLine(white, Offset(size.width * .64f, size.height * .5f), Offset(size.width * .9f, size.height * .5f), stroke, StrokeCap.Round)
          if (icon == RemoteIcon.VolumeUp) drawLine(white, Offset(size.width * .77f, size.height * .37f), Offset(size.width * .77f, size.height * .63f), stroke, StrokeCap.Round)
        }
        RemoteIcon.RemoteControl -> {
          drawRoundRect(white, topLeft = Offset(size.width * .2f, size.height * .08f),
            size = androidx.compose.ui.geometry.Size(size.width * .6f, size.height * .84f),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.width * .14f), style = Stroke(stroke))
          drawCircle(white, size.minDimension * .09f, Offset(size.width * .5f, size.height * .34f))
          repeat(3) { i -> drawCircle(white, size.minDimension * .055f, Offset(size.width * (.36f + i * .14f), size.height * .68f)) }
        }
      }
  }
}

@Composable
private fun AspectRatioRemoteSurface(
  videoSize: VideoSize?,
  onSurfaceCreated: (Surface) -> Unit,
  onSurfaceDestroyed: (Surface) -> Unit,
  onTouch: (Int, Long, Int, Int, Float) -> Unit,
  modifier: Modifier,
) {
  Box(modifier.background(Color.Black), contentAlignment = Alignment.Center) {
    val surfaceModifier = if (videoSize != null && videoSize.width > 0 && videoSize.height > 0) {
      Modifier.aspectRatio(videoSize.width.toFloat() / videoSize.height).fillMaxSize()
    } else {
      Modifier.fillMaxSize()
    }
    RemoteSurface(videoSize, onSurfaceCreated, onSurfaceDestroyed, onTouch, surfaceModifier)
  }
}

@Composable
private fun RemoteSurface(videoSize: VideoSize?, onSurfaceCreated: (Surface) -> Unit,
  onSurfaceDestroyed: (Surface) -> Unit,
  onTouch: (Int, Long, Int, Int, Float) -> Unit, modifier: Modifier) {
  AndroidView(modifier = modifier.background(Color.Black, RoundedCornerShape(12.dp)), factory = { context ->
    SurfaceView(context).apply { holder.addCallback(object : SurfaceHolder.Callback {
      private var attachedSurface: Surface? = null
      override fun surfaceCreated(holder: SurfaceHolder) {
        attachedSurface = holder.surface
        onSurfaceCreated(holder.surface)
      }
      override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit
      override fun surfaceDestroyed(holder: SurfaceHolder) {
        attachedSurface?.let(onSurfaceDestroyed)
        attachedSurface = null
      }
    }) }
  }, update = { view ->
    view.setOnTouchListener { touched, event ->
      val remote = videoSize ?: return@setOnTouchListener false
      val indices = if (event.actionMasked == MotionEvent.ACTION_MOVE) 0 until event.pointerCount else listOf(event.actionIndex)
      indices.forEach { i -> mapPoint(event.getX(i), event.getY(i), touched.width, touched.height, remote)?.let {
        point -> onTouch(event.actionMasked, event.getPointerId(i).toLong(), point.first, point.second, event.getPressure(i))
      } }
      true
    }
  })
}

private fun mapPoint(x: Float, y: Float, viewWidth: Int, viewHeight: Int, remote: VideoSize): Pair<Int, Int>? {
  if (viewWidth == 0 || viewHeight == 0) return null
  val scale = minOf(viewWidth.toFloat() / remote.width, viewHeight.toFloat() / remote.height)
  val w = remote.width * scale; val h = remote.height * scale
  val left = (viewWidth - w) / 2f; val top = (viewHeight - h) / 2f
  if (x !in left..left + w || y !in top..top + h) return null
  return ((x - left) / scale).toInt() to ((y - top) / scale).toInt()
}

@Composable
private fun scrcpyStatus(state: ScrcpyState): String = when (state) {
  ScrcpyState.Idle -> stringResource(R.string.scrcpy_ready)
  ScrcpyState.InstallingServer -> stringResource(R.string.scrcpy_installing)
  ScrcpyState.StartingServer -> stringResource(R.string.scrcpy_starting)
  ScrcpyState.ConnectingStreams -> stringResource(R.string.scrcpy_connecting_streams)
  is ScrcpyState.Streaming -> stringResource(R.string.scrcpy_streaming, state.videoSize.width, state.videoSize.height)
  is ScrcpyState.Failed -> stringResource(R.string.scrcpy_failed, state.cause.message ?: stringResource(R.string.unknown_error))
}

@Composable
private fun StatusPill(state: AdbConnectionState) {
  val (label, color) = when (state) {
    AdbConnectionState.Disconnected -> stringResource(R.string.adb_disconnected) to MaterialTheme.colorScheme.outline
    is AdbConnectionState.Connecting -> stringResource(R.string.adb_connecting, state.endpoint.serial) to MaterialTheme.colorScheme.primary
    is AdbConnectionState.Authorizing -> stringResource(R.string.adb_authorizing) to MaterialTheme.colorScheme.tertiary
    is AdbConnectionState.Connected -> stringResource(R.string.adb_connected, state.device.endpoint.serial) to WeChatBrand
    is AdbConnectionState.Failed -> stringResource(R.string.adb_failed, state.cause.message ?: stringResource(R.string.unknown_error)) to MaterialTheme.colorScheme.error
  }
  Box(Modifier.background(color.copy(.12f), RoundedCornerShape(100)).padding(horizontal = 12.dp, vertical = 7.dp)) {
    Text(label, color = color, style = MaterialTheme.typography.labelLarge)
  }
}

@Preview(showBackground = true, widthDp = 700, heightDp = 600)
@Composable
private fun MainScreenPreview() {
  AScrcpyTheme {
    MainScreen(
      state = MainUiState(), onHostChange = {}, onPortChange = {}, onConnect = {}, onDisconnect = {},
      onProbe = {}, onStartMirroring = {}, onStopMirroring = {}, onSurfaceCreated = {},
      onSurfaceDestroyed = {}, onTouch = { _, _, _, _, _ -> }, onKey = {}, onDeleteHost = {},
    )
  }
}
