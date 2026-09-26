package ernest.ascrcpy.scrcpy

import ernest.ascrcpy.adb.AdbChannel
import java.nio.ByteBuffer
import java.nio.ByteOrder

class ControlMessageWriter(private val channel: AdbChannel) {
    suspend fun injectTouch(
        action: Int,
        pointerId: Long,
        x: Int,
        y: Int,
        screenSize: VideoSize,
        pressure: Float,
    ) {
        val fixedPressure = (pressure.coerceIn(0f, 1f) * 0xFFFF).toInt()
        val message = ByteBuffer.allocate(32).order(ByteOrder.BIG_ENDIAN)
            .put(TYPE_INJECT_TOUCH)
            .put(action.toByte())
            .putLong(pointerId)
            .putInt(x.coerceIn(0, screenSize.width - 1))
            .putInt(y.coerceIn(0, screenSize.height - 1))
            .putShort(screenSize.width.coerceAtMost(0xFFFF).toShort())
            .putShort(screenSize.height.coerceAtMost(0xFFFF).toShort())
            .putShort(fixedPressure.toShort())
            .putInt(0)
            .putInt(0)
            .array()
        channel.write(message)
    }

    suspend fun injectKey(action: Int, keyCode: Int, repeat: Int = 0, metaState: Int = 0) {
        val message = ByteBuffer.allocate(14).order(ByteOrder.BIG_ENDIAN)
            .put(TYPE_INJECT_KEYCODE)
            .put(action.toByte())
            .putInt(keyCode)
            .putInt(repeat)
            .putInt(metaState)
            .array()
        channel.write(message)
    }

    private companion object {
        const val TYPE_INJECT_KEYCODE: Byte = 0
        const val TYPE_INJECT_TOUCH: Byte = 2
    }
}
