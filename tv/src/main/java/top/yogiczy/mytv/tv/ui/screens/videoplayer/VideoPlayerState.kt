package top.yogiczy.mytv.tv.ui.screens.videoplayer

import android.view.SurfaceView
import android.view.TextureView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import top.yogiczy.mytv.tv.ui.screens.videoplayer.player.Media3VideoPlayer
import top.yogiczy.mytv.tv.ui.screens.videoplayer.player.VideoPlayer

@Stable
class VideoPlayerState(
    private val instance: VideoPlayer,
    private var defaultDisplayModeProvider: () -> VideoPlayerDisplayMode = { VideoPlayerDisplayMode.ORIGINAL },
) {
    /** 临时在内存中记录最后一次成功准备的直播流 URL */
    var lastPreparedUrl: String? = null
        private set

    /** 播放器是否已经被销毁（释放） */
    var isReleased = false
        private set

    /** 显示模式 */
    var displayMode by mutableStateOf(defaultDisplayModeProvider())

    /** 视频宽高比 */
    var aspectRatio by mutableFloatStateOf(16f / 9f)

    /** 错误 */
    var error by mutableStateOf<String?>(null)

    /** 正在缓冲 */
    var isBuffering by mutableStateOf(false)

    /** 正在播放 */
    var isPlaying by mutableStateOf(false)

    /** 总时长 */
    var duration by mutableLongStateOf(0L)

    /** 当前播放位置 */
    var currentPosition by mutableLongStateOf(0L)

    /** 元数据 */
    var metadata by mutableStateOf(VideoPlayer.Metadata())

    fun prepare(url: String) {
        if (isReleased) return
        error = null
        lastPreparedUrl = url
        instance.prepare(url)
    }

    fun play() {
        if (!isReleased) instance.play()
    }

    fun pause() {
        if (!isReleased) instance.pause()
    }

    fun seekTo(position: Long) {
        if (!isReleased) instance.seekTo(position)
    }

    fun stop() {
        if (!isReleased) instance.stop()
    }

    fun setVideoSurfaceView(surfaceView: SurfaceView) {
        if (!isReleased) instance.setVideoSurfaceView(surfaceView)
    }

    fun setVideoTextureView(textureView: TextureView) {
        if (!isReleased) instance.setVideoTextureView(textureView)
    }

    private val onReadyListeners = mutableListOf<() -> Unit>()
    private val onErrorListeners = mutableListOf<() -> Unit>()
    private val onInterruptListeners = mutableListOf<() -> Unit>()

    fun onReady(listener: () -> Unit) {
        onReadyListeners.add(listener)
    }

    fun onError(listener: () -> Unit) {
        onErrorListeners.add(listener)
    }

    fun onInterrupt(listener: () -> Unit) {
        onInterruptListeners.add(listener)
    }

    fun initialize() {
        if (isReleased) return
        instance.initialize()
        instance.onResolution { width, height ->
            if (width > 0 && height > 0) aspectRatio = width.toFloat() / height
        }
        instance.onError { ex ->
            error = ex?.let { "${it.errorCodeName}(${it.errorCode})" }
                ?.apply { onErrorListeners.forEach { it.invoke() } }
        }
        instance.onReady {
            onReadyListeners.forEach { it.invoke() }
            error = null
            displayMode = defaultDisplayModeProvider()
        }
        instance.onBuffering {
            isBuffering = it
            if (it) error = null
        }
        instance.onPrepared { }
        instance.onIsPlayingChanged { isPlaying = it }
        instance.onDurationChanged { duration = it }
        instance.onCurrentPositionChanged { currentPosition = it }
        instance.onMetadata { metadata = it }
        instance.onInterrupt { onInterruptListeners.forEach { it.invoke() } }
    }

    fun release() {
        // 防止重复释放引发底层 C++ 崩溃
        if (isReleased) return
        isReleased = true
        onReadyListeners.clear()
        onErrorListeners.clear()
        onInterruptListeners.clear()
        instance.release()
    }
}

@Composable
fun rememberVideoPlayerState(
    defaultDisplayModeProvider: () -> VideoPlayerDisplayMode = { VideoPlayerDisplayMode.ORIGINAL },
): VideoPlayerState {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val coroutineScope = rememberCoroutineScope()
    
    // 用于在后台释放时，临时记住需要恢复的直播流 URL
    var savedUrl by remember { mutableStateOf<String?>(null) }
    
    // 触发重建播放器的“钥匙”。Key改变时，Compose 会自动丢弃旧 State，创建新 State
    var playerRecreateKey by remember { mutableStateOf(0) }

    // 使用 playerRecreateKey 作为 remember 的 key
    val state = remember(playerRecreateKey) {
        VideoPlayerState(
            Media3VideoPlayer(context, coroutineScope),
            defaultDisplayModeProvider,
        )
    }

    // 当 state 重新创建时，自动初始化
    DisposableEffect(playerRecreateKey) {
        state.initialize()
        
        // 如果是从后台切回来（savedUrl 有值），自动装填并播放
        if (!savedUrl.isNullOrEmpty()) {
            state.prepare(savedUrl!!)
            state.play()
            savedUrl = null // 恢复后清空
        }
        
        // 当组件卸载或 playerRecreateKey 变化时，释放当前实例
        onDispose { 
            state.release() 
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    // 【Power On / 回到前台】
                    // 仅当当前播放器已经是死亡状态（被 release 过了），才增加 Key 触发“转生”
                    // 这样可以避免 App 首次冷启动时无意义的重复创建
                    if (state.isReleased) {
                        playerRecreateKey++
                    }
                }
                Lifecycle.Event.ON_STOP -> {
                    // 【Power Off / 切到后台】
                    // 1. 记下最后的直播地址
                    if (!state.lastPreparedUrl.isNullOrEmpty()) {
                        savedUrl = state.lastPreparedUrl
                    }
                    // 2. 彻底释放 Media3，断开连接，归还系统硬解资源！
                    state.release()
                }
                else -> {}
            }
        }

        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    return state
}

enum class VideoPlayerDisplayMode(
    val label: String,
    val value: Int,
) {
    ORIGINAL("原始", 0),
    FILL("填充", 1),
    CROP("裁剪", 2),
    FOUR_THREE("4:3", 3),
    SIXTEEN_NINE("16:9", 4),
    WIDE("2.35:1", 5);

    companion object {
        fun fromValue(value: Int): VideoPlayerDisplayMode {
            return entries.firstOrNull { it.value == value } ?: ORIGINAL
        }
    }
}
