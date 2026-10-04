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
import ernest.ascrcpy.adb.AdbDevice
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
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject

enum class ConnectionMethod { TCP, TAILCAT, WIRELESS_CODE, WIRELESS_QR, USB }

data class SavedWirelessDevice(
  val guid: String,
  val name: String,
  val routeHost: String,
  val lastPort: String = "",
)

internal fun wirelessConnectionEndpoint(
  pairingHost: String,
  discoveredHost: String,
  discoveredPort: Int,
): AdbEndpoint = AdbEndpoint(
  if (pairingHost.isTailscaleAddress()) pairingHost else discoveredHost,
  discoveredPort,
)

data class MainUiState(
  val host: String = "192.168.68.59",
  val hostHistory: List<String> = emptyList(),
  val port: String = "5555",
  val tailcatAddress: String = "",
  val method: ConnectionMethod = ConnectionMethod.TCP,
  val pairingHost: String = "",
  val pairingPort: String = "",
  val pairingCode: String = "",
  val wirelessCodePaired: Boolean = false,
  val savedWirelessDevices: List<SavedWirelessDevice> = emptyList(),
  val selectedWirelessGuid: String? = null,
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
  private val savedWirelessDevices = loadSavedWirelessDevices()
  private val client: AdbClient = DefaultAdbClient.factory(application).create()
  internal val deviceClient: AdbClient get() = client
  private val scrcpy = ScrcpyClient(client)
  private val tailcat = TailcatForwarder(application)
  private val usbManager = application.getSystemService(Context.USB_SERVICE) as UsbManager
  private var qrDiscovery: WirelessAdbDiscovery? = null
  private var codeDiscovery: WirelessAdbDiscovery? = null
  private var savedDiscovery: WirelessAdbDiscovery? = null
  private var qrJob: Job? = null
  private var savedReconnectJob: Job? = null
  private val form = MutableStateFlow(MainUiState(
    host = savedHosts.firstOrNull() ?: "192.168.68.59",
    hostHistory = savedHosts,
    savedWirelessDevices = savedWirelessDevices,
  ))
  private var surface: Surface? = null
  private var mirrorJob: Job? = null
  private var surfaceStartJob: Job? = null
  private var shouldMirror = false

  init {
    restoreSavedWirelessPairing()
  }

  val uiState: StateFlow<MainUiState> = combine(form, client.state, scrcpy.state) { current, connection, scrcpyState ->
    current.copy(connectionState = connection, scrcpyState = scrcpyState)
  }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MainUiState())

  fun setHost(value: String) = form.update { copy(host = value.trim()) }

  fun setPort(value: String) = form.update { copy(port = value.filter(Char::isDigit).take(5)) }
  fun setTailcatAddress(value: String) = form.update { copy(tailcatAddress = value.trim()) }
  fun setMethod(value: ConnectionMethod) {
    stopQrPairing()
    if (value != ConnectionMethod.WIRELESS_CODE) stopSavedReconnect()
    form.update { copy(method = value, port = when (value) {
      ConnectionMethod.TCP -> if (port.isBlank()) "5555" else port
      ConnectionMethod.TAILCAT -> if (method != value) "5555" else port
      ConnectionMethod.WIRELESS_CODE -> if (method != value) "" else port
      else -> port
    }) }
  }
  fun setPairingHost(value: String) = form.update {
    copy(pairingHost = value.trim(), wirelessCodePaired = false)
  }
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
    stopSavedReconnect()
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
          if (snapshot.method == ConnectionMethod.WIRELESS_CODE) {
            snapshot.selectedWirelessGuid?.let { guid ->
              saveWirelessPairing(guid, snapshot.host, port, deviceName(device, snapshot.host))
            }
          }
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
    stopSavedReconnect()
    val snapshot = form.value
    val port = snapshot.pairingPort.toIntOrNull()
    if (snapshot.pairingHost.isBlank() || port == null || port !in 1..65535 ||
      !snapshot.pairingCode.matches(Regex("[0-9]{6}"))) {
      form.update { copy(console = string(R.string.log_invalid_pairing)) }
      return
    }
    if (form.value.busy || codeDiscovery != null) return
    val connections = Channel<WirelessAdbDiscovery.ResolvedService>(Channel.UNLIMITED)
    val discovery = WirelessAdbDiscovery(getApplication(), pairingServiceName = null,
      onPairing = {},
      onConnection = { connections.trySend(it) },
      // Pairing can still succeed when mDNS is unavailable. In that case the timeout below exposes
      // the manual connection-port fallback instead of failing the pairing operation.
      onError = {})
    codeDiscovery = discovery
    val discoveryAvailable = try {
      discovery.start()
      true
    } catch (_: Exception) {
      discovery.stop()
      false
    }
    viewModelScope.launch {
      form.update { copy(busy = true, console = string(R.string.log_pairing)) }
      try {
        val guid = client.pairWireless(AdbEndpoint(snapshot.pairingHost, port), snapshot.pairingCode)
        saveWirelessPairing(guid, snapshot.pairingHost, null)
        form.update { copy(console = string(R.string.log_finding_connection, guid)) }
        val connection = if (discoveryAvailable) awaitConnectableConnection(
          connections, guid, snapshot.pairingHost, WIRELESS_DISCOVERY_TIMEOUT_MILLIS,
        ) else null
        if (connection == null) {
          showWirelessCodeFallback(snapshot.pairingHost, "", guid)
        } else {
          saveWirelessPairing(guid, snapshot.pairingHost, connection.port)
          // Preserve an explicitly entered Tailscale address to keep that route. For ordinary
          // Wi-Fi pairing, use the address resolved with the matching connection service.
          val endpoint = wirelessConnectionEndpoint(
            snapshot.pairingHost,
            connection.host,
            connection.port,
          )
          runCatching { client.connectWireless(endpoint) }
            .onSuccess { target ->
              shouldMirror = true
              saveWirelessPairing(guid, snapshot.pairingHost, connection.port,
                deviceName(target, snapshot.pairingHost))
              form.update { copy(host = snapshot.pairingHost, port = connection.port.toString(),
                pairingCode = "", wirelessCodePaired = true,
                console = string(R.string.log_connected, target.banner)) }
              scheduleMirroring()
            }
            .onFailure { error ->
              showWirelessCodeFallback(snapshot.pairingHost, connection.port.toString(), guid,
                error.message ?: string(R.string.unknown_error))
            }
        }
      } catch (error: CancellationException) {
        throw error
      } catch (error: Exception) {
        form.update { copy(console = error.message ?: string(R.string.unknown_error)) }
      } finally {
        discovery.stop()
        if (codeDiscovery === discovery) codeDiscovery = null
        connections.close()
        form.update { copy(busy = false) }
      }
    }
  }

  private fun showWirelessCodeFallback(host: String, port: String, guid: String, reason: String? = null) {
    form.update { copy(host = host, port = port, pairingCode = "", wirelessCodePaired = true,
      console = if (reason == null) string(R.string.log_connection_not_found, guid)
      else string(R.string.log_auto_connection_failed, reason)) }
  }

  private fun restoreSavedWirelessPairing() {
    val selectedGuid = preferences.getString(SELECTED_WIRELESS_GUID_KEY, null)
    val selected = savedWirelessDevices.firstOrNull { it.guid == selectedGuid }
      ?: savedWirelessDevices.firstOrNull() ?: return
    persistSavedWirelessDevices(savedWirelessDevices, selected.guid)
    form.update { copy(method = ConnectionMethod.WIRELESS_CODE, pairingHost = selected.routeHost,
      host = selected.routeHost, port = selected.lastPort, pairingCode = "", wirelessCodePaired = true,
      selectedWirelessGuid = selected.guid,
      console = string(R.string.log_saved_wireless_available)) }
  }

  fun reconnectSavedWirelessDevice() {
    if (form.value.busy || savedDiscovery != null) return
    val selected = form.value.savedWirelessDevices.firstOrNull {
      it.guid == form.value.selectedWirelessGuid
    } ?: return
    val guid = selected.guid
    val pairingHost = selected.routeHost
    val connections = Channel<WirelessAdbDiscovery.ResolvedService>(Channel.UNLIMITED)
    val discovery = WirelessAdbDiscovery(getApplication(), pairingServiceName = null,
      onPairing = {}, onConnection = { connections.trySend(it) }, onError = {})
    savedDiscovery = discovery
    form.update { copy(busy = true, console = string(R.string.log_reconnecting_saved_wireless)) }
    val discoveryAvailable = try {
      discovery.start()
      true
    } catch (_: Exception) {
      discovery.stop()
      false
    }
    savedReconnectJob = viewModelScope.launch {
      try {
        val connection = if (discoveryAvailable) awaitConnectableConnection(
          connections, guid, pairingHost, WIRELESS_DISCOVERY_TIMEOUT_MILLIS,
        ) else null
        if (connection == null || form.value.method != ConnectionMethod.WIRELESS_CODE || form.value.connected) {
          if (connection == null && form.value.method == ConnectionMethod.WIRELESS_CODE) {
            form.update { copy(console = string(R.string.log_saved_wireless_not_found)) }
          }
          return@launch
        }
        saveWirelessPairing(guid, pairingHost, connection.port)
        form.update { copy(port = connection.port.toString(),
          console = string(R.string.log_connecting_saved_wireless)) }
        val endpoint = wirelessConnectionEndpoint(pairingHost, connection.host, connection.port)
        runCatching { client.connectWireless(endpoint) }
          .onSuccess { target ->
            shouldMirror = true
            saveWirelessPairing(guid, pairingHost, connection.port, deviceName(target, selected.name))
            form.update { copy(console = string(R.string.log_connected, target.banner)) }
            scheduleMirroring()
          }
          .onFailure { error -> form.update {
            copy(console = string(R.string.log_auto_connection_failed,
              error.message ?: string(R.string.unknown_error)))
          } }
      } catch (error: CancellationException) {
        throw error
      } finally {
        if (savedDiscovery === discovery) savedDiscovery = null
        discovery.stop()
        connections.close()
        form.update { copy(busy = false) }
        savedReconnectJob = null
      }
    }
  }

  private fun stopSavedReconnect() {
    savedReconnectJob?.cancel(); savedReconnectJob = null
    savedDiscovery?.stop(); savedDiscovery = null
  }

  private suspend fun awaitConnectableConnection(
    connections: Channel<WirelessAdbDiscovery.ResolvedService>,
    guid: String,
    pairingHost: String,
    timeoutMillis: Long,
  ): WirelessAdbDiscovery.ResolvedService? = withTimeoutOrNull(timeoutMillis) {
    firstReachableWirelessService(
      connections,
      matches = { isConnectionServiceForGuid(it.name, guid) },
    ) { candidate ->
      probeWireless(wirelessConnectionEndpoint(pairingHost, candidate.host, candidate.port))
    }
  }

  private suspend fun probeWireless(endpoint: AdbEndpoint): Boolean {
    val probe = DefaultAdbClient.factory(getApplication()).create()
    return try {
      withTimeout(10_000) { probe.connectWireless(endpoint) }
      true
    } catch (_: Exception) {
      false
    } finally {
      probe.close()
    }
  }

  private fun saveWirelessPairing(guid: String, host: String, port: Int?, name: String? = null) {
    val current = form.value.savedWirelessDevices
    val previous = current.firstOrNull { it.guid == guid }
    val device = SavedWirelessDevice(guid, name?.takeIf(String::isNotBlank) ?: previous?.name ?: host,
      host, port?.toString() ?: previous?.lastPort.orEmpty())
    val updated = (listOf(device) + current.filterNot { it.guid == guid }).take(MAX_SAVED_WIRELESS_DEVICES)
    persistSavedWirelessDevices(updated, guid)
    form.update { copy(savedWirelessDevices = updated, selectedWirelessGuid = guid) }
  }

  fun prepareWirelessPairing() {
    stopSavedReconnect()
    form.update { copy(host = "", port = "", pairingHost = "", pairingPort = "",
      pairingCode = "", wirelessCodePaired = false, selectedWirelessGuid = null, console = "") }
  }

  fun selectSavedWirelessDevice(guid: String) {
    stopSavedReconnect()
    val selected = form.value.savedWirelessDevices.firstOrNull { it.guid == guid } ?: return
    preferences.edit().putString(SELECTED_WIRELESS_GUID_KEY, guid).apply()
    form.update { copy(pairingHost = selected.routeHost, host = selected.routeHost,
      port = selected.lastPort, pairingCode = "", wirelessCodePaired = true,
      selectedWirelessGuid = guid, console = string(R.string.log_saved_wireless_available)) }
  }

  fun startQrPairing() {
    if (form.value.busy || qrDiscovery != null) return
    val qr = QrPairing()
    val connections = Channel<WirelessAdbDiscovery.ResolvedService>(Channel.UNLIMITED)
    var pairingStarted = false
    val discovery = WirelessAdbDiscovery(getApplication(), qr.serviceName,
      onPairing = { service ->
        if (!pairingStarted) {
          pairingStarted = true
          qrJob = viewModelScope.launch {
            form.update { copy(busy = true, console = string(R.string.log_pairing)) }
            try {
              val guid = client.pairWireless(AdbEndpoint(service.host, service.port), qr.password)
              saveWirelessPairing(guid, service.host, null)
              form.update { copy(console = string(R.string.log_finding_connection, guid)) }
              val connection = awaitConnectableConnection(connections, guid, service.host, 30_000)
              if (connection == null) {
                form.update { copy(method = ConnectionMethod.WIRELESS_CODE, pairingHost = service.host,
                  host = service.host, port = "", pairingCode = "", wirelessCodePaired = true,
                  console = string(R.string.log_connection_not_found, guid)) }
              } else {
                saveWirelessPairing(guid, service.host, connection.port)
                val endpoint = wirelessConnectionEndpoint(service.host, connection.host, connection.port)
                val target = client.connectWireless(endpoint)
                shouldMirror = true
                saveWirelessPairing(guid, service.host, connection.port, deviceName(target, service.host))
                form.update { copy(host = endpoint.host, port = connection.port.toString(),
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
      form.update { copy(busy = true) }
      var failure: Exception? = null
      try {
        surfaceStartJob?.cancel()
        mirrorJob?.cancel()
        scrcpy.stop()
        client.disconnect()
      } catch (error: CancellationException) {
        throw error
      } catch (error: Exception) {
        failure = error
      } finally {
        runCatching { tailcat.stop() }.onFailure { if (failure == null && it is Exception) failure = it }
        form.update { copy(busy = false, console = failure?.let {
          string(R.string.log_disconnect_failed, it.message ?: string(R.string.unknown_error))
        } ?: string(R.string.log_disconnected)) }
      }
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
    stopSavedReconnect()
    codeDiscovery?.stop(); codeDiscovery = null
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

  private fun loadSavedWirelessDevices(): List<SavedWirelessDevice> = runCatching {
    val array = JSONArray(preferences.getString(SAVED_WIRELESS_DEVICES_KEY, "[]"))
    buildList {
      for (index in 0 until array.length()) {
        val item = array.optJSONObject(index) ?: continue
        val guid = item.optString("guid").takeIf(String::isNotBlank) ?: continue
        val host = item.optString("host").takeIf(String::isNotBlank) ?: continue
        add(SavedWirelessDevice(guid, item.optString("name", host).ifBlank { host }, host,
          item.optString("port")))
      }
    }.distinctBy(SavedWirelessDevice::guid).take(MAX_SAVED_WIRELESS_DEVICES)
  }.getOrDefault(emptyList())

  private fun persistSavedWirelessDevices(devices: List<SavedWirelessDevice>, selectedGuid: String) {
    val array = JSONArray()
    devices.forEach { device -> array.put(JSONObject()
      .put("guid", device.guid)
      .put("name", device.name)
      .put("host", device.routeHost)
      .put("port", device.lastPort)) }
    preferences.edit()
      .putString(SAVED_WIRELESS_DEVICES_KEY, array.toString())
      .putString(SELECTED_WIRELESS_GUID_KEY, selectedGuid)
      .apply()
  }

  private fun deviceName(device: AdbDevice, fallback: String): String =
    sequenceOf(device.properties["model"], device.properties["ro.product.model"],
      device.properties["device"], device.properties["product"])
      .filterNotNull().firstOrNull(String::isNotBlank)?.replace('_', ' ') ?: fallback

  private fun string(@StringRes id: Int, vararg arguments: Any): String =
    getApplication<Application>().getString(id, *arguments)

  private companion object {
    const val WIRELESS_DISCOVERY_TIMEOUT_MILLIS = 30_000L
    const val SAVED_WIRELESS_DEVICES_KEY = "saved_wireless_devices_v2"
    const val SELECTED_WIRELESS_GUID_KEY = "selected_wireless_guid_v2"
    const val MAX_SAVED_WIRELESS_DEVICES = 20
    const val HOST_HISTORY_KEY = "hosts"
    const val MAX_HOST_HISTORY = 20
    const val SURFACE_SETTLE_DELAY_MILLIS = 300L
  }
}

private inline fun MutableStateFlow<MainUiState>.update(transform: MainUiState.() -> MainUiState) {
  value = value.transform()
}
