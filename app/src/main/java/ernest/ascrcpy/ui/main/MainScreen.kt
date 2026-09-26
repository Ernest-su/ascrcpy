package ernest.ascrcpy.ui.main

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ernest.ascrcpy.adb.AdbConnectionState
import ernest.ascrcpy.scrcpy.ScrcpyState
import ernest.ascrcpy.scrcpy.VideoSize
import ernest.ascrcpy.theme.AScrcpyTheme
import android.view.MotionEvent
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView

@Composable
fun MainScreen(viewModel: MainViewModel, modifier: Modifier = Modifier) {
  val state by viewModel.uiState.collectAsStateWithLifecycle()
  MainScreen(
    state = state,
    onHostChange = viewModel::setHost,
    onPortChange = viewModel::setPort,
    onConnect = viewModel::connect,
    onDisconnect = viewModel::disconnect,
    onProbe = viewModel::probe,
    onStartMirroring = viewModel::startMirroring,
    onStopMirroring = viewModel::stopMirroring,
    onSurface = viewModel::setSurface,
    onTouch = viewModel::injectTouch,
    modifier = modifier,
  )
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
  onSurface: (Surface?) -> Unit,
  onTouch: (Int, Long, Int, Int, Float) -> Unit,
  modifier: Modifier = Modifier,
) {
  Scaffold(modifier = modifier.fillMaxSize()) { padding ->
    Column(
      modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())
        .padding(horizontal = 24.dp, vertical = 20.dp),
      verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
      Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("AScrcpy", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
        Text("Android-to-Android remote control", style = MaterialTheme.typography.bodyLarge,
          color = MaterialTheme.colorScheme.onSurfaceVariant)
      }
      Card(modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
          Text("ADB device", style = MaterialTheme.typography.titleLarge)
          Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(value = state.host, onValueChange = onHostChange,
              label = { Text("IP address or host") }, singleLine = true,
              enabled = !state.busy && !state.connected, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(12.dp))
            OutlinedTextField(value = state.port, onValueChange = onPortChange,
              label = { Text("Port") }, singleLine = true,
              enabled = !state.busy && !state.connected, modifier = Modifier.width(112.dp))
          }
          StatusPill(state.connectionState)
          Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (state.connected) {
              Button(onClick = onProbe, enabled = !state.busy) { Text("Test shell") }
              Button(onClick = onStartMirroring, enabled = !state.busy && state.scrcpyState !is ScrcpyState.Streaming) {
                Text("Start mirroring")
              }
              OutlinedButton(onClick = onDisconnect, enabled = !state.busy) { Text("Disconnect") }
            } else {
              Button(onClick = onConnect, enabled = !state.busy && state.host.isNotBlank()) {
                if (state.busy) {
                  CircularProgressIndicator(modifier = Modifier.width(18.dp).height(18.dp), strokeWidth = 2.dp)
                  Spacer(Modifier.width(8.dp))
                }
                Text(if (state.busy) "Connecting" else "Connect")
              }
            }
          }
        }
      }
      if (state.connected) {
        val videoSize = (state.scrcpyState as? ScrcpyState.Streaming)?.videoSize
        Card(modifier = Modifier.fillMaxWidth()) {
          Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
              verticalAlignment = Alignment.CenterVertically) {
              Text("Remote display", style = MaterialTheme.typography.titleLarge)
              if (state.scrcpyState !is ScrcpyState.Idle) {
                OutlinedButton(onClick = onStopMirroring) { Text("Stop") }
              }
            }
            RemoteSurface(videoSize, onSurface, onTouch)
            Text(scrcpyStatus(state.scrcpyState), color = MaterialTheme.colorScheme.onSurfaceVariant)
          }
        }
      }
      if (state.console.isNotBlank()) {
        Card(modifier = Modifier.fillMaxWidth()) {
          Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Session log", style = MaterialTheme.typography.titleMedium)
            Text(state.console, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium)
          }
        }
      }
    }
  }
}

