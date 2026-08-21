// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin

import android.Manifest
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.inputmethodservice.InputMethodService
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.core.content.ContextCompat
import kotlin.math.sqrt

/**
 * Context + callback object passed to the embedded Rust engine.
 *
 * The native model loader needs normal Android Context methods such as
 * getFilesDir() and getAssets(), while the streaming worker also needs callback
 * methods for status/results. Keeping both on one ContextWrapper lets the
 * existing transcribe engine run inside HeliBoard without a second app/service.
 */
private class ParakeetNativeContext(base: Context) : ContextWrapper(base) {
    @Suppress("unused")
    fun onStatusUpdate(status: String) = InlineVoiceRecognition.onStatusUpdate(status)

    @Suppress("unused")
    fun onPartialResults(text: String) = InlineVoiceRecognition.onPartialResults(text)

    @Suppress("unused")
    fun onResults(text: String) = InlineVoiceRecognition.onResults(text)

    @Suppress("unused")
    fun onError(error: Int) = InlineVoiceRecognition.onError(error)
}

/**
 * Native Parakeet Unified dictation embedded directly in HeliBoard.
 *
 * HeliBoard owns AudioRecord and feeds 16 kHz mono PCM16 over JNI into the
 * bundled transcribe.cpp/Parakeet streaming engine. No second app,
 * RecognitionService, SpeechRecognizer or cross-package microphone permission
 * path is involved.
 */
object InlineVoiceRecognition {
    private const val TAG = "InlineVoiceRecognition"
    private const val SAMPLE_RATE = 16_000

    private const val MIN_SPEECH_LEVEL = 0.12f
    private const val SPEECH_MARGIN = 0.08f
    private const val SILENCE_MS = 1_500L
    private const val NO_SPEECH_TIMEOUT_MS = 7_000L
    private const val MAX_SESSION_MS = 60_000L

    private val mainHandler = Handler(Looper.getMainLooper())

    private var owner: InputMethodService? = null
    private var active = false
    private var finishing = false
    private var hasComposingText = false
    private var lastPartial = ""
    private var leadingSpace = ""

    @Volatile
    private var capturingAudio = false
    private var audioRecord: AudioRecord? = null
    private var audioThread: Thread? = null

    @Volatile
    private var speechStarted = false
    @Volatile
    private var lastVoiceAt = 0L
    @Volatile
    private var noiseFloor = 0.0f
    private var startedAt = 0L

    private var nativeInitialized = false
    private var nativeLoadError: Throwable? = null
    private var nativeContext: ParakeetNativeContext? = null

    init {
        try {
            System.loadLibrary("c++_shared")
            System.loadLibrary("android_transcribe_app")
        } catch (t: Throwable) {
            nativeLoadError = t
            Log.e(TAG, "Failed to load embedded Parakeet native libraries", t)
        }
    }

    private external fun initNative(target: ParakeetNativeContext)
    private external fun startNative(target: ParakeetNativeContext)
    private external fun feedAudioNative(samples: ShortArray, length: Int)
    private external fun finishNative()
    private external fun cancelNative()
    private external fun destroyNative()

    @JvmStatic
    fun isAvailable(@Suppress("UNUSED_PARAMETER") context: Context): Boolean = nativeLoadError == null

    /** Toggle embedded dictation. The second tap finalizes immediately. */
    @JvmStatic
    fun startOrToggle(ime: InputMethodService): Boolean {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { startOrToggle(ime) }
            return true
        }

        nativeLoadError?.let {
            Toast.makeText(ime, "Parakeet native engine unavailable", Toast.LENGTH_SHORT).show()
            Log.e(TAG, "Native engine unavailable", it)
            return true
        }

        if (ContextCompat.checkSelfPermission(ime, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            ime.startActivity(Intent(ime, VoicePermissionActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
            return true
        }

        ensureNativeInitialized(ime)

        if (active) {
            finishSession()
            return true
        }

        cleanupState(cancelNativeSession = true, clearComposing = false)
        owner = ime
        active = true
        finishing = false
        hasComposingText = false
        lastPartial = ""

        val connection = ime.currentInputConnection
        connection?.finishComposingText()
        val before = connection?.getTextBeforeCursor(1, 0)?.toString().orEmpty()
        leadingSpace = if (before.isNotEmpty() && !before.last().isWhitespace()) " " else ""

        speechStarted = false
        noiseFloor = 0.0f
        startedAt = android.os.SystemClock.elapsedRealtime()
        lastVoiceAt = startedAt

        return try {
            startNative(requireNotNull(nativeContext))
            startAudioCapture()
            mainHandler.post(endpointCheck)
            showStatus("Voice: listening")
            Log.i(TAG, "Started embedded Parakeet streaming session")
            true
        } catch (t: Throwable) {
            Log.e(TAG, "Could not start embedded Parakeet", t)
            showStatus("Voice start failed: ${t.javaClass.simpleName}")
            cleanupState(cancelNativeSession = true, clearComposing = true)
            true
        }
    }

    private fun ensureNativeInitialized(context: Context) {
        if (nativeContext == null) {
            nativeContext = ParakeetNativeContext(context.applicationContext)
        }
        if (nativeInitialized) return
        initNative(requireNotNull(nativeContext))
        nativeInitialized = true
    }

    private fun startAudioCapture() {
        stopAudioCapture()

        val minBytes = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        val bufferBytes = maxOf(minBytes.takeIf { it > 0 } ?: 0, SAMPLE_RATE * 2)

        val record = AudioRecord.Builder()
            .setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                    .build(),
            )
            .setBufferSizeInBytes(bufferBytes)
            .build()

        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            throw IllegalStateException("AudioRecord failed to initialize")
        }

        audioRecord = record
        capturingAudio = true
        record.startRecording()
        if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            stopAudioCapture()
            throw IllegalStateException("AudioRecord failed to start")
        }

