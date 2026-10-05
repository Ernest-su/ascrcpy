package ernest.ascrcpy.ui.main

data class ConnectionHistoryEntry(
  val method: ConnectionMethod,
  val host: String = "",
  val port: String = "",
  val tailcatAddress: String = "",
  val pairingPort: String = "",
) {
  val key: String get() = listOf(method.name, host, port, tailcatAddress, pairingPort).joinToString("\u0000")

  val summary: String get() = when (method) {
    ConnectionMethod.TCP -> "$host:$port"
    ConnectionMethod.TAILCAT -> "$tailcatAddress → $port"
    ConnectionMethod.TAILCAT_WIRELESS -> "$tailcatAddress · $pairingPort / $port"
    else -> host.ifBlank { tailcatAddress }
  }
}

internal fun updateConnectionHistory(
  current: List<ConnectionHistoryEntry>,
  entry: ConnectionHistoryEntry,
  limitPerMethod: Int,
): List<ConnectionHistoryEntry> = limitConnectionHistory(
  listOf(entry) + current.filterNot { it.key == entry.key },
  limitPerMethod,
)

internal fun limitConnectionHistory(
  entries: List<ConnectionHistoryEntry>,
  limitPerMethod: Int,
): List<ConnectionHistoryEntry> {
  val counts = mutableMapOf<ConnectionMethod, Int>()
  return entries.filter { entry ->
    val count = counts[entry.method] ?: 0
    if (count >= limitPerMethod) false else {
      counts[entry.method] = count + 1
      true
    }
  }
}