@Composable
private fun RemoteSurface(
  videoSize: VideoSize?,
  onSurface: (Surface?) -> Unit,
  onTouch: (Int, Long, Int, Int, Float) -> Unit,
) {
  AndroidView(
    modifier = Modifier.fillMaxWidth().height(420.dp).background(Color.Black, RoundedCornerShape(12.dp)),
    factory = { context ->
      SurfaceView(context).apply {
        holder.addCallback(object : SurfaceHolder.Callback {
          override fun surfaceCreated(holder: SurfaceHolder) = onSurface(holder.surface)
          override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit
          override fun surfaceDestroyed(holder: SurfaceHolder) = onSurface(null)
        })
      }
    },
    update = { view ->
      view.setOnTouchListener { touchedView, event ->
        val remote = videoSize ?: return@setOnTouchListener false
        val indices = if (event.actionMasked == MotionEvent.ACTION_MOVE) {
          0 until event.pointerCount
        } else {
          listOf(event.actionIndex)
        }
        indices.forEach { index ->
          val point = mapPoint(event.getX(index), event.getY(index), touchedView.width, touchedView.height, remote)
          if (point != null) {
            onTouch(event.actionMasked, event.getPointerId(index).toLong(), point.first, point.second,
              event.getPressure(index))
          }
        }
        true
      }
    },
  )
}

private fun mapPoint(x: Float, y: Float, viewWidth: Int, viewHeight: Int, remote: VideoSize): Pair<Int, Int>? {
  if (viewWidth == 0 || viewHeight == 0) return null
  val scale = minOf(viewWidth.toFloat() / remote.width, viewHeight.toFloat() / remote.height)
  val contentWidth = remote.width * scale
  val contentHeight = remote.height * scale
  val left = (viewWidth - contentWidth) / 2f
  val top = (viewHeight - contentHeight) / 2f
  if (x !in left..(left + contentWidth) || y !in top..(top + contentHeight)) return null
  return (((x - left) / scale).toInt() to ((y - top) / scale).toInt())
}

private fun scrcpyStatus(state: ScrcpyState): String = when (state) {
  ScrcpyState.Idle -> "Ready"
  ScrcpyState.InstallingServer -> "Installing server…"
  ScrcpyState.StartingServer -> "Starting server…"
  ScrcpyState.ConnectingStreams -> "Connecting video and control streams…"
  is ScrcpyState.Streaming -> "Streaming ${state.videoSize.width} × ${state.videoSize.height}"
  is ScrcpyState.Failed -> "Stream failed: ${state.cause.message}"
}

@Composable
private fun StatusPill(connectionState: AdbConnectionState) {
  val (label, color) = when (connectionState) {
    AdbConnectionState.Disconnected -> "Disconnected" to MaterialTheme.colorScheme.outline
    is AdbConnectionState.Connecting -> "Connecting to ${connectionState.endpoint.serial}" to MaterialTheme.colorScheme.primary
    is AdbConnectionState.Authorizing -> "Authorize this controller on the target device" to MaterialTheme.colorScheme.tertiary
    is AdbConnectionState.Connected -> "Connected · ${connectionState.device.endpoint.serial}" to Color(0xFF2E7D32)
    is AdbConnectionState.Failed -> "Failed · ${connectionState.cause.message}" to MaterialTheme.colorScheme.error
  }
  Box(modifier = Modifier.background(color.copy(alpha = 0.12f), RoundedCornerShape(100))
    .padding(horizontal = 12.dp, vertical = 7.dp)) {
    Text(label, color = color, style = MaterialTheme.typography.labelLarge)
  }
}

@Preview(showBackground = true, widthDp = 700, heightDp = 600)
@Composable
private fun MainScreenPreview() {
  AScrcpyTheme {
    MainScreen(MainUiState(host = "192.168.1.20", port = "5555"), {}, {}, {}, {}, {}, {}, {}, {},
      { _, _, _, _, _ -> })
  }
}
