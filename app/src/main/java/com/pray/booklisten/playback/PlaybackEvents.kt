package com.pray.booklisten.playback

import kotlinx.coroutines.flow.MutableSharedFlow

object PlaybackEvents {
    val messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    fun report(message: String) { messages.tryEmit(message) }
}
