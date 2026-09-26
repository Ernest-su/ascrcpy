package ernest.ascrcpy.scrcpy

data class ScrcpyConfig(
    val maxSize: Int = 1920,
    val videoBitRate: Int = 8_000_000,
    val maxFps: Int = 60,
) {
    init {
        require(maxSize > 0)
        require(videoBitRate > 0)
        require(maxFps > 0)
    }
}

data class VideoSize(val width: Int, val height: Int)

sealed interface ScrcpyState {
    data object Idle : ScrcpyState
    data object InstallingServer : ScrcpyState
    data object StartingServer : ScrcpyState
    data object ConnectingStreams : ScrcpyState
    data class Streaming(val videoSize: VideoSize) : ScrcpyState
    data class Failed(val cause: Throwable) : ScrcpyState
}
