package fun7772ei.taptoshare.ui

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import fun7772ei.taptoshare.AppSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

object SoundEffectEngine {

    private const val SAMPLE_RATE = 44100
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val contactPcm: ShortArray by lazy { synthesizeContactShimmer() }
    private val completePcm: ShortArray by lazy { synthesizeCompleteBloom() }
    private val errorPcm: ShortArray by lazy { synthesizeErrorTone() }

    private fun shouldPlay(context: Context): Boolean {
        if (!AppSettings(context).soundEffectsEnabled) return false
        val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return true
        return am.ringerMode == AudioManager.RINGER_MODE_NORMAL
    }

    fun playContactShimmer(context: Context) {
        if (!shouldPlay(context)) return
        playPcmAsync(contactPcm)
    }

    fun playCompleteBloom(context: Context) {
        if (!shouldPlay(context)) return
        playPcmAsync(completePcm)
    }

    fun playErrorTone(context: Context) {
        if (!shouldPlay(context)) return
        playPcmAsync(errorPcm)
    }

    private fun playPcmAsync(samples: ShortArray) {
        scope.launch {
            var track: AudioTrack? = null
            try {
                val byteSize = samples.size * 2
                val attrs = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
                val format = AudioFormat.Builder()
                    .setSampleRate(SAMPLE_RATE)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()

                track = AudioTrack.Builder()
                    .setAudioAttributes(attrs)
                    .setAudioFormat(format)
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .setBufferSizeInBytes(byteSize)
                    .build()

                track.write(samples, 0, samples.size)
                track.play()
                val durationMs = (samples.size * 1000L) / SAMPLE_RATE
                delay(durationMs + 90L)
            } catch (_: Exception) {
            } finally {
                runCatching { track?.stop() }
                runCatching { track?.release() }
            }
        }
    }

    private fun synthesizeContactShimmer(): ShortArray {
        val durationSec = 0.19
        val count = (SAMPLE_RATE * durationSec).toInt()
        val out = ShortArray(count)
        for (i in 0 until count) {
            val t = i.toDouble() / SAMPLE_RATE
            val progress = i.toDouble() / count
            val freq = 587.33 + 292.67 * progress // D5 -> A5 glide
            val env = sin(PI * progress) * exp(-2.4 * t)
            val wave = 0.72 * sin(2.0 * PI * freq * t) +
                0.28 * sin(2.0 * PI * (freq * 1.5) * t)
            out[i] = (wave * env * 0.22 * Short.MAX_VALUE).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
        return out
    }

    private fun synthesizeCompleteBloom(): ShortArray {
        val durationSec = 0.36
        val count = (SAMPLE_RATE * durationSec).toInt()
        val out = ShortArray(count)
        val note2Start = 0.085
        for (i in 0 until count) {
            val t = i.toDouble() / SAMPLE_RATE
            val attack1 = (t / 0.012).coerceIn(0.0, 1.0)
            val note1Env = attack1 * exp(-9.5 * t)
            val note1 = 0.78 * sin(2.0 * PI * 1046.50 * t) + 0.22 * sin(2.0 * PI * 2093.0 * t) // C6 glass

            var note2Sample = 0.0
            if (t >= note2Start) {
                val t2 = t - note2Start
                val attack2 = (t2 / 0.012).coerceIn(0.0, 1.0)
                val note2Env = attack2 * exp(-7.8 * t2)
                note2Sample = (0.80 * sin(2.0 * PI * 1567.98 * t2) + 0.20 * sin(2.0 * PI * 3135.96 * t2)) * note2Env // G6 glass
            }
            val mixed = note1 * note1Env * 0.75 + note2Sample
            out[i] = (mixed * 0.24 * Short.MAX_VALUE).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
        return out
    }

    private fun synthesizeErrorTone(): ShortArray {
        val durationSec = 0.20
        val count = (SAMPLE_RATE * durationSec).toInt()
        val out = ShortArray(count)
        for (i in 0 until count) {
            val t = i.toDouble() / SAMPLE_RATE
            val progress = i.toDouble() / count
            val freq = 349.23 - 102.23 * progress
            val env = sin(PI * progress) * exp(-4.0 * t)
            val wave = sin(2.0 * PI * freq * t)
            out[i] = (wave * env * 0.18 * Short.MAX_VALUE).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
        return out
    }
}
