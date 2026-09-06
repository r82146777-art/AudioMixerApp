package com.audiomixer.app

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.ResultReceiver
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import zeroonezero.android.audio_mixer.AudioMixer
import zeroonezero.android.audio_mixer.input.GeneralAudioInput
import java.io.File

/**
 * Runs in SEPARATE process (:mix).
 * Optimized for long files (10–60+ min) with explicit duration and fast AAC.
 */
class MixService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            stopSelf(startId)
            return START_NOT_STICKY
        }

        @Suppress("DEPRECATION")
        val receiver = intent.getParcelableExtra<ResultReceiver>(EXTRA_RECEIVER)
        val mainPath = intent.getStringExtra(EXTRA_MAIN) ?: ""
        val bgPath = intent.getStringExtra(EXTRA_BG) ?: ""
        val outPath = intent.getStringExtra(EXTRA_OUT) ?: ""
        val mainVol = intent.getFloatExtra(EXTRA_MAIN_VOL, 1f)
        val bgVol = intent.getFloatExtra(EXTRA_BG_VOL, 0.5f)
        val mainSpeed = intent.getFloatExtra(EXTRA_MAIN_SPEED, 1f)
        val bgSpeed = intent.getFloatExtra(EXTRA_BG_SPEED, 1f)
        val mainPitch = intent.getFloatExtra(EXTRA_MAIN_PITCH, 1f)
        val bgPitch = intent.getFloatExtra(EXTRA_BG_PITCH, 1f)
        val mainEcho = intent.getFloatExtra(EXTRA_MAIN_ECHO, 0f)
        val bgEcho = intent.getFloatExtra(EXTRA_BG_ECHO, 0f)
        val fade = intent.getBooleanExtra(EXTRA_FADE, false)
        val useFfmpeg = intent.getBooleanExtra(EXTRA_USE_FFMPEG, true)
        val durationSec = intent.getDoubleExtra(EXTRA_DURATION_SEC, 0.0)

        Thread {
            var ok = false
            var err = ""
            try {
                File(outPath).parentFile?.mkdirs()

                if (useFfmpeg) {
                    ok = runFfmpegFast(
                        mainPath, bgPath, outPath,
                        mainVol, bgVol, mainSpeed, bgSpeed,
                        mainPitch, bgPitch, mainEcho, bgEcho,
                        fade, durationSec
                    )
                    if (!ok) {
                        // Simple FFmpeg without advanced filters
                        ok = runFfmpegSimple(mainPath, bgPath, outPath, mainVol, bgVol, durationSec)
                    }
                    if (!ok) {
                        // Last resort: Java mixer (only if not extremely long)
                        val mainLen = File(mainPath).length()
                        if (mainLen < 15_000_000L) {
                            ok = runJava(mainPath, bgPath, outPath, mainVol, bgVol)
                        }
                        if (!ok) err = "میکس فایل طولانی ناموفق بود"
                    }
                } else {
                    ok = runJava(mainPath, bgPath, outPath, mainVol, bgVol)
                    if (!ok) err = "میکس ناموفق بود"
                }
            } catch (t: Throwable) {
                err = t.message ?: t.javaClass.simpleName
                try {
                    ok = runFfmpegSimple(mainPath, bgPath, outPath, mainVol, bgVol, durationSec)
                    if (ok) err = ""
                } catch (t2: Throwable) {
                    err = t2.message ?: err
                    ok = false
                }
            }

            val bundle = android.os.Bundle()
            bundle.putBoolean(KEY_OK, ok)
            bundle.putString(KEY_PATH, outPath)
            bundle.putString(KEY_ERROR, err)
            try {
                receiver?.send(if (ok) RESULT_OK else RESULT_FAIL, bundle)
            } catch (_: Exception) {}
            stopSelf(startId)
        }.start()

        return START_NOT_STICKY
    }

    private fun trackFilter(vol: Float, speed: Float, pitch: Float, echo: Float): String {
        val parts = mutableListOf("volume=${vol.coerceIn(0f, 2f)}")
        val sp = speed.coerceIn(0.5f, 2f)
        if (kotlin.math.abs(sp - 1f) > 0.01f) {
            // atempo only accepts 0.5–2.0; chain if needed
            var remaining = sp
            while (remaining > 2.0f) {
                parts.add("atempo=2.0")
                remaining /= 2.0f
            }
            while (remaining < 0.5f) {
                parts.add("atempo=0.5")
                remaining /= 0.5f
            }
            parts.add("atempo=${"%.3f".format(remaining)}")
        }
        val p = pitch.coerceIn(0.5f, 2f)
        if (kotlin.math.abs(p - 1f) > 0.01f) {
            parts.add("asetrate=44100*$p")
            parts.add("aresample=44100")
            val comp = (1.0 / p).toFloat().coerceIn(0.5f, 2f)
            parts.add("atempo=${"%.3f".format(comp)}")
        }
        if (echo > 0.05f) {
            val g = (0.5f * echo).coerceIn(0.1f, 0.7f)
            parts.add("aecho=0.8:$g:40|60:0.3|0.25")
        }
        return parts.joinToString(",")
    }

    /** Fast path for long files — explicit -t, low bitrate, no normalize */
    private fun runFfmpegFast(
        mainPath: String, bgPath: String, outPath: String,
        mainVol: Float, bgVol: Float,
        mainSpeed: Float, bgSpeed: Float,
        mainPitch: Float, bgPitch: Float,
        mainEcho: Float, bgEcho: Float,
        fade: Boolean,
        durationSec: Double
    ): Boolean {
        val mainF = trackFilter(mainVol, mainSpeed, mainPitch, mainEcho)
        val bgF = trackFilter(bgVol, bgSpeed, bgPitch, bgEcho)

        val fadePart = if (fade && durationSec > 3.0) {
            val outStart = (durationSec - 1.5).coerceAtLeast(0.0)
            ";[am]afade=t=in:st=0:d=1.2,afade=t=out:st=$outStart:d=1.5[a]"
        } else if (fade) {
            ";[am]afade=t=in:st=0:d=0.8,afade=t=out:st=0:d=0.8[a]"
        } else {
            "[a]" // will fix label below
        }

        val filter = if (fade) {
            "[0:a]$mainF[a0];[1:a]$bgF[a1];[a0][a1]amix=inputs=2:duration=first:dropout_transition=0:normalize=0[am]$fadePart"
        } else {
            "[0:a]$mainF[a0];[1:a]$bgF[a1];[a0][a1]amix=inputs=2:duration=first:dropout_transition=0:normalize=0[a]"
        }

        val args = mutableListOf(
            "-y",
            "-threads", "0",
            "-i", mainPath,
            "-stream_loop", "-1",
            "-i", bgPath,
            "-filter_complex", filter,
            "-map", "[a]",
            "-c:a", "aac",
            "-b:a", "96k",
            "-ac", "2",
            "-ar", "44100"
        )
        if (durationSec > 1.0) {
            args.add("-t")
            args.add("%.3f".format(durationSec))
        } else {
            args.add("-shortest")
        }
        args.add(outPath)

        val session = FFmpegKit.executeWithArguments(args.toTypedArray())
        val out = File(outPath)
        return ReturnCode.isSuccess(session.returnCode) && out.exists() && out.length() > 200
    }

    private fun runFfmpegSimple(
        mainPath: String, bgPath: String, outPath: String,
        mainVol: Float, bgVol: Float,
        durationSec: Double
    ): Boolean {
        val filter =
            "[0:a]volume=$mainVol[a0];[1:a]volume=$bgVol[a1];[a0][a1]amix=inputs=2:duration=first:dropout_transition=0:normalize=0[a]"
        val args = mutableListOf(
            "-y",
            "-threads", "0",
            "-i", mainPath,
            "-stream_loop", "-1",
            "-i", bgPath,
            "-filter_complex", filter,
            "-map", "[a]",
            "-c:a", "aac",
            "-b:a", "96k",
            "-ac", "2",
            "-ar", "44100"
        )
        if (durationSec > 1.0) {
            args.add("-t")
            args.add("%.3f".format(durationSec))
        } else {
            args.add("-shortest")
        }
        args.add(outPath)

        val session = FFmpegKit.executeWithArguments(args.toTypedArray())
        val out = File(outPath)
        return ReturnCode.isSuccess(session.returnCode) && out.exists() && out.length() > 200
    }

    private fun runJava(mainPath: String, bgPath: String, outPath: String, mainVol: Float, bgVol: Float): Boolean {
        val out = File(outPath)
        val mixer = AudioMixer(out.absolutePath)
        val in1 = GeneralAudioInput(mainPath)
        in1.setVolume(mainVol)
        val in2 = GeneralAudioInput(bgPath)
        in2.setVolume(bgVol)
        val d1 = in1.durationUs
        val d2 = in2.durationUs
        if (d1 > 0 && d2 > d1) in2.setEndTimeUs(d1)
        mixer.setLoopingEnabled(true)
        mixer.addDataSource(in1)
        mixer.addDataSource(in2)
        mixer.setSampleRate(44100)
        mixer.setBitRate(96000)
        mixer.setChannelCount(2)
        mixer.start()
        mixer.processSync()
        return out.exists() && out.length() > 500
    }

    companion object {
        const val EXTRA_RECEIVER = "receiver"
        const val EXTRA_MAIN = "main"
        const val EXTRA_BG = "bg"
        const val EXTRA_OUT = "out"
        const val EXTRA_MAIN_VOL = "mainVol"
        const val EXTRA_BG_VOL = "bgVol"
        const val EXTRA_MAIN_SPEED = "mainSpeed"
        const val EXTRA_BG_SPEED = "bgSpeed"
        const val EXTRA_MAIN_PITCH = "mainPitch"
        const val EXTRA_BG_PITCH = "bgPitch"
        const val EXTRA_MAIN_ECHO = "mainEcho"
        const val EXTRA_BG_ECHO = "bgEcho"
        const val EXTRA_FADE = "fade"
        const val EXTRA_USE_FFMPEG = "useFfmpeg"
        const val EXTRA_DURATION_SEC = "durationSec"
        const val KEY_OK = "ok"
        const val KEY_PATH = "path"
        const val KEY_ERROR = "error"
        const val RESULT_OK = 1
        const val RESULT_FAIL = 0
    }
}