        audioThread = Thread({
            val samples = ShortArray(1024)
            try {
                while (capturingAudio) {
                    val current = audioRecord ?: break
                    val count = current.read(samples, 0, samples.size, AudioRecord.READ_BLOCKING)
                    if (count <= 0) {
                        if (count < 0 && capturingAudio) {
                            Log.e(TAG, "AudioRecord read failed: $count")
                        }
                        break
                    }
                    updateVad(samples, count)
                    feedAudioNative(samples, count)
                }
            } catch (t: Throwable) {
                if (capturingAudio) {
                    Log.e(TAG, "Embedded audio capture failed", t)
                    mainHandler.post {
                        showStatus("Voice audio error")
                        cleanupState(cancelNativeSession = true, clearComposing = false)
                    }
                }
            }
        }, "heliboard-parakeet-capture").also { it.start() }
    }

    private fun updateVad(samples: ShortArray, count: Int) {
        var energy = 0.0
        for (i in 0 until count) {
            val value = samples[i].toDouble() / 32768.0
            energy += value * value
        }
        val rms = sqrt(energy / count.coerceAtLeast(1)).toFloat()
        val level = (rms * 6.0f).coerceIn(0.0f, 1.0f)
        val floor = noiseFloor
        val speech = level > MIN_SPEECH_LEVEL && level > floor + SPEECH_MARGIN

        if (speech) {
            lastVoiceAt = android.os.SystemClock.elapsedRealtime()
            if (!speechStarted) {
                speechStarted = true
                mainHandler.post { showStatus("Voice: listening") }
            }
        } else {
            noiseFloor = floor * 0.95f + level * 0.05f
        }
    }

    private val endpointCheck = object : Runnable {
        override fun run() {
            if (!active || finishing) return
            val now = android.os.SystemClock.elapsedRealtime()
            val elapsed = now - startedAt
            val done = (speechStarted && now - lastVoiceAt >= SILENCE_MS) ||
                (!speechStarted && elapsed >= NO_SPEECH_TIMEOUT_MS) ||
                elapsed >= MAX_SESSION_MS

            if (done) {
                if (speechStarted) {
                    finishSession()
                } else {
                    showStatus("Voice: no speech")
                    cleanupState(cancelNativeSession = true, clearComposing = false)
                }
                return
            }
            mainHandler.postDelayed(this, 100L)
        }
    }

    private fun finishSession() {
        if (!active || finishing) return
        finishing = true
        mainHandler.removeCallbacks(endpointCheck)
        stopAudioCapture()
        showStatus("Voice: finalizing…")
        finishNative()
    }

    private fun stopAudioCapture() {
        capturingAudio = false
        val record = audioRecord
        audioRecord = null
        if (record != null) {
            try { record.stop() } catch (_: Throwable) {}
            try { record.release() } catch (_: Throwable) {}
        }

        val thread = audioThread
        audioThread = null
        if (thread != null && thread != Thread.currentThread()) {
            try {
                thread.join(250)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
    }

    // --- JNI callbacks from the embedded Rust worker -------------------------

    @Suppress("unused")
    fun onStatusUpdate(status: String) {
        Log.d(TAG, "engine: $status")
        if (status.startsWith("Loading") || status.startsWith("Error")) {
            mainHandler.post { showStatus("Parakeet: $status") }
        }
    }

    @Suppress("unused")
    fun onPartialResults(text: String) {
        val partial = text.trim()
        if (partial.isEmpty() || partial == lastPartial) return
        mainHandler.post {
            val connection = owner?.currentInputConnection ?: return@post
            val value = leadingSpace + partial
            if (connection.setComposingText(value, 1)) {
                hasComposingText = true
                lastPartial = partial
            }
        }
    }

    @Suppress("unused")
    fun onResults(text: String) {
        mainHandler.post {
            val finalText = text.trim()
            val connection = owner?.currentInputConnection
            if (connection != null && finalText.isNotEmpty()) {
                val value = leadingSpace + finalText
                if (hasComposingText) {
                    connection.setComposingText(value, 1)
                    connection.finishComposingText()
                } else {
                    connection.commitText(value, 1)
                }
                showStatus("Voice: done")
            } else if (connection != null && hasComposingText) {
                connection.finishComposingText()
            }
            cleanupState(cancelNativeSession = false, clearComposing = false)
        }
    }

    @Suppress("unused")
    fun onError(error: Int) {
        mainHandler.post {
            Log.w(TAG, "Embedded recognition error: $error")
            showStatus(if (error == 7) "Voice: no match" else "Voice error $error")
            owner?.currentInputConnection?.let { if (hasComposingText) it.finishComposingText() }
            cleanupState(cancelNativeSession = false, clearComposing = false)
        }
    }

    private fun showStatus(message: String) {
        val context = owner ?: return
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    }

    private fun cleanupState(cancelNativeSession: Boolean, clearComposing: Boolean) {
        mainHandler.removeCallbacks(endpointCheck)
        stopAudioCapture()
        if (cancelNativeSession && nativeInitialized) {
            try { cancelNative() } catch (_: Throwable) {}
        }
        if (clearComposing && hasComposingText) {
            owner?.currentInputConnection?.setComposingText("", 1)
        }
        owner = null
        active = false
        finishing = false
        hasComposingText = false
        lastPartial = ""
        leadingSpace = ""
        speechStarted = false
        noiseFloor = 0.0f
    }
}
