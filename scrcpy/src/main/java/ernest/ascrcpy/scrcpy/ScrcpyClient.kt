package ernest.ascrcpy.scrcpy

import android.view.Surface
import ernest.ascrcpy.adb.AdbChannel
import ernest.ascrcpy.adb.AdbClient
import java.io.Closeable
import java.io.InputStream
import kotlin.random.Random
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class ScrcpyClient(private val adb: AdbClient) : Closeable {
    private val mutableState = MutableStateFlow<ScrcpyState>(ScrcpyState.Idle)
    val state: StateFlow<ScrcpyState> = mutableState.asStateFlow()

    private var scope: CoroutineScope? = null
    private var videoChannel: AdbChannel? = null
    private var controlChannel: AdbChannel? = null
    private var videoJob: Job? = null
    private var serverJob: Job? = null
    private var controller: ControlMessageWriter? = null

    suspend fun start(server: InputStream, surface: Surface, config: ScrcpyConfig = ScrcpyConfig()) {
        stop()
        try {
            mutableState.value = ScrcpyState.InstallingServer
            adb.push(server, SERVER_PATH, 0x1A4)
            val scid = Random.nextInt(1, Int.MAX_VALUE)
            mutableState.value = ScrcpyState.StartingServer
            val command = buildString {
                append("CLASSPATH=$SERVER_PATH app_process / com.genymobile.scrcpy.Server $SERVER_VERSION ")
                append("scid=${scid.toString(16).padStart(8, '0')} tunnel_forward=true ")
                append("audio=false video_codec=h264 max_size=${config.maxSize} ")
                append("video_bit_rate=${config.videoBitRate} max_fps=${config.maxFps} ")
                append("send_device_meta=false send_dummy_byte=false cleanup=false")
            }
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            serverJob = scope!!.launch {
                runCatching { adb.shell(command) }
                    .onFailure { if (it !is kotlinx.coroutines.CancellationException) mutableState.value = ScrcpyState.Failed(it) }
            }
            mutableState.value = ScrcpyState.ConnectingStreams
            val socketName = "scrcpy_${scid.toString(16).padStart(8, '0')}"
            videoChannel = openWithRetry("localabstract:$socketName")
            controlChannel = openWithRetry("localabstract:$socketName")
            controller = ControlMessageWriter(checkNotNull(controlChannel))
            videoJob = scope!!.launch {
                runCatching {
                    VideoStreamDecoder(checkNotNull(videoChannel), surface) { videoSize ->
                        mutableState.value = ScrcpyState.Streaming(videoSize)
                    }.run()
                }.onFailure { mutableState.value = ScrcpyState.Failed(it) }
            }
        } catch (error: Throwable) {
            stopChannels()
            mutableState.value = ScrcpyState.Failed(error)
            throw error
        }
    }

    suspend fun injectTouch(action: Int, pointerId: Long, x: Int, y: Int, pressure: Float) {
        val videoSize = (state.value as? ScrcpyState.Streaming)?.videoSize ?: return
        controller?.injectTouch(action, pointerId, x, y, videoSize, pressure)
    }

    suspend fun injectKey(action: Int, keyCode: Int) {
        controller?.injectKey(action, keyCode)
    }

    fun stop() {
        stopChannels()
        mutableState.value = ScrcpyState.Idle
    }

    private fun stopChannels() {
        videoJob?.cancel()
        videoJob = null
        serverJob?.cancel()
        serverJob = null
        scope?.cancel()
        scope = null
        videoChannel?.close()
        videoChannel = null
        controlChannel?.close()
        controlChannel = null
        controller = null
    }

    private suspend fun openWithRetry(service: String): AdbChannel {
        var lastError: Throwable? = null
        repeat(40) {
            try {
                return adb.open(service)
            } catch (error: Throwable) {
                lastError = error
                delay(100)
            }
        }
        throw IllegalStateException("Timed out opening $service", lastError)
    }

    override fun close() = stop()

    private companion object {
        const val SERVER_VERSION = "4.0"
        const val SERVER_PATH = "/data/local/tmp/ascrcpy-server.jar"
    }
}
