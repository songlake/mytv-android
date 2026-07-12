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
    /** 核心：记录最后一次准备的播放地址，用于从后台无损恢复 */
    var lastPreparedUrl: String? = null
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
        error = null
        lastPreparedUrl = url // 拦截并记录当前的播放地址
        instance.prepare(url)
    }

    fun play() {
        instance.play()
    }

    fun pause() {
        instance.pause()
    }

    fun seekTo(position: Long) {
        instance.seekTo(position)
    }

    fun stop() {
        instance.stop()
    }

    fun setVideoSurfaceView(surfaceView: SurfaceView) {
        instance.setVideoSurfaceView(surfaceView)
    }

    fun setVideoTextureView(textureView: TextureView) {
        instance.setVideoTextureView(textureView)
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

    // 整个生命周期内只创建一个稳定的 state，绝对不销毁重建，杜绝闭包引用丢失和黑屏
    val state = remember {
        VideoPlayerState(
            Media3VideoPlayer(context, coroutineScope),
            defaultDisplayModeProvider,
        )
    }
    
    // 用于在后台息屏时，记住需要恢复的直播流 URL
    var savedUrl by remember { mutableStateOf<String?>(null) }

    DisposableEffect(Unit) {
        state.initialize()
        onDispose { state.release() }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    // 【Power On 唤醒 / 从后台切换回前台】
                    if (savedUrl != null) {
                        // 因为底层的播放器引擎还是同一个，Surface 依然牢牢绑定着，直接 prepare 就能重新出画！
                        state.prepare(savedUrl!!)
                        state.play()
                        savedUrl = null 
                    } else {
                        state.play()
                    }
                }
                Lifecycle.Event.ON_STOP -> {
                    // 【Power Off 息屏 / 按 Home 键切到后台】
                    if (!state.lastPreparedUrl.isNullOrEmpty()) {
                        savedUrl = state.lastPreparedUrl
                    }
                    
                    // 【核心修复：绝对不能调 release()，改用 stop()】
                    // 1. stop() 会停止下载（不会偷偷跑流量）。
                    // 2. stop() 会让引擎进入 IDLE 状态，彻底释放硬件视频解码器（YouTube 再也不会变暗）。
                    // 3. stop() 保留了引擎外壳和 Surface 绑定，为下次秒开画面做准备。
                    state.stop()
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
