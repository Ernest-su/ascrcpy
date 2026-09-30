package ernest.ascrcpy.ui.main

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
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
import kotlin.math.roundToInt

@Composable
fun MainScreen(viewModel: MainViewModel, modifier: Modifier = Modifier) {
  val state by viewModel.uiState.collectAsStateWithLifecycle()
  MainScreen(state, viewModel::setHost, viewModel::setPort, viewModel::connect,
    viewModel::disconnect, viewModel::probe, viewModel::startMirroring,
    viewModel::stopMirroring, viewModel::attachSurface, viewModel::detachSurface, viewModel::injectTouch,
    viewModel::injectKey, viewModel::deleteHost, modifier)
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
      onProbe, onStartMirroring, onStopMirroring, onSurfaceCreated, onSurfaceDestroyed, onTouch, onDeleteHost, modifier)
    if (state.connected) {
      FloatingRemote(onKey, { fullscreen = true }, remoteState, Modifier.fillMaxSize().safeDrawingPadding())
    }
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
          Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            HostHistoryField(state, onHostChange, onDeleteHost, Modifier.weight(1f))
            Spacer(Modifier.width(12.dp))
            OutlinedTextField(state.port, onPortChange, Modifier.width(112.dp), label = { Text(stringResource(R.string.port)) },
              singleLine = true, enabled = !state.busy && !state.connected)
          }
          StatusPill(state.connectionState)
          Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (state.connected) {
              Button(onProbe, enabled = !state.busy) { Text(stringResource(R.string.test_shell)) }
              Button(onStartMirroring, enabled = !state.busy && state.scrcpyState !is ScrcpyState.Streaming) { Text(stringResource(R.string.start_mirroring)) }
              OutlinedButton(onDisconnect, enabled = !state.busy) { Text(stringResource(R.string.disconnect)) }
            } else {
              Button(onConnect, enabled = !state.busy && state.host.isNotBlank()) {
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
      with(density) { RemoteIconSize.toPx() }, with(density) { RemoteIconSize.toPx() })
    val panel = RemoteBounds(areaWidth, areaHeight,
      with(density) { RemotePanelWidth.toPx() }, with(density) { RemotePanelHeight.toPx() })
    val bounds = if (state.collapsed) icon else panel
    val edgeThreshold = with(density) { RemoteEdgeThreshold.toPx() }

    // The remote is remembered by the point at its centre, so growing or shrinking the panel never
    // teleports it to a screen edge; an untouched remote still docks to the right-hand side.
    val restingCenter = if (state.center.x.isNaN()) Offset(icon.maxX + icon.itemWidth / 2f, areaHeight / 2f)
      else state.center
    var dragged by remember { mutableStateOf<Offset?>(null) }
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
      .size(if (state.collapsed) RemoteIconSize else RemotePanelWidth, if (state.collapsed) RemoteIconSize else RemotePanelHeight)
      .pointerInput(Unit) {
        detectDragGestures(
          onDragStart = { dragged = liveTopLeft },
          onDrag = { change, drag ->
            change.consume()
            val anchor = dragged ?: liveTopLeft
            dragged = liveBounds.clamp(anchor.x + drag.x, anchor.y + drag.y)
          },
          onDragEnd = {
            val dropped = dragged ?: liveTopLeft
            state.apply(settleRemote(dropped, liveBounds, liveIcon, livePanel, liveThreshold, liveCollapsed))
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
      MiniRemote(onKey, dragModifier)
    }
  }
}

private val RemoteIconSize = 40.dp
private val RemotePanelWidth = 204.dp
private val RemotePanelHeight = 348.dp
private val RemoteEdgeThreshold = 24.dp

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

/** Resolves the resting placement for a drag that ended on [dropped]. */
internal fun settleRemote(
  dropped: Offset,
  bounds: RemoteBounds,
  icon: RemoteBounds,
  panel: RemoteBounds,
  edgeThreshold: Float,
  collapsed: Boolean,
): RemotePlacement {
  val nearLeft = dropped.x <= edgeThreshold
  val nearRight = dropped.x >= bounds.maxX - edgeThreshold
  return when {
    collapsed && !nearLeft && !nearRight -> expandRemote(dropped, icon, panel)
    !collapsed && (nearLeft || nearRight) -> dockRemote(dropped, icon, nearLeft, nearRight)
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

@Composable
private fun MiniRemote(onKey: (Int) -> Unit, modifier: Modifier = Modifier) {
  Column(modifier.background(WeChatOverlayStrong, RoundedCornerShape(20.dp)).padding(12.dp),
    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
      RemoteButton(RemoteIcon.Power, stringResource(R.string.power), buttonSize = 42.dp, iconSize = 21.dp, tint = WeChatDanger) {
        onKey(KeyEvent.KEYCODE_POWER)
      }
    }
    RemoteButton(RemoteIcon.Up, stringResource(R.string.direction_up)) { onKey(KeyEvent.KEYCODE_DPAD_UP) }
    Row(verticalAlignment = Alignment.CenterVertically) {
      RemoteButton(RemoteIcon.Left, stringResource(R.string.direction_left)) { onKey(KeyEvent.KEYCODE_DPAD_LEFT) }
      RemoteButton(RemoteIcon.Confirm, stringResource(R.string.confirm)) { onKey(KeyEvent.KEYCODE_DPAD_CENTER) }
      RemoteButton(RemoteIcon.Right, stringResource(R.string.direction_right)) { onKey(KeyEvent.KEYCODE_DPAD_RIGHT) }
    }
    RemoteButton(RemoteIcon.Down, stringResource(R.string.direction_down)) { onKey(KeyEvent.KEYCODE_DPAD_DOWN) }
    Spacer(Modifier.height(4.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
      RemoteButton(RemoteIcon.VolumeDown, stringResource(R.string.volume_down), buttonSize = 44.dp, iconSize = 19.dp) { onKey(KeyEvent.KEYCODE_VOLUME_DOWN) }
      Spacer(Modifier.size(44.dp))
      RemoteButton(RemoteIcon.VolumeUp, stringResource(R.string.volume_up), buttonSize = 44.dp, iconSize = 19.dp) { onKey(KeyEvent.KEYCODE_VOLUME_UP) }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      RemoteButton(RemoteIcon.Back, stringResource(R.string.back)) { onKey(KeyEvent.KEYCODE_BACK) }
      RemoteButton(RemoteIcon.Home, stringResource(R.string.home)) { onKey(KeyEvent.KEYCODE_HOME) }
      RemoteButton(RemoteIcon.Menu, stringResource(R.string.menu)) { onKey(KeyEvent.KEYCODE_MENU) }
    }
  }
}

private enum class RemoteIcon { Up, Down, Left, Right, Confirm, Back, Home, Menu, Power, VolumeDown, VolumeUp, RemoteControl }

@Composable
private fun RemoteButton(icon: RemoteIcon, description: String, buttonSize: androidx.compose.ui.unit.Dp = 52.dp,
  iconSize: androidx.compose.ui.unit.Dp = 24.dp, tint: Color = Color.White.copy(.94f), onClick: () -> Unit) {
  IconButton(onClick, Modifier.size(buttonSize).background(Color.White.copy(.11f), CircleShape)
    .semantics { contentDescription = description }) {
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
