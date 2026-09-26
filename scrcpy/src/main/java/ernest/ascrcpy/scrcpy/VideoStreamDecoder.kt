package ernest.ascrcpy.scrcpy

import android.media.MediaCodec
import android.media.MediaFormat
import android.view.Surface
import ernest.ascrcpy.adb.AdbChannel
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive

internal class VideoStreamDecoder(
    private val channel: AdbChannel,
    private val surface: Surface,
    private val onVideoSize: (VideoSize) -> Unit,
) {
    private var decoder: MediaCodec? = null
    private var size: VideoSize? = null

    suspend fun run() {
        val codecId = ByteBuffer.wrap(channel.readExactly(4)).order(ByteOrder.BIG_ENDIAN).int
        if (codecId != CODEC_H264) error("Unsupported scrcpy video codec: 0x${codecId.toString(16)}")
        try {
            while (currentCoroutineContext().isActive) {
                val header = channel.readExactly(12)
                if ((header[0].toInt() and 0x80) != 0) {
                    val data = ByteBuffer.wrap(header).order(ByteOrder.BIG_ENDIAN)
                    data.int
                    val newSize = VideoSize(data.int, data.int)
                    configure(newSize)
                    onVideoSize(newSize)
                    continue
                }
                val data = ByteBuffer.wrap(header).order(ByteOrder.BIG_ENDIAN)
                val ptsAndFlags = data.long
                val packetSize = data.int
                require(packetSize in 1..MAX_PACKET_SIZE) { "Invalid video packet size: $packetSize" }
                val packet = channel.readExactly(packetSize)
                queue(packet, ptsAndFlags)
                drain()
            }
        } finally {
            releaseDecoder()
        }
    }

    private fun configure(newSize: VideoSize) {
        if (size == newSize && decoder != null) return
        releaseDecoder()
        size = newSize
        decoder = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC).apply {
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, newSize.width, newSize.height)
            format.setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
            configure(format, surface, null, 0)
            start()
        }
    }

    private fun queue(packet: ByteArray, ptsAndFlags: Long) {
        val codec = checkNotNull(decoder) { "Video packet received before session metadata" }
        val index = codec.dequeueInputBuffer(INPUT_TIMEOUT_US)
        if (index < 0) return
        codec.getInputBuffer(index)!!.apply { clear(); put(packet) }
        val config = ptsAndFlags and FLAG_CONFIG != 0L
        val pts = if (config) 0L else ptsAndFlags and PTS_MASK
        codec.queueInputBuffer(index, 0, packet.size, pts,
            if (config) MediaCodec.BUFFER_FLAG_CODEC_CONFIG else 0)
    }

    private fun drain() {
        val codec = decoder ?: return
        val info = MediaCodec.BufferInfo()
        while (true) {
            val index = codec.dequeueOutputBuffer(info, 0)
            if (index < 0) return
            codec.releaseOutputBuffer(index, true)
        }
    }

    private fun releaseDecoder() {
        decoder?.runCatching { stop() }
        decoder?.release()
        decoder = null
    }

    private companion object {
        const val CODEC_H264 = 0x68323634
        const val FLAG_CONFIG = 1L shl 62
        const val FLAG_KEY_FRAME = 1L shl 61
        const val PTS_MASK = FLAG_KEY_FRAME - 1
        const val INPUT_TIMEOUT_US = 10_000L
        const val MAX_PACKET_SIZE = 16 * 1024 * 1024
    }
}

private suspend fun AdbChannel.readExactly(length: Int): ByteArray {
    val result = ByteArray(length)
    var offset = 0
    while (offset < length) {
        val count = read(result, offset, length - offset)
        if (count < 0) error("Unexpected end of scrcpy stream")
        offset += count
    }
    return result
}
