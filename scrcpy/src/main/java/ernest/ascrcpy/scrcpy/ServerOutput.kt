package ernest.ascrcpy.scrcpy

import ernest.ascrcpy.adb.AdbChannel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Consume the owned server's output with bounded memory, without logging or retaining device data. */
internal suspend fun drainServerOutput(channel: AdbChannel) {
    val buffer = ByteArray(8192)
    while (true) {
        currentCoroutineContext().ensureActive()
        if (channel.read(buffer, 0, buffer.size) < 0) return
    }
}
