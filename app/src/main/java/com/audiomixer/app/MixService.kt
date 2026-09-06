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
import java.util.concurrent.atomic.AtomicBoolean

/** Offline mix in :mix process. Fast, timed SFX overlays, speed/pitch/echo/fade. */
class MixService : Service() {

    private val cancelled = AtomicBoolean(false)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        if (intent.action == ACTION_CANCEL) {
            cancelled.set(true)
            try { FFmpegKit.cancel() } catch (_: Throwable) {}
            stopSelf()
            return START_NOT_STICKY
        }

        cancelled.set(false)
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
        val overlaysRaw = intent.getStringExtra(EXTRA_OVERLAYS) ?: ""

        Thread {
            var ok = false
            var err = "میکس ناموفق بود"
            try {
                if (cancelled.get()) {
                    err = "میکس لغو شد"
                } else if (mainPath.isBlank() || !File(mainPath).exists()) {
                    err = "فایل اصلی پیدا نشد"
                } else if (bgPath.isBlank() || !File(bgPath).exists()) {
                    err = "فایل پس‌زمینه پیدا نشد"
                } else {
                    File(outPath).parentFile?.mkdirs()
                    File(outPath).delete()

                    val overlays = parseOverlays(overlaysRaw)
                    val needsFx = hasFx(mainSpeed, bgSpeed, mainPitch, bgPitch, mainEcho, bgEcho, fade) || overlays.isNotEmpty()

                    // Always prefer FFmpeg when FX/overlays — applies filters correctly
                    if (!cancelled.get()) {
                        ok = runFfmpegFull(
                            mainPath, bgPath, outPath,
                            mainVol, bgVol, mainSpeed, bgSpeed,
                            mainPitch, bgPitch, mainEcho, bgEcho,
                            fade, durationSec, overlays
                        )
                    }
                    if (!ok && !cancelled.get() && overlays.isEmpty() && !needsFx) {
                        ok = runFfmpegSimple(mainPath, bgPath, outPath, mainVol, bgVol, durationSec)
                    }
                    if (!ok && !cancelled.get() && overlays.isEmpty()) {
                        ok = runJava(mainPath, bgPath, outPath, mainVol, bgVol)
                    }

                    when {
                        cancelled.get() -> { ok = false; err = "میکس لغو شد"; File(outPath).delete() }
                        ok -> err = ""
                        else -> err = "میکس انجام نشد. دوباره تلاش کنید."
                    }
                }
            } catch (_: Throwable) {
                err = if (cancelled.get()) "میکس لغو شد" else "خطا در میکس. دوباره تلاش کنید."
                ok = false
                if (!cancelled.get()) {
                    try {
                        ok = runJava(mainPath, bgPath, outPath, mainVol, bgVol)
                        if (ok) err = ""
                    } catch (_: Throwable) {}
                }
            }

            val bundle = android.os.Bundle()
            bundle.putBoolean(KEY_OK, ok)
            bundle.putString(KEY_PATH, outPath)
            bundle.putString(KEY_ERROR, err)
            try { receiver?.send(if (ok) RESULT_OK else RESULT_FAIL, bundle) } catch (_: Exception) {}
            stopSelf(startId)
        }.start()

