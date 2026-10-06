package com.conversationalai.agent.asr

import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.speech.RecognitionListener
import android.speech.RecognitionSupport
import android.speech.RecognitionSupportCallback
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Platform (OS) ASR behind [AsrEngine], for TESTING against the owned sherpa-onnx engine.
 *
 * Uses the on-device recognizer only (`createOnDeviceSpeechRecognizer`; the system processes
 * audio locally) and feeds it the loop's captured utterance through a pipe
 * (`RecognizerIntent.EXTRA_AUDIO_SOURCE`, Android 13+), so VAD endpointing, capture, and the rest
 * of the pipeline are identical for both engines — only the recognizer differs. Whether a
 * language can run on-device is checked with `checkRecognitionSupport` before the engine is
 * selected; a missing language pack is reported, never silently sent elsewhere.
 *
 * Not the product path: the offline claim rests on the owned engine (`OfflineAsr`).
 */
class PlatformAsr(private val context: Context) : AsrEngine {

    data class Availability(
        val onDeviceRecognizer: Boolean,
        val languageInstalled: Boolean,
        val installedLanguages: List<String>,
        val supportedLanguages: List<String>,
        val detail: String,
    ) {
        val usable: Boolean get() = onDeviceRecognizer && languageInstalled
    }

    @Volatile var language: String = "ko"
        private set

    private val main = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null   // main thread only

    override fun name(): String = "android SpeechRecognizer (on-device, lang=${PlatformAsrLogic.bcp47(language)})"

    fun setLanguage(lang: String) {
        language = lang
    }

