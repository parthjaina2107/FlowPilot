package com.flowpilot.util

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.speech.tts.TextToSpeech
import android.util.Log
import java.util.Locale

/**
 * FeedbackManager provides unified voice (Text-to-Speech) and haptic feedback
 * across FlowPilot's recording, matching, replay, and security checkpoints.
 */
object FeedbackManager : TextToSpeech.OnInitListener {
    private const val TAG = "FeedbackManager"

    private var tts: TextToSpeech? = null
    private var isTtsReady = false
    private var pendingSpeech: String? = null
    private var vibrator: Vibrator? = null

    fun init(context: Context) {
        val appContext = context.applicationContext
        if (tts == null) {
            tts = TextToSpeech(appContext, this)
        }
        if (vibrator == null) {
            vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = appContext.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                vm?.defaultVibrator ?: (appContext.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator)
            } else {
                @Suppress("DEPRECATION")
                appContext.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val result = tts?.setLanguage(Locale.US)
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                Log.w(TAG, "TTS Language not supported")
            } else {
                isTtsReady = true
                Log.i(TAG, "TTS engine initialized successfully")
                pendingSpeech?.let {
                    speak(it)
                    pendingSpeech = null
                }
            }
        } else {
            Log.e(TAG, "TTS initialization failed with status: $status")
        }
    }

    fun speak(text: String, queueMode: Int = TextToSpeech.QUEUE_FLUSH) {
        if (!isTtsReady) {
            pendingSpeech = text
            Log.d(TAG, "TTS not ready yet, queued: '$text'")
            return
        }
        Log.i(TAG, "🗣️ Speaking: '$text'")
        tts?.speak(text, queueMode, null, "flowpilot_${System.currentTimeMillis()}")
    }

    fun stopSpeaking() {
        tts?.stop()
    }

    /** Double pulse for security/auth checkpoint (T11) */
    fun vibrateAuthWarning() {
        vibratePattern(longArrayOf(0, 150, 100, 250))
    }

    /** Sharp alert pulse for stuck state (T10) */
    fun vibrateAlert() {
        vibratePattern(longArrayOf(0, 300, 150, 300))
    }

    /** Subtle tap for step or flow completion */
    fun vibrateSuccess() {
        vibratePattern(longArrayOf(0, 100))
    }

    private fun vibratePattern(pattern: LongArray) {
        try {
            val vib = vibrator ?: return
            if (!vib.hasVibrator()) return

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vib.vibrate(VibrationEffect.createWaveform(pattern, -1))
            } else {
                @Suppress("DEPRECATION")
                vib.vibrate(pattern, -1)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Vibration failed: ${e.message}")
        }
    }

    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        isTtsReady = false
    }
}
