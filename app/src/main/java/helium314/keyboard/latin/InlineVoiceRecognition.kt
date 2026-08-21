// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.inputmethodservice.InputMethodService
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.speech.RecognitionListener
import android.speech.RecognitionService
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import android.widget.Toast
import androidx.core.content.ContextCompat
import java.io.OutputStream

/**
 * Drives an installed Offline Voice Input RecognitionService without leaving HeliBoard.
 *
 * On Android 13+ HeliBoard owns the microphone and passes an already-open PCM stream to
 * RecognitionService via RecognizerIntent.EXTRA_AUDIO_SOURCE. This is both lower-friction
 * and more robust for while-in-use microphone permissions: the visible/current IME owns
 * RECORD_AUDIO, while the background recognizer only consumes PCM and runs inference.
 *
 * Partial hypotheses are kept in the editor as composing text so a newer hypothesis
 * replaces the previous one instead of being appended. The final result is then
 * finished/committed in place. Tapping the microphone while recognition is active
 * closes the audio source and requests immediate finalization.
 */
object InlineVoiceRecognition {
    private const val TAG = "InlineVoiceRecognition"
    private const val TEST_PACKAGE = "dev.notune.transcribe.unifiedtest"
    private const val RELEASE_PACKAGE = "dev.notune.transcribe"
    private const val SAMPLE_RATE = 16_000

    private val mainHandler = Handler(Looper.getMainLooper())

    private var recognizer: SpeechRecognizer? = null
    private var owner: InputMethodService? = null
    private var active = false
    private var hasComposingText = false
    private var lastPartial = ""
    private var leadingSpace = ""

    @Volatile
    private var capturingAudio = false
    private var audioRecord: AudioRecord? = null
    private var audioThread: Thread? = null
    private var audioSourceRead: ParcelFileDescriptor? = null
    private var audioSourceOutput: OutputStream? = null

    @JvmStatic
    fun isAvailable(context: Context): Boolean = findOfflineRecognizer(context) != null

    /**
     * @return true when Offline Voice Input was found (or its permission flow was
     * started), false when HeliBoard should fall back to its normal voice-IME switch.
     */
    @JvmStatic
    fun startOrToggle(ime: InputMethodService): Boolean {
        val component = findOfflineRecognizer(ime) ?: return false

        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { startOrToggle(ime) }
            return true
        }

        if (ContextCompat.checkSelfPermission(ime, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            val permissionIntent = Intent(ime, VoicePermissionActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            ime.startActivity(permissionIntent)
            return true
        }

        if (active) {
            showStatus("Voice: stopping…")
            // Closing the caller-owned pipe tells an EXTRA_AUDIO_SOURCE recognizer that
            // no more audio is coming. stopListening() remains useful for the fallback
            // service-microphone path and makes manual finalization immediate.
            stopCallerAudioCapture()
            recognizer?.stopListening()
            return true
        }

        cleanupRecognizer(keepComposingText = true)
        owner = ime
        active = true
        hasComposingText = false
        lastPartial = ""

        // End any normal HeliBoard composing word first. Dictation gets its own
        // composing range so partial revisions cannot overwrite what was typed before it.
        val connection = ime.currentInputConnection
        connection?.finishComposingText()
        val before = connection?.getTextBeforeCursor(1, 0)?.toString().orEmpty()
        leadingSpace = if (before.isNotEmpty() && !before.last().isWhitespace()) " " else ""

        return try {
            val speechRecognizer = SpeechRecognizer.createSpeechRecognizer(ime, component)
            recognizer = speechRecognizer
            speechRecognizer.setRecognitionListener(listener)

            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            }

            val usingCallerAudio = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
            if (usingCallerAudio) {
                prepareCallerAudioSource(intent)
            }

            showStatus("Voice: connecting to ${component.packageName}")
            speechRecognizer.startListening(intent)

            if (usingCallerAudio) {
                startCallerAudioCapture()
                Log.i(TAG, "Started inline recognition with caller-owned AudioRecord: $component")
            } else {
                Log.i(TAG, "Started inline recognition with service microphone fallback: $component")
            }
            true
        } catch (t: Throwable) {
            Log.e(TAG, "Could not start inline recognition", t)
            showStatus("Voice start failed: ${t.javaClass.simpleName}")
            try {
                recognizer?.cancel()
            } catch (_: Throwable) {
            }
            cleanupRecognizer(keepComposingText = false)
            true
        }
    }

    /** Prepare a pipe + AudioRecord before startListening so the read FD is in the intent. */
    private fun prepareCallerAudioSource(intent: Intent) {
        stopCallerAudioCapture()

        val pipe = ParcelFileDescriptor.createPipe()
        audioSourceRead = pipe[0]
        audioSourceOutput = ParcelFileDescriptor.AutoCloseOutputStream(pipe[1])

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
            throw IllegalStateException("HeliBoard AudioRecord failed to initialize")
        }
        audioRecord = record

