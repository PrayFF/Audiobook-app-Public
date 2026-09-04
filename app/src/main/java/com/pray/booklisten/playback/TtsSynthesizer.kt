package com.pray.booklisten.playback

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import com.pray.booklisten.data.SpeechProsody

class TtsSynthesizer(context: Context, enginePackage: String = "") {
    private val appContext = context.applicationContext
    private val selectedEngine = enginePackage
    private var ready = CompletableDeferred<Boolean>()
    private var tts: TextToSpeech? = null
    // Remember the currently applied voice so we only touch engine.voice when it actually
    // changes — some engines (Sherpa multi-speaker models) reload the whole model on a voice
    // switch, which is slow.  Re-assigning the same voice on every block wastes seconds.
    private var appliedVoiceName: String? = null

    suspend fun synthesize(text: String, output: File, voiceName: String): File {
        output.parentFile?.mkdirs()
        if (output.exists() && output.length() > 44) {
            output.setLastModified(System.currentTimeMillis())
            return output
        }
        ensureEngine()
        check(ready.await()) { "语音引擎初始化失败，请安装并启用所选音色" }
        val engine = checkNotNull(tts)
        engine.language = Locale.SIMPLIFIED_CHINESE
        if (voiceName.isNotBlank() && voiceName != appliedVoiceName) {
            engine.voices?.firstOrNull { it.name == voiceName }?.let { engine.voice = it }
            appliedVoiceName = voiceName
        }
        val utteranceId = output.nameWithoutExtension
        return suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation {
                engine.stop()
                output.delete()
            }
            engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(id: String?) = Unit
                override fun onDone(id: String?) {
                    if (id == utteranceId && continuation.isActive) continuation.resume(output)
                }
                @Deprecated("Deprecated in Java")
                override fun onError(id: String?) = fail(id)
                override fun onError(id: String?, errorCode: Int) = fail(id)
                private fun fail(id: String?) {
                    if (id == utteranceId && continuation.isActive) continuation.resumeWithException(
                        IllegalStateException("语音合成失败")
                    )
                }
            })
            val result = engine.synthesizeToFile(SpeechProsody.prepare(text), Bundle(), output, utteranceId)
            if (result == TextToSpeech.ERROR && continuation.isActive) {
                continuation.resumeWithException(IllegalStateException("系统 TTS 拒绝了合成任务"))
            }
        }
    }

    private fun ensureEngine() {
        if (tts != null) return
        val initialization = CompletableDeferred<Boolean>()
        ready = initialization
        tts = if (selectedEngine.isBlank()) {
            TextToSpeech(appContext) { status -> initialization.complete(status == TextToSpeech.SUCCESS) }
        } else {
            TextToSpeech(appContext, { status -> initialization.complete(status == TextToSpeech.SUCCESS) }, selectedEngine)
        }
    }

    fun shutdown() {
        tts?.shutdown()
        tts = null
    }
}
