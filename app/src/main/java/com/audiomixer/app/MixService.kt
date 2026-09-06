package com.audiomixer.app

import android.app.Service
import android.content.Intent
import android.os.IBinder
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import zeroonezero.android.audio_mixer.AudioMixer
import zeroonezero.android.audio_mixer.input.GeneralAudioInput
import java.io.File
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Mix runs in :mix process so UI never crashes.
 * Always sends ACTION_MIX_DONE broadcast when finished (success or fail).
 */
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
        var durationSec = intent.getDoubleExtra(EXTRA_DURATION_SEC, 0.0)
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
                    try { File(outPath).delete() } catch (_: Exception) {}

                    if (durationSec <= 0.5) {
                        durationSec = probeDuration(mainPath)
                    }

                    val overlays = parseOverlays(overlaysRaw)
                    val needsFx = hasFx(mainSpeed, bgSpeed, mainPitch, bgPitch, mainEcho, bgEcho, fade) || overlays.isNotEmpty()

                    // Path A: simple mix — Java first (fast + reliable)
                    if (!needsFx && !cancelled.get()) {
                        ok = runJava(mainPath, bgPath, outPath, mainVol, bgVol)
                    }

                    // Path B: FFmpeg volume-only if Java failed
                    if (!ok && !needsFx && !cancelled.get()) {
                        ok = runFfmpegVolume(mainPath, bgPath, outPath, mainVol, bgVol, durationSec)
                    }

                    // Path C: full FX / overlays
                    if (!ok && needsFx && !cancelled.get()) {
                        ok = runFfmpegFull(
                            mainPath, bgPath, outPath,
                            mainVol, bgVol, mainSpeed, bgSpeed,
                            mainPitch, bgPitch, mainEcho, bgEcho,
                            fade, durationSec, overlays
                        )
                    }

                    // Path D: last resort volume-only Java
                    if (!ok && !cancelled.get()) {
                        ok = runJava(mainPath, bgPath, outPath, mainVol, bgVol)
                    }

                    when {
                        cancelled.get() -> {
                            ok = false
                            err = "میکس لغو شد"
                            try { File(outPath).delete() } catch (_: Exception) {}
                        }
                        ok -> err = ""
                        else -> err = "میکس انجام نشد. دوباره تلاش کنید."
                    }
                }
            } catch (t: Throwable) {
                err = if (cancelled.get()) "میکس لغو شد" else "خطا: ${t.message ?: "نامشخص"}"
                ok = false
                if (!cancelled.get()) {
                    try {
                        ok = runJava(mainPath, bgPath, outPath, mainVol, bgVol)
                        if (ok) err = ""
                    } catch (_: Throwable) {}
                }
            }

            val result = Intent(ACTION_MIX_DONE).apply {
                setPackage(packageName)
                putExtra(KEY_OK, ok)
                putExtra(KEY_PATH, outPath)
                putExtra(KEY_ERROR, err)
            }
            try { sendBroadcast(result) } catch (_: Exception) {}
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
        }.take(8)
    }

    private fun hasFx(ms: Float, bs: Float, mp: Float, bp: Float, me: Float, be: Float, fade: Boolean) =
        kotlin.math.abs(ms - 1f) > 0.02f || kotlin.math.abs(bs - 1f) > 0.02f ||
            kotlin.math.abs(mp - 1f) > 0.02f || kotlin.math.abs(bp - 1f) > 0.02f ||
            me > 0.05f || be > 0.05f || fade

    private fun f(v: Float) = String.format(Locale.US, "%.3f", v)
    private fun d(v: Double) = String.format(Locale.US, "%.3f", v)

    private fun probeDuration(path: String): Double {
        return try {
            val r = android.media.MediaMetadataRetriever()
            r.setDataSource(path)
            val ms = r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            r.release()
            (ms / 1000.0).coerceAtLeast(1.0)
        } catch (_: Exception) {
            60.0
        }
    }

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

    /** Volume-only FFmpeg: always finite with -t */
    private fun runFfmpegVolume(
        mainPath: String, bgPath: String, outPath: String,
        mainVol: Float, bgVol: Float, durationSec: Double
    ): Boolean {
        if (cancelled.get()) return false
        val t = if (durationSec > 0.5) durationSec else 120.0
        return try {
            val filter =
                "[0:a]volume=${f(mainVol)}[a0];" +
                "[1:a]volume=${f(bgVol)},aloop=loop=-1:size=2e9[a1];" +
                "[a0][a1]amix=inputs=2:duration=first:dropout_transition=0[a]"

            val args = arrayOf(
                "-y",
                "-i", mainPath,
                "-i", bgPath,
                "-filter_complex", filter,
                "-map", "[a]",
                "-c:a", "aac",
                "-b:a", if (t > 900) "64k" else "96k",
                "-ac", "2",
                "-ar", "44100",
                "-t", d(t),
                outPath
            )
            val session = FFmpegKit.executeWithArguments(args)
            if (cancelled.get()) {
                try { File(outPath).delete() } catch (_: Exception) {}
                return false
            }
            val out = File(outPath)
            ReturnCode.isSuccess(session.returnCode) && out.exists() && out.length() > 200
        } catch (_: Throwable) {
            false
        }
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
        val t = if (durationSec > 0.5) durationSec else 120.0
        return try {
            val mainF = buildTrack(mainVol, mainSpeed, mainPitch, mainEcho)
            val bgF = buildTrack(bgVol, bgSpeed, bgPitch, bgEcho) + ",aloop=loop=-1:size=2e9"

            val args = ArrayList<String>()
            args.add("-y")
            args.add("-i"); args.add(mainPath)
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
                filter.append(";[$idx:a]volume=1.0,adelay=${o.delayMs}|${o.delayMs}[$lab]")
                mixLabels.add("[$lab]")
            }
            val n = mixLabels.size
            filter.append(";")
            filter.append(mixLabels.joinToString(""))
            filter.append("amix=inputs=$n:duration=first:dropout_transition=0")

            if (fade && t > 3.0) {
                val outStart = d((t - 1.5).coerceAtLeast(0.0))
                filter.append("[am];[am]afade=t=in:st=0:d=1.0,afade=t=out:st=$outStart:d=1.5[a]")
            } else if (fade) {
                filter.append("[am];[am]afade=t=in:st=0:d=0.5,afade=t=out:st=0:d=0.5[a]")
            } else {
                filter.append("[a]")
            }

            args.add("-filter_complex"); args.add(filter.toString())
            args.add("-map"); args.add("[a]")
            args.addAll(listOf("-c:a", "aac", "-b:a", if (t > 900) "64k" else "96k", "-ac", "2", "-ar", "44100"))
            args.add("-t"); args.add(d(t))
            args.add(outPath)

            val session = FFmpegKit.executeWithArguments(args.toTypedArray())
            if (cancelled.get()) {
                try { File(outPath).delete() } catch (_: Exception) {}
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
            try { File(outPath).delete() } catch (_: Exception) {}
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
            if (cancelled.get()) return false
            mixer.processSync()
            if (cancelled.get()) {
                try { File(outPath).delete() } catch (_: Exception) {}
                return false
            }
            File(outPath).exists() && File(outPath).length() > 500
        } catch (_: Throwable) {
            false
        }
    }

    companion object {
        const val ACTION_CANCEL = "com.audiomixer.app.CANCEL_MIX"
        const val ACTION_MIX_DONE = "com.audiomixer.app.MIX_DONE"
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
        const val EXTRA_DURATION_SEC = "durationSec"
        const val EXTRA_OVERLAYS = "overlays"
        const val KEY_OK = "ok"
        const val KEY_PATH = "path"
        const val KEY_ERROR = "error"
    }
}
