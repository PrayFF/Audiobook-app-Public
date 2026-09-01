package com.pray.booklisten.playback

import android.content.ComponentName
import android.content.Context
import androidx.core.content.ContextCompat
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

data class PlaybackUiState(
    val connected: Boolean = false,
    val isPlaying: Boolean = false,
    val blockIndex: Int = 0,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val speed: Float = 1f,
    val bookId: String = "",
    val chapterIndex: Int = 0,
)

class PlaybackConnection(context: Context, private val scope: CoroutineScope) {
    private var controller: MediaController? = null
    private var ticker: Job? = null
    private val future = MediaController.Builder(
        context,
        SessionToken(context, ComponentName(context, PlaybackService::class.java)),
    ).buildAsync()
    private val _state = MutableStateFlow(PlaybackUiState())
    val state: StateFlow<PlaybackUiState> = _state

    init {
        future.addListener({
            runCatching { future.get() }.onSuccess { mediaController ->
                controller = mediaController
                mediaController.addListener(object : Player.Listener {
                    override fun onEvents(player: Player, events: Player.Events) = refresh()
                })
                _state.value = _state.value.copy(connected = true)
                ticker = scope.launch(Dispatchers.Main.immediate) {
                    while (true) {
                        refresh()
                        delay(500)
                    }
                }
            }
        }, ContextCompat.getMainExecutor(context))
    }

    fun toggle() = controller?.let { if (it.isPlaying) it.pause() else it.play() }
    fun setSpeed(speed: Float) { controller?.setPlaybackSpeed(speed.coerceIn(0.75f, 2.5f)); refresh() }

    fun seekToBlock(blockIndex: Int, fractionInBlock: Float): Boolean {
        val player = controller ?: return false
        val mediaIndex = (0 until player.mediaItemCount).firstOrNull { index ->
            player.getMediaItemAt(index).mediaId.substringAfterLast(':').toIntOrNull() == blockIndex
        } ?: return false
        val position = if (mediaIndex == player.currentMediaItemIndex && player.duration > 0) {
            (player.duration * fractionInBlock.coerceIn(0f, 1f)).toLong()
        } else 0L
        player.seekTo(mediaIndex, position)
        player.play()
        refresh()
        return true
    }

    fun seekBy(deltaMs: Long) {
        val player = controller ?: return
        if (deltaMs < 0 && player.currentPosition + deltaMs < 0 && player.hasPreviousMediaItem()) {
            val remainder = -(player.currentPosition + deltaMs)
            player.seekToPreviousMediaItem()
            player.seekTo((player.duration - remainder).coerceAtLeast(0))
        } else if (deltaMs > 0 && player.duration > 0 && player.currentPosition + deltaMs > player.duration && player.hasNextMediaItem()) {
            val remainder = player.currentPosition + deltaMs - player.duration
            player.seekToNextMediaItem()
            player.seekTo(remainder)
        } else {
            player.seekTo((player.currentPosition + deltaMs).coerceAtLeast(0))
        }
        refresh()
    }

    private fun refresh() {
        val player = controller ?: return
        val mediaParts = player.currentMediaItem?.mediaId?.split(':').orEmpty()
        _state.value = PlaybackUiState(
            connected = true,
            isPlaying = player.isPlaying,
            blockIndex = mediaParts.getOrNull(2)?.toIntOrNull()
                ?: player.currentMediaItemIndex.coerceAtLeast(0),
            positionMs = player.currentPosition.coerceAtLeast(0),
            durationMs = player.duration.coerceAtLeast(0),
            speed = player.playbackParameters.speed,
            bookId = mediaParts.getOrNull(0).orEmpty(),
            chapterIndex = mediaParts.getOrNull(1)?.toIntOrNull() ?: 0,
        )
    }

    fun release() {
        ticker?.cancel()
        controller?.release()
        if (!future.isDone) future.cancel(true)
    }
}