        return START_NOT_STICKY
    }

    private data class Overlay(val path: String, val delayMs: Long)

    private fun parseOverlays(raw: String): List<Overlay> {
        if (raw.isBlank()) return emptyList()
        return raw.split(';').mapNotNull { part ->
            val bits = part.split('|')
            if (bits.size < 2) return@mapNotNull null
            val path = bits[0]
            val ms = bits[1].toLongOrNull() ?: return@mapNotNull null
            if (!File(path).exists()) return@mapNotNull null
            Overlay(path, ms.coerceAtLeast(0L))
        }.take(20)
    }

    private fun hasFx(ms: Float, bs: Float, mp: Float, bp: Float, me: Float, be: Float, fade: Boolean) =
        kotlin.math.abs(ms - 1f) > 0.02f || kotlin.math.abs(bs - 1f) > 0.02f ||
            kotlin.math.abs(mp - 1f) > 0.02f || kotlin.math.abs(bp - 1f) > 0.02f ||
            me > 0.05f || be > 0.05f || fade

    private fun f(v: Float) = String.format(Locale.US, "%.3f", v)
    private fun d(v: Double) = String.format(Locale.US, "%.3f", v)

    private fun buildTrack(vol: Float, speed: Float, pitch: Float, echo: Float): String {
        val parts = ArrayList<String>()
        parts.add("volume=${f(vol.coerceIn(0f, 2f))}")
        val sp = speed.coerceIn(0.5f, 2f)
        if (kotlin.math.abs(sp - 1f) > 0.02f) parts.add("atempo=${f(sp)}")
        val p = pitch.coerceIn(0.5f, 2f)
        if (kotlin.math.abs(p - 1f) > 0.02f) {
            parts.add("asetrate=${f(44100f * p)}")
            parts.add("aresample=44100")
            parts.add("atempo=${f((1f / p).coerceIn(0.5f, 2f))}")
        }
        if (echo > 0.05f) {
            val g = f((0.45f * echo).coerceIn(0.1f, 0.65f))
            parts.add("aecho=0.8:$g:55:0.35")
        }
        return parts.joinToString(",")
    }

    private fun runFfmpegFull(
        mainPath: String, bgPath: String, outPath: String,
        mainVol: Float, bgVol: Float,
        mainSpeed: Float, bgSpeed: Float,
        mainPitch: Float, bgPitch: Float,
        mainEcho: Float, bgEcho: Float,
        fade: Boolean, durationSec: Double,
        overlays: List<Overlay>
    ): Boolean {
        if (cancelled.get()) return false
        return try {
            val mainF = buildTrack(mainVol, mainSpeed, mainPitch, mainEcho)
            val bgF = buildTrack(bgVol, bgSpeed, bgPitch, bgEcho)

            val args = ArrayList<String>()
            args.add("-y")
            args.add("-i"); args.add(mainPath)
            args.add("-stream_loop"); args.add("-1")
            args.add("-i"); args.add(bgPath)
            for (o in overlays) {
                args.add("-i"); args.add(o.path)
            }

            val filter = StringBuilder()
            filter.append("[0:a]$mainF[a0];[1:a]$bgF[a1]")
            val mixLabels = ArrayList<String>()
            mixLabels.add("[a0]")
            mixLabels.add("[a1]")
            overlays.forEachIndexed { i, o ->
                val idx = i + 2
                val lab = "e$i"
                // adelay in ms for L|R — keep original quality (no pitch change)
                filter.append(";[$idx:a]volume=1.0,adelay=${o.delayMs}|${o.delayMs}[$lab]")
                mixLabels.add("[$lab]")
            }
            val n = mixLabels.size
            filter.append(";")
            filter.append(mixLabels.joinToString(""))
            filter.append("amix=inputs=$n:duration=first:dropout_transition=0:normalize=0")

            if (fade && durationSec > 3.0) {
                val outStart = d((durationSec - 1.5).coerceAtLeast(0.0))
                filter.append("[am];[am]afade=t=in:st=0:d=1.0,afade=t=out:st=$outStart:d=1.5[a]")
            } else if (fade) {
                filter.append("[am];[am]afade=t=in:st=0:d=0.8,afade=t=out:st=0:d=0.8[a]")
            } else {
                filter.append("[a]")
            }

            args.add("-filter_complex"); args.add(filter.toString())
            args.add("-map"); args.add("[a]")
            // Fast encode for long files
            val bitrate = if (durationSec > 600) "64k" else "96k"
            args.addAll(listOf("-c:a", "aac", "-b:a", bitrate, "-ac", "2", "-ar", "44100"))
            if (durationSec > 1.0) {
                args.add("-t"); args.add(d(durationSec))
            } else {
                args.add("-shortest")
            }
            args.add(outPath)

            val session = FFmpegKit.executeWithArguments(args.toTypedArray())
            if (cancelled.get()) {
                File(outPath).delete()
                return false
            }
            val out = File(outPath)
            ReturnCode.isSuccess(session.returnCode) && out.exists() && out.length() > 200
        } catch (_: Throwable) {
            false
        }
    }

    private fun runFfmpegSimple(
        mainPath: String, bgPath: String, outPath: String,
        mainVol: Float, bgVol: Float, durationSec: Double
    ): Boolean {
        if (cancelled.get()) return false
        return try {
            val filter =
                "[0:a]volume=${f(mainVol)}[a0];[1:a]volume=${f(bgVol)}[a1];[a0][a1]amix=inputs=2:duration=first:dropout_transition=0[a]"
            val args = ArrayList<String>()
            args.addAll(
                listOf(
                    "-y", "-i", mainPath, "-stream_loop", "-1", "-i", bgPath,
                    "-filter_complex", filter, "-map", "[a]",
                    "-c:a", "aac", "-b:a", if (durationSec > 600) "64k" else "96k",
                    "-ac", "2", "-ar", "44100"
                )
            )
            if (durationSec > 1.0) {
                args.add("-t"); args.add(d(durationSec))
            } else args.add("-shortest")
            args.add(outPath)
            val session = FFmpegKit.executeWithArguments(args.toTypedArray())
            if (cancelled.get()) {
                File(outPath).delete()
                return false
            }
            val out = File(outPath)
            ReturnCode.isSuccess(session.returnCode) && out.exists() && out.length() > 200
        } catch (_: Throwable) {
            false
        }
    }

    private fun runJava(mainPath: String, bgPath: String, outPath: String, mainVol: Float, bgVol: Float): Boolean {
        if (cancelled.get()) return false
        return try {
            File(outPath).delete()
            val mixer = AudioMixer(outPath)
            val in1 = GeneralAudioInput(mainPath)
            in1.setVolume(mainVol.coerceIn(0f, 2f))
            val in2 = GeneralAudioInput(bgPath)
            in2.setVolume(bgVol.coerceIn(0f, 2f))
            val d1 = try { in1.durationUs } catch (_: Exception) { 0L }
            val d2 = try { in2.durationUs } catch (_: Exception) { 0L }
            if (d1 > 0 && d2 > d1) try { in2.setEndTimeUs(d1) } catch (_: Exception) {}
            try { mixer.setLoopingEnabled(true) } catch (_: Exception) {}
            mixer.addDataSource(in1)
            mixer.addDataSource(in2)
            mixer.setSampleRate(44100)
            mixer.setBitRate(96000)
            mixer.setChannelCount(2)
            mixer.start()
            if (cancelled.get()) return false
            mixer.processSync()
            if (cancelled.get()) {
                File(outPath).delete()
                return false
            }
            File(outPath).exists() && File(outPath).length() > 500
        } catch (_: Throwable) {
            false
        }
    }

    companion object {
        const val ACTION_CANCEL = "com.audiomixer.app.CANCEL_MIX"
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
        const val EXTRA_OVERLAYS = "overlays" // path|ms;path|ms
        const val KEY_OK = "ok"
        const val KEY_PATH = "path"
        const val KEY_ERROR = "error"
        const val RESULT_OK = 1
        const val RESULT_FAIL = 0
    }
}
