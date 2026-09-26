package ernest.ascrcpy.ui.main

import android.app.Application
import android.view.Surface
import androidx.annotation.StringRes
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import ernest.ascrcpy.R
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

data class MainUiState(
  val host: String = "192.168.68.59",
  val hostHistory: List<String> = emptyList(),
  val port: String = "5555",
  val connectionState: AdbConnectionState = AdbConnectionState.Disconnected,
  val console: String = "",
  val busy: Boolean = false,
  val scrcpyState: ScrcpyState = ScrcpyState.Idle,
) {
  val connected: Boolean get() = connectionState is AdbConnectionState.Connected
}

class MainViewModel(application: Application) : AndroidViewModel(application) {
  private val preferences = application.getSharedPreferences("connection_history", Application.MODE_PRIVATE)
  private val savedHosts = preferences.getString(HOST_HISTORY_KEY, null)
    ?.split('\n')?.filter(String::isNotBlank)?.distinct().orEmpty()
  private val client: AdbClient = DefaultAdbClient.factory(application).create()
  private val scrcpy = ScrcpyClient(client)
  private val form = MutableStateFlow(MainUiState(
    host = savedHosts.firstOrNull() ?: "192.168.68.59",
    hostHistory = savedHosts,
  ))
  private var surface: Surface? = null
  private var mirrorJob: Job? = null
  private var surfaceStartJob: Job? = null
  private var shouldMirror = false

  val uiState: StateFlow<MainUiState> = combine(form, client.state, scrcpy.state) { current, connection, scrcpyState ->
    current.copy(connectionState = connection, scrcpyState = scrcpyState)
  }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MainUiState())

  fun setHost(value: String) = form.update { copy(host = value.trim()) }

  fun setPort(value: String) = form.update { copy(port = value.filter(Char::isDigit).take(5)) }

  fun deleteHost(host: String) {
    val updated = form.value.hostHistory.filterNot { it == host }
    preferences.edit().putString(HOST_HISTORY_KEY, updated.joinToString("\n")).apply()
    form.update { copy(hostHistory = updated) }
  }

  fun connect() {
    val snapshot = form.value
    val port = snapshot.port.toIntOrNull()
    if (port == null || port !in 1..65535) {
      form.update { copy(console = string(R.string.log_invalid_port)) }
      return
    }
    rememberHost(snapshot.host)
    viewModelScope.launch {
      form.update { copy(busy = true, console = string(R.string.log_opening_connection)) }
      runCatching { client.connect(AdbEndpoint(snapshot.host, port)) }
        .onSuccess { device ->
          shouldMirror = true
          form.update { copy(console = string(R.string.log_connected, device.banner)) }
        }
        .onFailure { error -> form.update { copy(console = error.stackTraceToString()) } }
      form.update { copy(busy = false) }
      scheduleMirroring()
    }
  }

  fun disconnect() {
    viewModelScope.launch {
      shouldMirror = false
      surfaceStartJob?.cancel()
      mirrorJob?.cancel()
      scrcpy.stop()
      form.update { copy(busy = true) }
      client.disconnect()
      form.update { copy(busy = false, console = string(R.string.log_disconnected)) }
    }
  }

  fun probe() {
    viewModelScope.launch {
      form.update { copy(busy = true, console = string(R.string.log_running_probe)) }
      runCatching { client.shell("getprop ro.product.model; getprop ro.build.version.release; id") }
        .onSuccess { result -> form.update { copy(console = result.text().trim()) } }
        .onFailure { error -> form.update { copy(console = error.stackTraceToString()) } }
      form.update { copy(busy = false) }
    }
  }

  fun attachSurface(value: Surface) {
    surface = value
    scheduleMirroring(SURFACE_SETTLE_DELAY_MILLIS)
  }

  fun detachSurface(value: Surface) {
    if (surface !== value) return
    surface = null
    surfaceStartJob?.cancel()
    mirrorJob?.cancel()
    scrcpy.stop()
  }

  fun startMirroring() {
    shouldMirror = true
    scheduleMirroring()
  }

  private fun scheduleMirroring(delayMillis: Long = 0) {
    surfaceStartJob?.cancel()
    surfaceStartJob = viewModelScope.launch {
      if (delayMillis > 0) delay(delayMillis)
      startMirroringIfReady()
    }
  }

  private fun startMirroringIfReady() {
    val output = surface
    val streamState = scrcpy.state.value
    if (!shouldMirror || client.state.value !is AdbConnectionState.Connected || output == null || !output.isValid ||
      mirrorJob?.isActive == true || (streamState !is ScrcpyState.Idle && streamState !is ScrcpyState.Failed)
    ) return
    mirrorJob = viewModelScope.launch {
      form.update { copy(busy = true, console = string(R.string.log_starting_scrcpy)) }
      runCatching {
        getApplication<Application>().assets.open("scrcpy-server-v4.0").use { server ->
          scrcpy.start(server, output)
        }
      }.onFailure { error -> form.update { copy(console = error.stackTraceToString()) } }
      form.update { copy(busy = false) }
    }
  }

  fun stopMirroring() {
    shouldMirror = false
    surfaceStartJob?.cancel()
    mirrorJob?.cancel()
    scrcpy.stop()
  }

  fun injectTouch(action: Int, pointerId: Long, x: Int, y: Int, pressure: Float) {
    viewModelScope.launch { scrcpy.injectTouch(action, pointerId, x, y, pressure) }
  }

  fun injectKey(keyCode: Int) {
    viewModelScope.launch {
      scrcpy.injectKey(android.view.KeyEvent.ACTION_DOWN, keyCode)
      scrcpy.injectKey(android.view.KeyEvent.ACTION_UP, keyCode)
    }
  }

  override fun onCleared() {
    scrcpy.close()
    client.close()
  }

  private fun rememberHost(host: String) {
    if (host.isBlank()) return
    val updated = (listOf(host) + form.value.hostHistory.filterNot { it == host }).take(MAX_HOST_HISTORY)
    preferences.edit().putString(HOST_HISTORY_KEY, updated.joinToString("\n")).apply()
    form.update { copy(hostHistory = updated) }
  }

  private fun string(@StringRes id: Int, vararg arguments: Any): String =
    getApplication<Application>().getString(id, *arguments)

  private companion object {
    const val HOST_HISTORY_KEY = "hosts"
    const val MAX_HOST_HISTORY = 20
    const val SURFACE_SETTLE_DELAY_MILLIS = 300L
  }
}

private inline fun MutableStateFlow<MainUiState>.update(transform: MainUiState.() -> MainUiState) {
  value = value.transform()
}
