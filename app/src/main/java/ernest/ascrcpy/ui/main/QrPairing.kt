package ernest.ascrcpy.ui.main

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import java.security.SecureRandom
import java.util.ArrayDeque
import kotlinx.coroutines.channels.ReceiveChannel

/** One QR pairing attempt. The QR secret is valid only for this discovery session. */
internal class QrPairing {
    private val alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789"
    private val random = SecureRandom()
    val serviceName = "studio-${randomText(10)}"
    val password = randomText(16)
    val payload = "WIFI:T:ADB;S:$serviceName;P:$password;;"

    private fun randomText(length: Int) = buildString {
        repeat(length) { append(alphabet[random.nextInt(alphabet.length)]) }
    }
}

internal fun isConnectionServiceForGuid(serviceName: String, guid: String): Boolean =
    guid.isNotBlank() && serviceName.contains(guid, ignoreCase = true)

internal suspend fun <T> firstReachableWirelessService(
    services: ReceiveChannel<T>,
    matches: (T) -> Boolean,
    isReachable: suspend (T) -> Boolean,
): T {
    while (true) {
        val candidate = services.receive()
        if (matches(candidate) && isReachable(candidate)) return candidate
    }
}

internal fun String.isTailscaleAddress(): Boolean {
    val normalized = trim().removePrefix("[").removeSuffix("]").lowercase()
    if (normalized.startsWith("fd7a:115c:a1e0:")) return true
    val octets = normalized.split('.').mapNotNull(String::toIntOrNull)
    return octets.size == 4 && octets.all { it in 0..255 } &&
        octets[0] == 100 && octets[1] in 64..127
}

/** Resolves an optional QR pairing service and available wireless ADB connection services. */
internal class WirelessAdbDiscovery(
    context: Context,
    private val pairingServiceName: String?,
    private val onPairing: (ResolvedService) -> Unit,
    private val onConnection: (ResolvedService) -> Unit,
    private val onError: (Int?) -> Unit,
) {
    data class ResolvedService(val name: String, val host: String, val port: Int)

    private val nsd = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private val multicastLock = wifi.createMulticastLock("adb-qr-pairing").apply { setReferenceCounted(false) }
    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    private val listeners = mutableListOf<NsdManager.DiscoveryListener>()
    private val pending = ArrayDeque<Pair<String, NsdServiceInfo>>()
    private val seen = mutableSetOf<String>()
    private var resolving = false
    private var active = false

    fun start() {
        if (active) return
        active = true
        multicastLock.acquire()
        if (pairingServiceName != null) discover(PAIRING_TYPE)
        discover(CONNECTION_TYPE)
    }

    fun stop() {
        if (!active) return
        active = false
        pending.clear()
        listeners.forEach { listener ->
            try { nsd.stopServiceDiscovery(listener) } catch (_: IllegalArgumentException) { }
        }
        listeners.clear()
        if (multicastLock.isHeld) multicastLock.release()
    }

    private fun discover(type: String) {
        if (!active) return
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) = Unit
            override fun onDiscoveryStopped(serviceType: String) = Unit
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                main.post { if (active) onError(errorCode) }
            }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                main.post { seen.remove("$type/${serviceInfo.serviceName}") }
            }
            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                main.post {
                    if (!active) return@post
                    if (type == PAIRING_TYPE && serviceInfo.serviceName != pairingServiceName) return@post
                    val key = "$type/${serviceInfo.serviceName}"
                    if (seen.add(key)) {
                        pending.add(key to serviceInfo)
                        resolveNext()
                    }
                }
            }
        }
        listeners.add(listener)
        try {
            nsd.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (error: Exception) {
            if (active) onError(null)
        }
    }

    private fun resolveNext() {
        if (!active || resolving || pending.isEmpty()) return
        val (key, service) = pending.removeFirst()
        resolving = true
        try {
            @Suppress("DEPRECATION")
            nsd.resolveService(service, object : NsdManager.ResolveListener {
                override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                    main.post {
                        resolving = false
                        seen.remove(key)
                        resolveNext()
                    }
                }
                override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                    main.post {
                        resolving = false
                        if (active && serviceInfo.host != null && serviceInfo.port in 1..65535) {
                            val resolved = ResolvedService(
                                serviceInfo.serviceName, serviceInfo.host.hostAddress ?: return@post,
                                serviceInfo.port,
                            )
                            if (key.startsWith(PAIRING_TYPE)) onPairing(resolved)
                            else onConnection(resolved)
                        }
                        resolveNext()
                    }
                }
            })
        } catch (_: Exception) {
            resolving = false
            seen.remove(key)
            resolveNext()
        }
    }

    private companion object {
        const val PAIRING_TYPE = "_adb-tls-pairing._tcp."
        const val CONNECTION_TYPE = "_adb-tls-connect._tcp."
    }
}
