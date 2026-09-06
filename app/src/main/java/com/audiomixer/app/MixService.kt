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
import java.util.Locale

/**
 * Separate process (:mix). Mix short + long files reliably.
 * All numbers use Locale.US so FFmpeg never sees Persian digits.
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
        val durationSec = intent.getDoubleExtra(EXTRA_DURATION_SEC, 0.0)

        Thread {
            var ok = false
            var err = "میکس ناموفق بود"

            try {
                if (mainPath.isBlank() || bgPath.isBlank() || outPath.isBlank()) {
                    err = "مسیر فایل‌ها نامعتبر است"
                } else if (!File(mainPath).exists() || !File(bgPath).exists()) {
                    err = "فایل ورودی پیدا نشد"
                } else {
                    File(outPath).parentFile?.mkdirs()
                    File(outPath).delete()

                    val needsFx = kotlin.math.abs(mainSpeed - 1f) > 0.02f ||
                            kotlin.math.abs(bgSpeed - 1f) > 0.02f ||
                            kotlin.math.abs(mainPitch - 1f) > 0.02f ||
                            kotlin.math.abs(bgPitch - 1f) > 0.02f ||
                            mainEcho > 0.05f || bgEcho > 0.05f || fade

                    val longish = durationSec > 180 || File(mainPath).length() > 4_000_000L

                    // 1) Prefer Java mixer for short files without advanced FX (stable, no native)
                    if (!needsFx && !longish) {
                        ok = runJavaSafe(mainPath, bgPath, outPath, mainVol, bgVol)
                    }

                    // 2) FFmpeg simple (volume only + loop) — best for long files
                    if (!ok) {
                        ok = runFfmpegSimple(mainPath, bgPath, outPath, mainVol, bgVol, durationSec)
                    }

                    // 3) FFmpeg with effects if requested
                    if (!ok && needsFx) {
                        ok = runFfmpegEffects(
                            mainPath, bgPath, outPath,
                            mainVol, bgVol, mainSpeed, bgSpeed,
                            mainPitch, bgPitch, mainEcho, bgEcho,
                            fade, durationSec
                        )
                    }

                    // 4) Always try Java as last resort
                    if (!ok) {
                        ok = runJavaSafe(mainPath, bgPath, outPath, mainVol, bgVol)
                    }

                    if (!ok) {
                        err = "میکس انجام نشد. فرمت فایل را عوض کنید یا دوباره انتخاب کنید."
                    } else {
                        err = ""
                    }
                }
            } catch (t: Throwable) {
                // Never show raw English stack to user
                val msg = t.message ?: ""
                err = when {
                    msg.contains("UnsatisfiedLink", true) || msg.contains("ffmpeg", true) ->
                        "موتور میکس آماده نیست. دوباره تلاش کنید."
                    msg.contains("codec", true) || msg.contains("format", true) ->
                        "فرمت یکی از فایل‌ها پشتیبانی نمی‌شود."
                    else -> "خطا در میکس. دوباره تلاش کنید."
                }
                try {
                    ok = runJavaSafe(mainPath, bgPath, outPath, mainVol, bgVol)
                    if (ok) err = ""
                } catch (_: Throwable) {
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

    private fun f(v: Float): String = String.format(Locale.US, "%.3f", v)
    private fun d(v: Double): String = String.format(Locale.US, "%.3f", v)

    private fun runFfmpegSimple(
        mainPath: String, bgPath: String, outPath: String,
        mainVol: Float, bgVol: Float,
        durationSec: Double
    ): Boolean {
        return try {
            val filter = "[0:a]volume=${f(mainVol)}[a0];[1:a]volume=${f(bgVol)}[a1];" +
                    "[a0][a1]amix=inputs=2:duration=first:dropout_transition=0[a]"

            val args = ArrayList<String>()
            args.add("-y")
            args.add("-i"); args.add(mainPath)
            args.add("-stream_loop"); args.add("-1")
            args.add("-i"); args.add(bgPath)
            args.add("-filter_complex"); args.add(filter)
            args.add("-map"); args.add("[a]")
            args.add("-c:a"); args.add("aac")
            args.add("-b:a"); args.add("96k")
            args.add("-ac"); args.add("2")
            args.add("-ar"); args.add("44100")
            if (durationSec > 1.0) {
                args.add("-t"); args.add(d(durationSec))
            } else {
                args.add("-shortest")
            }
            args.add(outPath)

            val session = FFmpegKit.executeWithArguments(args.toTypedArray())
            val out = File(outPath)
            ReturnCode.isSuccess(session.returnCode) && out.exists() && out.length() > 200
        } catch (_: Throwable) {
            false
        }
    }

    private fun runFfmpegEffects(
        mainPath: String, bgPath: String, outPath: String,
        mainVol: Float, bgVol: Float,
        mainSpeed: Float, bgSpeed: Float,
        mainPitch: Float, bgPitch: Float,
        mainEcho: Float, bgEcho: Float,
        fade: Boolean,
        durationSec: Double
    ): Boolean {
        return try {
            val mainF = buildTrack(mainVol, mainSpeed, mainPitch, mainEcho)
            val bgF = buildTrack(bgVol, bgSpeed, bgPitch, bgEcho)

            val filter = if (fade && durationSec > 3.0) {
                val outStart = d((durationSec - 1.5).coerceAtLeast(0.0))
                "[0:a]$mainF[a0];[1:a]$bgF[a1];" +
                        "[a0][a1]amix=inputs=2:duration=first:dropout_transition=0[am];" +
                        "[am]afade=t=in:st=0:d=1.0,afade=t=out:st=$outStart:d=1.5[a]"
            } else if (fade) {
                "[0:a]$mainF[a0];[1:a]$bgF[a1];" +
                        "[a0][a1]amix=inputs=2:duration=first:dropout_transition=0[am];" +
                        "[am]afade=t=in:st=0:d=0.8,afade=t=out:st=0:d=0.8[a]"
            } else {
                "[0:a]$mainF[a0];[1:a]$bgF[a1];" +
                        "[a0][a1]amix=inputs=2:duration=first:dropout_transition=0[a]"
            }

            val args = ArrayList<String>()
            args.add("-y")
            args.add("-i"); args.add(mainPath)
            args.add("-stream_loop"); args.add("-1")
            args.add("-i"); args.add(bgPath)
            args.add("-filter_complex"); args.add(filter)
            args.add("-map"); args.add("[a]")
            args.add("-c:a"); args.add("aac")
            args.add("-b:a"); args.add("96k")
            args.add("-ac"); args.add("2")
            args.add("-ar"); args.add("44100")
            if (durationSec > 1.0) {
                args.add("-t"); args.add(d(durationSec))
            } else {
                args.add("-shortest")
            }
            args.add(outPath)

            val session = FFmpegKit.executeWithArguments(args.toTypedArray())
            val out = File(outPath)
            ReturnCode.isSuccess(session.returnCode) && out.exists() && out.length() > 200
        } catch (_: Throwable) {
            false
        }
    }

    private fun buildTrack(vol: Float, speed: Float, pitch: Float, echo: Float): String {
        val parts = ArrayList<String>()
        parts.add("volume=${f(vol.coerceIn(0f, 2f))}")

        var sp = speed.coerceIn(0.5f, 2f)
        if (kotlin.math.abs(sp - 1f) > 0.02f) {
            parts.add("atempo=${f(sp)}")
        }

        val p = pitch.coerceIn(0.5f, 2f)
        if (kotlin.math.abs(p - 1f) > 0.02f) {
            parts.add("asetrate=${f(44100f * p)}")
            parts.add("aresample=44100")
            parts.add("atempo=${f((1f / p).coerceIn(0.5f, 2f))}")
        }

        if (echo > 0.05f) {
            val g = f((0.4f * echo).coerceIn(0.1f, 0.6f))
            parts.add("aecho=0.8:$g:50:0.3")
        }
        return parts.joinToString(",")
    }

    private fun runJavaSafe(
        mainPath: String, bgPath: String, outPath: String,
        mainVol: Float, bgVol: Float
    ): Boolean {
        return try {
            File(outPath).delete()
            val mixer = AudioMixer(outPath)
            val in1 = GeneralAudioInput(mainPath)
            in1.setVolume(mainVol.coerceIn(0f, 2f))
            val in2 = GeneralAudioInput(bgPath)
            in2.setVolume(bgVol.coerceIn(0f, 2f))

            val d1 = try { in1.durationUs } catch (_: Exception) { 0L }
            val d2 = try { in2.durationUs } catch (_: Exception) { 0L }
            if (d1 > 0 && d2 > d1) {
                try { in2.setEndTimeUs(d1) } catch (_: Exception) {}
            }
            try { mixer.setLoopingEnabled(true) } catch (_: Exception) {}

            mixer.addDataSource(in1)
            mixer.addDataSource(in2)
            mixer.setSampleRate(44100)
            mixer.setBitRate(96000)
            mixer.setChannelCount(2)
            mixer.start()
            mixer.processSync()

            val out = File(outPath)
            out.exists() && out.length() > 500
        } catch (_: Throwable) {
            false
        }
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
