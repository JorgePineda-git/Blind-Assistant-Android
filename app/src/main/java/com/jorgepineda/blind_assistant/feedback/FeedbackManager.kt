package com.jorgepineda.blind_assistant.feedback

import android.content.Context
import android.net.TetheringManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.speech.tts.TextToSpeech
import android.util.Log
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.min
import kotlin.time.Duration

/**
 * String 3: Multisensorial Feedback (TTS voice + vibrations)
 * Run in his own to avoid interfering with AI or camera
 */
class FeedbackManager(
    context: Context,
    private val sameObstacleCooldownMs: Long = 2500L,
    private val minGlobalGapMs: Long = 1000L
) : TextToSpeech.OnInitListener {

    private val appContext = context.applicationContext

    //String 3 exclusively for feedback
    private val feedbackExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    private var tts: TextToSpeech? = TextToSpeech(appContext, this)
    @Volatile
    private var isTtsReady = false

    private var lastSpokenLabel: String? = null
    private var lastSpokenTimeMs: Long = 0L

    private val vibrator: Vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val manager = appContext.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
        manager.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        appContext.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val engine = tts ?: return
            val mxResult = engine.setLanguage(Locale.forLanguageTag("es-MX"))
            if (mxResult == TextToSpeech.LANG_MISSING_DATA || mxResult == TextToSpeech.LANG_NOT_SUPPORTED) {
                engine.setLanguage(Locale.forLanguageTag("es-ES"))
            }
            engine.setSpeechRate(1.1f)
            isTtsReady = true
        } else {
            Log.e("FeedbackManager", "Error al inicializar TTS: $status")
        }
    }

    /**
     * Announce obstacle by priority checking TTS status and cooldown
     */
    fun announce(obstacles: List<FilteredObstacle>) {
        val primary = obstacles.firstOrNull() ?: return

        feedbackExecutor.execute {
            val engine = tts ?: return@execute
            if (!isTtsReady || engine.isSpeaking) return@execute

            val now = System.currentTimeMillis()
            val elapsed = now - lastSpokenTimeMs

            //Global pause to avoid spam if 2 object alternate quick
            if (elapsed < minGlobalGapMs) return@execute

            //Large cooldown if its still same object
            val isSameObstacle = primary.spanishName == lastSpokenLabel
            if (isSameObstacle && elapsed < sameObstacleCooldownMs) return@execute

            lastSpokenLabel = primary.spanishName
            lastSpokenTimeMs = now

            vibrateShort(100L)
            engine.speak(
                primary.spanishName,
                TextToSpeech.QUEUE_FLUSH,
                null,
                "obs_$now"
            )
        }
    }

    private fun vibrateShort(durationMs: Long) {
        if (!vibrator.hasVibrator()) return
        if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val effect = VibrationEffect.createOneShot(
                durationMs,
                VibrationEffect.DEFAULT_AMPLITUDE
            )
            vibrator.vibrate(effect)
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(durationMs)
        }
    }

    fun shutdown() {
        feedbackExecutor.execute {
            tts?.stop()
            tts?.shutdown()
            tts = null
            isTtsReady = false
        }
        feedbackExecutor.shutdown()
    }
}
