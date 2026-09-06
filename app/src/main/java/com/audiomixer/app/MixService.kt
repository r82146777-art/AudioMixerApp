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
 * Runs in a SEPARATE process (:mix) so if FFmpeg native code crashes,
 * only this process dies — MainActivity stays alive.
 */
class MixService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            stopSelf(startId)
            return START_NOT_STICKY
        }

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

        Thread {
            var ok = false
            var err = ""
            try {
                if (useFfmpeg) {
                    ok = runFfmpeg(
                        mainPath, bgPath, outPath,
                        mainVol, bgVol, mainSpeed, bgSpeed,
                        mainPitch, bgPitch, mainEcho, bgEcho, fade
                    )
                    if (!ok) {
                        ok = runJava(mainPath, bgPath, outPath, mainVol, bgVol)
                        if (!ok) err = "میکس ناموفق بود"
                    }
                } else {
                    ok = runJava(mainPath, bgPath, outPath, mainVol, bgVol)
                    if (!ok) err = "میکس ناموفق بود"
                }
            } catch (t: Throwable) {
                err = t.message ?: t.javaClass.simpleName
                try {
                    ok = runJava(mainPath, bgPath, outPath, mainVol, bgVol)
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
        if (sp != 1f) parts.add("atempo=$sp")
        val p = pitch.coerceIn(0.5f, 2f)
        if (p != 1f) {
            parts.add("asetrate=44100*$p")
            parts.add("aresample=44100")
            val comp = (1.0 / p).toFloat().coerceIn(0.5f, 2f)
            parts.add("atempo=$comp")
        }
        if (echo > 0.05f) {
            val g = (0.5f * echo).coerceIn(0.1f, 0.8f)
            parts.add("aecho=0.8:$g:60:0.4")
        }
        return parts.joinToString(",")
    }

    private fun runFfmpeg(
        mainPath: String, bgPath: String, outPath: String,
        mainVol: Float, bgVol: Float,
        mainSpeed: Float, bgSpeed: Float,
        mainPitch: Float, bgPitch: Float,
        mainEcho: Float, bgEcho: Float,
        fade: Boolean
    ): Boolean {
        val mainF = trackFilter(mainVol, mainSpeed, mainPitch, mainEcho)
        val bgF = trackFilter(bgVol, bgSpeed, bgPitch, bgEcho)
        val filter = if (fade) {
            "[0:a]$mainF[a0];[1:a]$bgF[a1];[a0][a1]amix=inputs=2:duration=first:dropout_transition=2[am];[am]afade=t=in:st=0:d=1.5,afade=t=out:st=0:d=1.5[a]"
        } else {
            "[0:a]$mainF[a0];[1:a]$bgF[a1];[a0][a1]amix=inputs=2:duration=first:dropout_transition=2[a]"
        }
        val args = arrayOf(
            "-y",
            "-i", mainPath,
            "-stream_loop", "-1",
            "-i", bgPath,
            "-filter_complex", filter,
            "-map", "[a]",
            "-c:a", "aac", "-b:a", "128k",
            "-shortest", outPath
        )
        val session = FFmpegKit.executeWithArguments(args)
        val out = File(outPath)
        if (ReturnCode.isSuccess(session.returnCode) && out.exists() && out.length() > 200) return true

        // ultra simple fallback
        val args2 = arrayOf(
            "-y",
            "-i", mainPath,
            "-stream_loop", "-1",
            "-i", bgPath,
            "-filter_complex",
            "[0:a]volume=$mainVol[a0];[1:a]volume=$bgVol[a1];[a0][a1]amix=inputs=2:duration=first[a]",
            "-map", "[a]",
            "-c:a", "aac", "-b:a", "128k",
            "-shortest", outPath
        )
        val session2 = FFmpegKit.executeWithArguments(args2)
        return ReturnCode.isSuccess(session2.returnCode) && out.exists() && out.length() > 200
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
        mixer.setBitRate(128000)
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
        const val KEY_OK = "ok"
        const val KEY_PATH = "path"
        const val KEY_ERROR = "error"
        const val RESULT_OK = 1
        const val RESULT_FAIL = 0
    }
}
