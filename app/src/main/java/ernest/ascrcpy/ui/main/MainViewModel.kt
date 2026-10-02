package ernest.ascrcpy.ui.main

import android.app.Application
import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.view.Surface
import androidx.annotation.StringRes
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import ernest.ascrcpy.R
import ernest.ascrcpy.adb.AdbClient
import ernest.ascrcpy.adb.AdbConnectionState
import ernest.ascrcpy.adb.AdbEndpoint
import ernest.ascrcpy.adb.DefaultAdbClient
import ernest.ascrcpy.adb.transport.UsbAdbTransport
import ernest.ascrcpy.scrcpy.ScrcpyClient
import ernest.ascrcpy.scrcpy.ScrcpyState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeoutOrNull

enum class ConnectionMethod { TCP, TAILCAT, WIRELESS_CODE, WIRELESS_QR, USB }

data class MainUiState(
  val host: String = "192.168.68.59",
  val hostHistory: List<String> = emptyList(),
  val port: String = "5555",
  val tailcatAddress: String = "",
  val method: ConnectionMethod = ConnectionMethod.TCP,
  val pairingHost: String = "",
  val pairingPort: String = "",
  val pairingCode: String = "",
  val qrPayload: String? = null,
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
  private val tailcat = TailcatForwarder(application)
  private val usbManager = application.getSystemService(Context.USB_SERVICE) as UsbManager
  private var qrDiscovery: QrPairingDiscovery? = null
  private var qrJob: Job? = null
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
  fun setTailcatAddress(value: String) = form.update { copy(tailcatAddress = value.trim()) }
  fun setMethod(value: ConnectionMethod) {
    stopQrPairing()
    form.update { copy(method = value, port = when (value) {
      ConnectionMethod.TCP -> if (port.isBlank()) "5555" else port
      ConnectionMethod.TAILCAT -> if (method != value) "5555" else port
      ConnectionMethod.WIRELESS_CODE -> if (method != value) "" else port
      else -> port
    }) }
  }
  fun setPairingHost(value: String) = form.update { copy(pairingHost = value.trim()) }
  fun setPairingPort(value: String) = form.update { copy(pairingPort = value.filter(Char::isDigit).take(5)) }
  fun setPairingCode(value: String) = form.update { copy(pairingCode = value.filter(Char::isDigit).take(6)) }
  fun usbDevices(): List<UsbDevice> = UsbAdbTransport.discover(usbManager)
  fun hasUsbPermission(device: UsbDevice): Boolean = usbManager.hasPermission(device)

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
    if (snapshot.method == ConnectionMethod.TAILCAT &&
      (snapshot.tailcatAddress.isBlank() || snapshot.tailcatAddress.any(Char::isWhitespace))) {
      form.update { copy(console = string(R.string.log_invalid_tailcat_address)) }
      return
    }
    if (snapshot.method != ConnectionMethod.TAILCAT) rememberHost(snapshot.host)
    viewModelScope.launch {
      form.update { copy(busy = true, console = string(R.string.log_opening_connection)) }
      runCatching {
        when (snapshot.method) {
          ConnectionMethod.TAILCAT -> {
            form.update { copy(console = string(R.string.log_starting_tailcat)) }
            val localPort = tailcat.start(snapshot.tailcatAddress, port)
            client.connect(AdbEndpoint("127.0.0.1", localPort))
          }
          ConnectionMethod.WIRELESS_CODE, ConnectionMethod.WIRELESS_QR ->
            client.connectWireless(AdbEndpoint(snapshot.host, port))
          else -> client.connect(AdbEndpoint(snapshot.host, port))
        }
      }
        .onSuccess { device ->
          shouldMirror = true
          form.update { copy(console = string(R.string.log_connected, device.banner)) }
        }
        .onFailure { error ->
          tailcat.stop()
          form.update { copy(console = error.message ?: string(R.string.unknown_error)) }
        }
      form.update { copy(busy = false) }
      scheduleMirroring()
    }
  }

  fun connectUsb(device: UsbDevice) {
    if (!usbManager.hasPermission(device)) {
      form.update { copy(console = string(R.string.log_usb_denied)) }
      return
    }
    viewModelScope.launch {
      form.update { copy(busy = true, console = string(R.string.log_opening_connection)) }
      runCatching { client.connectUsb(device) }
        .onSuccess { target ->
          shouldMirror = true
          form.update { copy(console = string(R.string.log_connected, target.banner)) }
          scheduleMirroring()
        }
        .onFailure { error -> form.update { copy(console = error.message ?: string(R.string.unknown_error)) } }
      form.update { copy(busy = false) }
    }
  }

  fun reportUsbError(message: String) = form.update { copy(console = message) }

  fun pairWithCode() {
    val snapshot = form.value
    val port = snapshot.pairingPort.toIntOrNull()
    if (snapshot.pairingHost.isBlank() || port == null || port !in 1..65535 ||
      !snapshot.pairingCode.matches(Regex("[0-9]{6}"))) {
      form.update { copy(console = string(R.string.log_invalid_pairing)) }
      return
    }
    viewModelScope.launch {
      form.update { copy(busy = true, console = string(R.string.log_pairing)) }
      runCatching { client.pairWireless(AdbEndpoint(snapshot.pairingHost, port), snapshot.pairingCode) }
        .onSuccess { guid -> form.update {
          copy(host = snapshot.pairingHost, port = "", pairingCode = "", console = string(R.string.log_paired, guid))
        } }
        .onFailure { error -> form.update { copy(console = error.message ?: string(R.string.unknown_error)) } }
      form.update { copy(busy = false) }
    }
  }

  fun startQrPairing() {
    if (form.value.busy || qrDiscovery != null) return
    val qr = QrPairing()
    val connections = Channel<QrPairingDiscovery.ResolvedService>(Channel.UNLIMITED)
    var pairingStarted = false
    val discovery = QrPairingDiscovery(getApplication(), qr.serviceName,
      onPairing = { service ->
        if (!pairingStarted) {
          pairingStarted = true
          qrJob = viewModelScope.launch {
            form.update { copy(busy = true, console = string(R.string.log_pairing)) }
            try {
              val guid = client.pairWireless(AdbEndpoint(service.host, service.port), qr.password)
              form.update { copy(console = string(R.string.log_finding_connection, guid)) }
              val connection = withTimeoutOrNull(30_000) {
                while (true) {
                  val candidate = connections.receive()
                  if (candidate.name.contains(guid, ignoreCase = true) || candidate.host == service.host)
                    return@withTimeoutOrNull candidate
                }
                @Suppress("UNREACHABLE_CODE")
                null
              }
              if (connection == null) {
                form.update { copy(method = ConnectionMethod.WIRELESS_CODE, host = service.host,
                  console = string(R.string.log_connection_not_found, guid)) }
              } else {
                val endpoint = AdbEndpoint(connection.host, connection.port)
                val target = client.connectWireless(endpoint)
                shouldMirror = true
                form.update { copy(host = connection.host, port = connection.port.toString(),
                  console = string(R.string.log_connected, target.banner)) }
                scheduleMirroring()
              }
            } catch (error: CancellationException) {
              throw error
            } catch (error: Exception) {
              form.update { copy(console = error.message ?: string(R.string.unknown_error)) }
            } finally {
              form.update { copy(busy = false, qrPayload = null) }
              qrDiscovery?.stop(); qrDiscovery = null; qrJob = null; connections.close()
            }
          }
        }
      },
      onConnection = { connections.trySend(it) },
      onError = { code ->
        form.update { copy(console = string(R.string.log_discovery_failed, code?.toString() ?: "")) }
        stopQrPairing()
      })
    qrDiscovery = discovery
    form.update { copy(qrPayload = qr.payload, console = string(R.string.log_qr_waiting)) }
    try { discovery.start() } catch (error: Exception) {
      form.update { copy(console = error.message ?: string(R.string.unknown_error)) }
      stopQrPairing()
    }
  }

  fun stopQrPairing() {
    qrJob?.cancel(); qrJob = null
    qrDiscovery?.stop(); qrDiscovery = null
    form.update { copy(qrPayload = null) }
  }

  fun disconnect() {
    viewModelScope.launch {
      shouldMirror = false
      surfaceStartJob?.cancel()
      mirrorJob?.cancel()
      scrcpy.stop()
      form.update { copy(busy = true) }
      try {
        client.disconnect()
      } finally {
        tailcat.stop()
        form.update { copy(busy = false, console = string(R.string.log_disconnected)) }
      }
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
    stopQrPairing()
    scrcpy.close()
    client.close()
    tailcat.close()
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
