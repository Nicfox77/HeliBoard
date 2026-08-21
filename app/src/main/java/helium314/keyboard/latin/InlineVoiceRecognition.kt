// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.inputmethodservice.InputMethodService
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognitionService
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * Drives an installed Offline Voice Input RecognitionService without leaving HeliBoard.
 *
 * Partial hypotheses are kept in the editor as composing text so a newer hypothesis
 * replaces the previous one instead of being appended. The final result is then
 * finished/committed in place. Tapping the microphone while recognition is active
 * requests an immediate stop/finalize.
 */
object InlineVoiceRecognition {
    private const val TAG = "InlineVoiceRecognition"
    private const val TEST_PACKAGE = "dev.notune.transcribe.unifiedtest"
    private const val RELEASE_PACKAGE = "dev.notune.transcribe"

    private val mainHandler = Handler(Looper.getMainLooper())

    private var recognizer: SpeechRecognizer? = null
    private var owner: InputMethodService? = null
    private var active = false
    private var hasComposingText = false
    private var lastPartial = ""
    private var leadingSpace = ""

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
            // SpeechRecognizer requires waiting for onResults/onError after stopListening.
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
            speechRecognizer.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            })
            Log.i(TAG, "Started inline recognition with $component")
            true
        } catch (t: Throwable) {
            Log.e(TAG, "Could not start inline recognition", t)
            cleanupRecognizer(keepComposingText = false)
            // We did find the preferred service, so do not unexpectedly switch IMEs.
            true
        }
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) = Unit
        override fun onBeginningOfSpeech() = Unit
        override fun onRmsChanged(rmsdB: Float) = Unit
        override fun onBufferReceived(buffer: ByteArray?) = Unit
        override fun onEndOfSpeech() = Unit

        override fun onError(error: Int) {
            Log.w(TAG, "Recognition error: $error")
            // If useful partial text already reached the editor, preserve it instead
            // of deleting the user's dictation just because finalization errored.
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
            } else if (connection != null && hasComposingText) {
                connection.finishComposingText()
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

    private fun firstResult(bundle: Bundle?): String? =
        bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()

    private fun cleanupRecognizer(keepComposingText: Boolean) {
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