        intent.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, audioSourceRead)
        intent.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
        intent.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
        intent.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, SAMPLE_RATE)
    }

    /** Start microphone capture and write little-endian PCM16 into the recognizer pipe. */
    private fun startCallerAudioCapture() {
        val record = audioRecord ?: throw IllegalStateException("AudioRecord not prepared")
        val output = audioSourceOutput ?: throw IllegalStateException("Audio pipe not prepared")

        capturingAudio = true
        record.startRecording()
        if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            capturingAudio = false
            throw IllegalStateException("HeliBoard AudioRecord failed to start")
        }

        audioThread = Thread({
            val samples = ShortArray(1024)
            val bytes = ByteArray(samples.size * 2)
            try {
                while (capturingAudio) {
                    val count = record.read(samples, 0, samples.size, AudioRecord.READ_BLOCKING)
                    if (count <= 0) {
                        if (count < 0 && capturingAudio) {
                            Log.e(TAG, "HeliBoard AudioRecord read failed: $count")
                        }
                        break
                    }

                    var b = 0
                    for (i in 0 until count) {
                        val value = samples[i].toInt()
                        bytes[b++] = (value and 0xff).toByte()
                        bytes[b++] = ((value ushr 8) and 0xff).toByte()
                    }
                    output.write(bytes, 0, count * 2)
                }
            } catch (t: Throwable) {
                // A closed reader is normal when the recognizer auto-finalizes first.
                if (capturingAudio) {
                    Log.w(TAG, "Caller audio pipe ended: ${t.javaClass.simpleName}: ${t.message}")
                }
            } finally {
                try {
                    output.close()
                } catch (_: Throwable) {
                }
            }
        }, "heliboard-inline-voice-capture").also { it.start() }
    }

    private fun stopCallerAudioCapture() {
        capturingAudio = false

        val record = audioRecord
        audioRecord = null
        if (record != null) {
            try {
                record.stop()
            } catch (_: Throwable) {
            }
            try {
                record.release()
            } catch (_: Throwable) {
            }
        }

        val output = audioSourceOutput
        audioSourceOutput = null
        try {
            output?.close()
        } catch (_: Throwable) {
        }

        val readSide = audioSourceRead
        audioSourceRead = null
        try {
            readSide?.close()
        } catch (_: Throwable) {
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

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            showStatus("Voice: ready — speak now")
        }

        override fun onBeginningOfSpeech() {
            showStatus("Voice: listening")
        }

        override fun onRmsChanged(rmsdB: Float) = Unit
        override fun onBufferReceived(buffer: ByteArray?) = Unit

        override fun onEndOfSpeech() {
            showStatus("Voice: finalizing…")
            // The backend may endpoint before the caller; stop producing immediately.
            stopCallerAudioCapture()
        }

        override fun onError(error: Int) {
            Log.w(TAG, "Recognition error: $error (${errorName(error)})")
            showStatus("Voice error $error: ${errorName(error)}")
            owner?.currentInputConnection?.let { connection ->
                if (hasComposingText) connection.finishComposingText()
            }
            cleanupRecognizer(keepComposingText = true)
        }

        override fun onResults(results: Bundle?) {
            val finalText = firstResult(results)
            val connection = owner?.currentInputConnection
            if (connection != null && !finalText.isNullOrBlank()) {
                val text = leadingSpace + finalText.trim()
                if (hasComposingText) {
                    connection.setComposingText(text, 1)
                    connection.finishComposingText()
                } else {
                    connection.commitText(text, 1)
                }
                showStatus("Voice: done")
            } else if (connection != null && hasComposingText) {
                connection.finishComposingText()
                showStatus("Voice: done")
            } else {
                showStatus("Voice: no text returned")
            }
            cleanupRecognizer(keepComposingText = true)
        }

        override fun onPartialResults(partialResults: Bundle?) {
            val partial = firstResult(partialResults)?.trim().orEmpty()
            if (partial.isEmpty() || partial == lastPartial) return

            val connection = owner?.currentInputConnection ?: return
            val text = leadingSpace + partial
            if (connection.setComposingText(text, 1)) {
                hasComposingText = true
                lastPartial = partial
            }
        }

        override fun onEvent(eventType: Int, params: Bundle?) = Unit
    }

    private fun showStatus(message: String) {
        val context = owner ?: return
        mainHandler.post {
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
    }

    private fun errorName(error: Int): String = when (error) {
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "network timeout"
        SpeechRecognizer.ERROR_NETWORK -> "network"
        SpeechRecognizer.ERROR_AUDIO -> "audio"
        SpeechRecognizer.ERROR_SERVER -> "server/backend"
        SpeechRecognizer.ERROR_CLIENT -> "client"
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "speech timeout"
        SpeechRecognizer.ERROR_NO_MATCH -> "no match"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "recognizer busy"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "permission denied"
        SpeechRecognizer.ERROR_TOO_MANY_REQUESTS -> "too many requests"
        SpeechRecognizer.ERROR_SERVER_DISCONNECTED -> "service disconnected"
        SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED -> "language not supported"
        SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> "language unavailable"
        else -> "unknown"
    }

    private fun firstResult(bundle: Bundle?): String? =
        bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()

    private fun cleanupRecognizer(keepComposingText: Boolean) {
        stopCallerAudioCapture()
        if (!keepComposingText && hasComposingText) {
            owner?.currentInputConnection?.setComposingText("", 1)
        }
        try {
            recognizer?.destroy()
        } catch (_: Throwable) {
        }
        recognizer = null
        owner = null
        active = false
        hasComposingText = false
        lastPartial = ""
        leadingSpace = ""
    }

    @Suppress("DEPRECATION")
    private fun findOfflineRecognizer(context: Context): ComponentName? {
        val intent = Intent(RecognitionService.SERVICE_INTERFACE)
        val services = context.packageManager.queryIntentServices(intent, 0)
            .mapNotNull { it.serviceInfo }

        val service = services.firstOrNull { it.packageName == TEST_PACKAGE }
            ?: services.firstOrNull { it.packageName == RELEASE_PACKAGE }
            ?: services.firstOrNull {
                it.packageName.startsWith(RELEASE_PACKAGE) &&
                    it.name.endsWith("VoiceRecognitionService")
            }
            ?: return null

        return ComponentName(service.packageName, service.name)
    }
}