    /** Blocking (worker thread). On-device recognizer present + language pack installed? */
    fun checkAvailability(timeoutMs: Long = 8_000L): Availability {
        if (!SpeechRecognizer.isOnDeviceRecognitionAvailable(context)) {
            return Availability(false, false, emptyList(), emptyList(), "no on-device recognizer on this device")
        }
        val latch = CountDownLatch(1)
        var support: RecognitionSupport? = null
        var error: Int? = null
        main.post {
            runCatching {
                ensureRecognizer().checkRecognitionSupport(
                    recognizeIntent(null),
                    context.mainExecutor,
                    object : RecognitionSupportCallback {
                        override fun onSupportResult(recognitionSupport: RecognitionSupport) {
                            support = recognitionSupport
                            latch.countDown()
                        }
                        override fun onError(e: Int) {
                            error = e
                            latch.countDown()
                        }
                    },
                )
            }.onFailure { e ->
                Log.w(TAG, "checkRecognitionSupport failed: ${e.message}")
                latch.countDown()
            }
        }
        if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS)) {
            return Availability(true, false, emptyList(), emptyList(), "recognition support check timed out")
        }
        val s = support ?: return Availability(
            true, false, emptyList(), emptyList(),
            "support check error: " + (error?.let { PlatformAsrLogic.errorText(it) } ?: "unknown"),
        )
        val installed = s.installedOnDeviceLanguages
        val supported = s.supportedOnDeviceLanguages
        val ok = PlatformAsrLogic.languageInstalled(language, installed)
        val detail = if (ok) {
            "on-device ${PlatformAsrLogic.bcp47(language)} installed"
        } else {
            "language pack ${PlatformAsrLogic.bcp47(language)} not installed on device " +
                "(installed: ${installed.joinToString().ifEmpty { "none" }}; downloadable: " +
                "${supported.joinToString().ifEmpty { "none" }})"
        }
        return Availability(true, ok, installed, supported, detail)
    }

    /** Blocking; must NOT be called on the main thread (the recognizer needs it to deliver). */
    override fun transcribe(samples: FloatArray, sampleRate: Int): String {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            Log.w(TAG, "transcribe called on the main thread; refusing (would deadlock)")
            return ""
        }
        val pipe = ParcelFileDescriptor.createPipe()
        val readEnd = pipe[0]
        val writeEnd = pipe[1]
        val bytes = PlatformAsrLogic.pcm16Bytes(samples)
        // Stream the utterance in; closing the write end is the recognizer's end-of-audio.
        val writer = Thread({
            runCatching {
                ParcelFileDescriptor.AutoCloseOutputStream(writeEnd).use { out ->
                    var off = 0
                    while (off < bytes.size) {
                        val n = minOf(CHUNK_BYTES, bytes.size - off)
                        out.write(bytes, off, n)
                        off += n
                    }
                }
            }.onFailure { Log.w(TAG, "audio pipe write failed: ${it.message}") }
        }, "platform-asr-pipe")

        val latch = CountDownLatch(1)
        var text = ""
        var errorCode: Int? = null
        val segments = StringBuilder()
        var lastPartial = ""
        val listener = object : RecognitionListener {
            override fun onResults(results: Bundle?) {
                // Observed on-device (Google SODA, PFD audio source, 2026-10-06): onResults arrives
                // with an EMPTY bundle and the final hypothesis is the last partial. Use it.
                text = PlatformAsrLogic.pickResult(results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION))
                    .ifEmpty { lastPartial }
                latch.countDown()
            }
            // Continuous-domain recognizers (the on-device SODA engine with a file-descriptor
            // source) deliver text per segment and then end the session instead of onResults.
            override fun onSegmentResults(segmentResults: Bundle) {
                val seg = PlatformAsrLogic.pickResult(segmentResults.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION))
                if (seg.isNotEmpty()) segments.append(seg).append(' ')
            }
            override fun onEndOfSegmentedSession() {
                if (text.isEmpty()) text = segments.toString().trim().ifEmpty { lastPartial }
                latch.countDown()
            }
            override fun onError(error: Int) {
                errorCode = error
                latch.countDown()
            }
            override fun onReadyForSpeech(params: Bundle?) = Unit
            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() = Unit
            override fun onPartialResults(partialResults: Bundle?) {
                val p = PlatformAsrLogic.pickResult(partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION))
                if (p.isNotEmpty()) lastPartial = p
            }
            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        }
        main.post {
            runCatching {
                val r = ensureRecognizer()
                r.setRecognitionListener(listener)
                r.startListening(recognizeIntent(readEnd, sampleRate))
                // The pipe buffers ~2 s of 16 kHz PCM16 before blocking; the service drains it
                // once it starts reading, so writing can begin right away.
                writer.start()
            }.onFailure { e ->
                Log.w(TAG, "startListening failed: ${e.message}")
                errorCode = SpeechRecognizer.ERROR_CLIENT
                latch.countDown()
            }
        }
        val audioMs = samples.size * 1000L / sampleRate.coerceAtLeast(1)
        val finished = latch.await(audioMs + RESULT_GRACE_MS, TimeUnit.MILLISECONDS)
        if (!finished) {
            Log.w(TAG, "platform ASR timed out after ${audioMs + RESULT_GRACE_MS} ms")
            main.post { runCatching { recognizer?.cancel() } }
        }
        // The writer may not have been started at all (error before onReadyForSpeech).
        if (writer.state == Thread.State.NEW) runCatching { writeEnd.close() }
        runCatching { readEnd.close() }
        errorCode?.let { code ->
            if (PlatformAsrLogic.isNoResult(code)) Log.i(TAG, "platform ASR: ${PlatformAsrLogic.errorText(code)}")
            else Log.w(TAG, "platform ASR error: ${PlatformAsrLogic.errorText(code)}")
        }
        return text
    }

    fun release() {
        main.post {
            recognizer?.destroy()
            recognizer = null
        }
    }

    private fun ensureRecognizer(): SpeechRecognizer =
        recognizer ?: SpeechRecognizer.createOnDeviceSpeechRecognizer(context).also { recognizer = it }

    private fun recognizeIntent(audio: ParcelFileDescriptor?, sampleRate: Int = 16_000): Intent =
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, PlatformAsrLogic.bcp47(language))
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            if (audio != null) {
                putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, audio)
                putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
                putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
                putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, sampleRate)
            }
        }

    companion object {
        private const val TAG = "PlatformAsr"
        private const val CHUNK_BYTES = 8 * 1024
        /** Extra wait beyond the audio's own duration for the recognizer to finalize. */
        private const val RESULT_GRACE_MS = 8_000L
    }
}
