package ernest.ascrcpy.ui.main

import android.app.Application
import android.view.Surface
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import ernest.ascrcpy.adb.AdbClient
import ernest.ascrcpy.adb.AdbConnectionState
import ernest.ascrcpy.adb.AdbEndpoint
import ernest.ascrcpy.adb.DefaultAdbClient
import ernest.ascrcpy.scrcpy.ScrcpyClient
import ernest.ascrcpy.scrcpy.ScrcpyState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class MainUiState(
  val host: String = "192.168.68.59",
  val port: String = "5555",
  val connectionState: AdbConnectionState = AdbConnectionState.Disconnected,
  val console: String = "",
  val busy: Boolean = false,
  val scrcpyState: ScrcpyState = ScrcpyState.Idle,
) {
  val connected: Boolean get() = connectionState is AdbConnectionState.Connected
}

class MainViewModel(application: Application) : AndroidViewModel(application) {
  private val client: AdbClient = DefaultAdbClient.factory(application).create()
  private val scrcpy = ScrcpyClient(client)
  private val form = MutableStateFlow(MainUiState())
  private var surface: Surface? = null

  val uiState: StateFlow<MainUiState> = combine(form, client.state, scrcpy.state) { current, connection, scrcpyState ->
    current.copy(connectionState = connection, scrcpyState = scrcpyState)
  }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MainUiState())

  fun setHost(value: String) = form.update { copy(host = value.trim()) }

  fun setPort(value: String) = form.update { copy(port = value.filter(Char::isDigit).take(5)) }

  fun connect() {
    val snapshot = form.value
    val port = snapshot.port.toIntOrNull()
    if (port == null || port !in 1..65535) {
      form.update { copy(console = "Invalid ADB port") }
      return
    }
    viewModelScope.launch {
      form.update { copy(busy = true, console = "Opening ADB connection…") }
      runCatching { client.connect(AdbEndpoint(snapshot.host, port)) }
        .onSuccess { device -> form.update { copy(console = "Connected\n${device.banner}") } }
        .onFailure { error -> form.update { copy(console = error.stackTraceToString()) } }
      form.update { copy(busy = false) }
    }
  }

  fun disconnect() {
    viewModelScope.launch {
      form.update { copy(busy = true) }
      client.disconnect()
      form.update { copy(busy = false, console = "Disconnected") }
    }
  }

  fun probe() {
    viewModelScope.launch {
      form.update { copy(busy = true, console = "Running device probe…") }
      runCatching { client.shell("getprop ro.product.model; getprop ro.build.version.release; id") }
        .onSuccess { result -> form.update { copy(console = result.text().trim()) } }
        .onFailure { error -> form.update { copy(console = error.stackTraceToString()) } }
      form.update { copy(busy = false) }
    }
  }

  fun setSurface(value: Surface?) {
    surface = value
    if (value == null) scrcpy.stop()
  }

  fun startMirroring() {
    val output = surface
    if (output == null || !output.isValid) {
      form.update { copy(console = "Video surface is not ready") }
      return
    }
    viewModelScope.launch {
      form.update { copy(busy = true, console = "Installing and starting scrcpy-server 4.0…") }
      runCatching {
        getApplication<Application>().assets.open("scrcpy-server-v4.0").use { server ->
          scrcpy.start(server, output)
        }
      }.onFailure { error -> form.update { copy(console = error.stackTraceToString()) } }
      form.update { copy(busy = false) }
    }
  }

  fun stopMirroring() = scrcpy.stop()

  fun injectTouch(action: Int, pointerId: Long, x: Int, y: Int, pressure: Float) {
    viewModelScope.launch { scrcpy.injectTouch(action, pointerId, x, y, pressure) }
  }

  override fun onCleared() {
    scrcpy.close()
    client.close()
  }
}

private inline fun MutableStateFlow<MainUiState>.update(transform: MainUiState.() -> MainUiState) {
  value = value.transform()
}
