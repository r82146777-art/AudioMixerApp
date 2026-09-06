package com.audiomixer.app

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import com.audiomixer.app.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import zeroonezero.android.audio_mixer.AudioMixer
import zeroonezero.android.audio_mixer.input.GeneralAudioInput
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: SharedPreferences

    private var mainFile: File? = null
    private var bgFile: File? = null
    private var mainFileName: String = ""
    private var bgFileName: String = ""

    private var mainVolume = 1.0f
    private var bgVolume = 0.5f
    private var mainSpeed = 1.0f
    private var bgSpeed = 1.0f
    private var mainPitch = 1.0f
    private var bgPitch = 1.0f
    private var mainEcho = 0f
    private var bgEcho = 0f

    private var outputFile: File? = null
    private var mediaPlayer: MediaPlayer? = null
    private var isPlaying = false
    private var isMixing = false

    private var mediaRecorder: MediaRecorder? = null
    private var recordedFile: File? = null
    private var isRecording = false

    private val uiHandler = Handler(Looper.getMainLooper())
    private var progressRunnable: Runnable? = null
    private var mixJob: Job? = null
    private val cancelFlag = AtomicBoolean(false)

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (!permissions.values.all { it }) {
            Toast.makeText(this, getString(R.string.permission_needed), Toast.LENGTH_LONG).show()
        }
    }

    private val selectMainLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri == null) {
            Toast.makeText(this, "انتخاب فایل اصلی لغو شد", Toast.LENGTH_SHORT).show()
            return@registerForActivityResult
        }
        onMainSelected(uri)
    }

    private val selectBgLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri == null) {
            Toast.makeText(this, "انتخاب فایل پس‌زمینه لغو شد", Toast.LENGTH_SHORT).show()
            return@registerForActivityResult
        }
        onBgSelected(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        prefs = getSharedPreferences("audiomixer_prefs", Context.MODE_PRIVATE)

        if (savedInstanceState != null) {
            mainFileName = savedInstanceState.getString("mainFileName") ?: ""
            bgFileName = savedInstanceState.getString("bgFileName") ?: ""
            savedInstanceState.getString("mainPath")?.let { path ->
                val f = File(path)
                if (f.exists()) {
                    mainFile = f
                    updateMainUi("✅ ${mainFileName.ifBlank { f.name }}")
                }
            }
            savedInstanceState.getString("bgPath")?.let { path ->
                val f = File(path)
                if (f.exists()) {
                    bgFile = f
                    updateBgUi("✅ ${bgFileName.ifBlank { f.name }}")
                }
            }
        }

        checkPermissions()
        setupButtons()
        showInviteIfNeeded()
    }

    override fun onDestroy() {
        super.onDestroy()
        cancelFlag.set(true)
        mixJob?.cancel()
        stopProgress()
        try { FFmpegKit.cancel() } catch (_: Throwable) {}
        mediaPlayer?.release()
        mediaPlayer = null
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.menu_guide -> {
                showTextDialog(getString(R.string.menu_guide), getString(R.string.guide_text)); true
            }
            R.id.menu_about -> {
                showAboutDialog(); true
            }
            R.id.menu_effects -> {
                startActivity(Intent(this, EffectsActivity::class.java)); true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("mainFileName", mainFileName)
        outState.putString("bgFileName", bgFileName)
        outState.putString("mainPath", mainFile?.absolutePath)
        outState.putString("bgPath", bgFile?.absolutePath)
    }

    private fun checkPermissions() {
        val permissions = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_AUDIO) != PackageManager.PERMISSION_GRANTED)
                permissions.add(Manifest.permission.READ_MEDIA_AUDIO)
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED)
                permissions.add(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
            permissions.add(Manifest.permission.RECORD_AUDIO)
        if (permissions.isNotEmpty()) requestPermissionLauncher.launch(permissions.toTypedArray())
    }

    private fun setupButtons() {
        binding.btnSelectMain.setOnClickListener { selectMainLauncher.launch("audio/*") }
        binding.btnSelectBg.setOnClickListener { selectBgLauncher.launch("audio/*") }
        binding.btnMainSettings.setOnClickListener { showFileSettingsDialog(true) }
        binding.btnBgSettings.setOnClickListener { showFileSettingsDialog(false) }
        binding.btnEffects.setOnClickListener { startActivity(Intent(this, EffectsActivity::class.java)) }
        binding.btnMix.setOnClickListener {
            if (isMixing) {
                Toast.makeText(this, "میکس در حال انجام است...", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (mainFile == null || !mainFile!!.exists()) {
                Toast.makeText(this, "اول فایل اصلی را انتخاب کنید", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (bgFile == null || !bgFile!!.exists()) {
                Toast.makeText(this, "اول فایل پس‌زمینه را انتخاب کنید", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            doMix()
        }
        binding.btnReset.setOnClickListener { resetAll() }
        binding.btnPlay.setOnClickListener { togglePlay() }
        binding.btnSave.setOnClickListener { saveToDownloads() }
        binding.btnRecord.setOnClickListener { toggleRecord() }
        binding.btnDeleteRecord.setOnClickListener { deleteRecord() }
        binding.btnUseRecordAsMain.setOnClickListener { useRecordAsMain() }
    }

    private fun onMainSelected(uri: Uri) {
        val name = resolveName(uri)
        mainFileName = name
        updateMainUi("در حال کپی... $name")
        Toast.makeText(this, "فایل اصلی انتخاب شد: $name", Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            val file = withContext(Dispatchers.IO) { copyUriToCache(uri, "main", name) }
            if (file != null && file.exists()) {
                mainFile = file
                updateMainUi("✅ $name")
                Toast.makeText(this@MainActivity, "فایل اصلی آماده است", Toast.LENGTH_SHORT).show()
            } else {
                mainFile = null
                updateMainUi("❌ خطا در خواندن فایل")
            }
        }
    }

    private fun onBgSelected(uri: Uri) {
        val name = resolveName(uri)
        bgFileName = name
        updateBgUi("در حال کپی... $name")
        Toast.makeText(this, "فایل پس‌زمینه انتخاب شد: $name", Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            val file = withContext(Dispatchers.IO) { copyUriToCache(uri, "bg", name) }
            if (file != null && file.exists()) {
                bgFile = file
                updateBgUi("✅ $name")
                Toast.makeText(this@MainActivity, "فایل پس‌زمینه آماده است", Toast.LENGTH_SHORT).show()
            } else {
                bgFile = null
                updateBgUi("❌ خطا در خواندن فایل")
            }
        }
    }

    private fun updateMainUi(text: String) {
        binding.tvMainFile.text = text
        binding.tvMainFile.setTextColor(if (text.startsWith("✅")) Color.parseColor("#1B5E20") else Color.BLACK)
        binding.tvMainFile.contentDescription = "فایل اصلی: $text"
        binding.btnSelectMain.text = if (text.startsWith("✅")) "تغییر فایل اصلی" else getString(R.string.select_main_file)
    }

    private fun updateBgUi(text: String) {
        binding.tvBgFile.text = text
        binding.tvBgFile.setTextColor(if (text.startsWith("✅")) Color.parseColor("#1B5E20") else Color.BLACK)
        binding.tvBgFile.contentDescription = "فایل پس‌زمینه: $text"
        binding.btnSelectBg.text = if (text.startsWith("✅")) "تغییر فایل پس‌زمینه" else getString(R.string.select_bg_file)
    }

    private fun resolveName(uri: Uri): String {
        var name: String? = null
        try {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) name = c.getString(idx)
                }
            }
        } catch (_: Exception) {}
        if (name.isNullOrBlank()) {
            name = uri.lastPathSegment?.substringAfterLast('/') ?: "audio_${System.currentTimeMillis()}"
        }
        return name!!
    }

    /** Copy with real extension so decoders work */
    private fun copyUriToCache(uri: Uri, prefix: String, displayName: String): File? {
        return try {
            val input = contentResolver.openInputStream(uri) ?: return null
            val ext = displayName.substringAfterLast('.', "").lowercase().let { e ->
                when (e) {
                    "mp3", "m4a", "aac", "wav", "ogg", "flac", "3gp", "amr" -> e
                    else -> {
                        val mime = contentResolver.getType(uri) ?: ""
                        when {
                            mime.contains("mpeg") || mime.contains("mp3") -> "mp3"
                            mime.contains("mp4") || mime.contains("m4a") || mime.contains("aac") -> "m4a"
                            mime.contains("wav") -> "wav"
                            mime.contains("ogg") -> "ogg"
                            else -> "m4a"
                        }
                    }
                }
            }
            val file = File(cacheDir, "${prefix}_${System.currentTimeMillis()}.$ext")
            FileOutputStream(file).use { out -> input.copyTo(out) }
            input.close()
            if (file.exists() && file.length() > 0) file else null
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private fun showFileSettingsDialog(isMain: Boolean) {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 24)
        }
        fun addSeek(label: String, max: Int, progress: Int, onChange: (Int) -> Unit) {
            val tv = TextView(this).apply { text = "$label: $progress"; contentDescription = label }
            layout.addView(tv)
            layout.addView(SeekBar(this).apply {
                this.max = max
                this.progress = progress
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(s: SeekBar?, p: Int, f: Boolean) {
                        tv.text = "$label: $p"
                        onChange(p)
                    }
                    override fun onStartTrackingTouch(s: SeekBar?) {}
                    override fun onStopTrackingTouch(s: SeekBar?) {}
                })
            })
        }
        val vol = if (isMain) (mainVolume * 100).toInt() else (bgVolume * 100).toInt()
        val spd = if (isMain) (mainSpeed * 100).toInt() else (bgSpeed * 100).toInt()
        val pit = if (isMain) (mainPitch * 100).toInt() else (bgPitch * 100).toInt()
        val echo = if (isMain) (mainEcho * 100).toInt() else (bgEcho * 100).toInt()

        addSeek("صدا (۰–۱۰۰)", 100, vol) { p -> if (isMain) mainVolume = p / 100f else bgVolume = p / 100f }
        addSeek("سرعت (۵۰–۲۰۰٪)", 200, spd.coerceIn(50, 200)) { p ->
            val v = p.coerceIn(50, 200) / 100f
            if (isMain) mainSpeed = v else bgSpeed = v
        }
        addSeek("زیر و بمی (۵۰–۲۰۰٪)", 200, pit.coerceIn(50, 200)) { p ->
            val v = p.coerceIn(50, 200) / 100f
            if (isMain) mainPitch = v else bgPitch = v
        }
        addSeek("اکو (۰–۱۰۰)", 100, echo) { p -> if (isMain) mainEcho = p / 100f else bgEcho = p / 100f }

        AlertDialog.Builder(this)
            .setTitle(if (isMain) "تنظیمات فایل اصلی" else "تنظیمات فایل پس‌زمینه")
            .setView(layout)
            .setPositiveButton("تأیید", null)
            .show()
    }

    private fun showInviteIfNeeded() {
        if (prefs.getBoolean("dont_show_invite", false)) return
        val checkBox = android.widget.CheckBox(this).apply { text = getString(R.string.invite_dont_show) }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.invite_title))
            .setMessage(getString(R.string.invite_message))
            .setView(checkBox)
            .setPositiveButton(getString(R.string.invite_join)) { _, _ ->
                if (checkBox.isChecked) prefs.edit().putBoolean("dont_show_invite", true).apply()
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://t.me/Akademi_hamdel")))
                } catch (_: Exception) {}
            }
            .setNegativeButton(getString(R.string.invite_cancel)) { _, _ ->
                if (checkBox.isChecked) prefs.edit().putBoolean("dont_show_invite", true).apply()
            }
            .setCancelable(false)
            .show()
    }

    private fun showTextDialog(title: String, message: String) {
        AlertDialog.Builder(this).setTitle(title).setMessage(message).setPositiveButton("باشه", null).show()
    }

    private fun showAboutDialog() {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.menu_about))
            .setMessage(getString(R.string.about_text))
            .setPositiveButton(getString(R.string.copy_link)) { _, _ ->
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("channel", "https://t.me/Akademi_hamdel"))
                Toast.makeText(this, getString(R.string.link_copied), Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("بستن", null)
            .show()
    }

    private fun getAudioDurationSec(file: File): Double {
        return try {
            val mmr = MediaMetadataRetriever()
            mmr.setDataSource(file.absolutePath)
            val d = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            mmr.release()
            d / 1000.0
        } catch (_: Exception) {
            0.0
        }
    }

    private fun needsFx(): Boolean {
        return kotlin.math.abs(mainSpeed - 1f) > 0.02f || kotlin.math.abs(bgSpeed - 1f) > 0.02f ||
            kotlin.math.abs(mainPitch - 1f) > 0.02f || kotlin.math.abs(bgPitch - 1f) > 0.02f ||
            mainEcho > 0.05f || bgEcho > 0.05f || binding.cbFade.isChecked
    }

    private fun f(v: Float) = String.format(Locale.US, "%.3f", v)
    private fun d(v: Double) = String.format(Locale.US, "%.3f", v)

    private fun doMix() {
        val main = mainFile ?: return
        val bg = bgFile ?: return
        if (!main.exists() || !bg.exists()) {
            Toast.makeText(this, "فایل‌ها پیدا نشدند. دوباره انتخاب کنید.", Toast.LENGTH_LONG).show()
            return
        }

        isMixing = true
        cancelFlag.set(false)
        binding.progressBar.visibility = View.VISIBLE
        binding.progressBar.isIndeterminate = false
        binding.progressBar.max = 100
        binding.progressBar.progress = 5
        binding.btnMix.isEnabled = false
        binding.btnPlay.isEnabled = false
        binding.btnSave.isEnabled = false

        val durationSec = getAudioDurationSec(main).let { if (it < 0.5) 0.0 else it }
        val mins = if (durationSec > 0) durationSec / 60.0 else 0.0
        binding.tvStatus.text = if (mins >= 1) {
            String.format(Locale.US, "در حال میکس فایل %.0f دقیقه‌ای...", mins)
        } else {
            "در حال میکس... لطفاً صبر کنید"
        }

        val useMp3 = binding.rbMp3.isChecked
        val useWav = binding.rbWav.isChecked
        val outExt = when {
            useWav -> "wav"
            useMp3 -> "mp3"
            else -> "m4a"
        }
        // Encoder: wav=pcm, mp3 via libmp3lame may not exist in ffmpeg-kit-audio → use aac in m4a container labeled mp3 request fallback to m4a
        val outFile = File(cacheDir, "mixed_${System.currentTimeMillis()}.$outExt")

        startFakeProgress()

        mixJob = lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                mixInternal(main, bg, outFile, durationSec, outExt)
            }
            if (!isActive) return@launch
            if (result.first != null) {
                finishMix(result.first, "")
            } else {
                finishMix(null, result.second)
            }
        }
    }

    /**
     * Returns Pair(successFile, errorMessage)
     */
    private fun mixInternal(main: File, bg: File, outFile: File, durationSec: Double, outExt: String): Pair<File?, String> {
        try {
            if (cancelFlag.get()) return Pair(null, "میکس لغو شد")
            try { outFile.delete() } catch (_: Exception) {}

            val overlays = parseOverlays()
            val fx = needsFx() || overlays.isNotEmpty()

            // 1) Simple volume mix with Java library (most reliable for short/medium)
            if (!fx) {
                val ok = runJavaMix(main, bg, outFile)
                if (ok) return Pair(outFile, "")
            }

            if (cancelFlag.get()) return Pair(null, "میکس لغو شد")

            // 2) FFmpeg (handles long files, FX, overlays)
            val okFf = runFfmpegMix(main, bg, outFile, durationSec, outExt, overlays)
            if (okFf) return Pair(outFile, "")

            if (cancelFlag.get()) return Pair(null, "میکس لغو شد")

            // 3) Last resort Java again
            val ok2 = runJavaMix(main, bg, outFile)
            if (ok2) return Pair(outFile, "")

            return Pair(null, "میکس انجام نشد. فرمت فایل را بررسی کنید یا فایل دیگری امتحان کنید.")
        } catch (t: Throwable) {
            return Pair(null, "خطا: ${t.message ?: "نامشخص"}")
        }
    }

    private fun parseOverlays(): List<Pair<String, Long>> {
        val raw = prefs.getString(EffectsActivity.KEY_OVERLAYS, "") ?: ""
        if (raw.isBlank()) return emptyList()
        return raw.split(';').mapNotNull { part ->
            val bits = part.split('|')
            if (bits.size < 2) return@mapNotNull null
            val path = bits[0]
            val ms = bits[1].toLongOrNull() ?: return@mapNotNull null
            if (!File(path).exists()) return@mapNotNull null
            path to ms.coerceAtLeast(0L)
        }.take(8)
    }

    private fun runJavaMix(main: File, bg: File, outFile: File): Boolean {
        if (cancelFlag.get()) return false
        return try {
            try { outFile.delete() } catch (_: Exception) {}
            // Java mixer outputs AAC/m4a-style; if user asked wav/mp3, still produce then rename if needed
            val tempOut = if (outFile.extension.lowercase() == "m4a" || outFile.extension.lowercase() == "mp3") {
                outFile
            } else {
                File(cacheDir, "tmp_java_${System.currentTimeMillis()}.m4a")
            }
            val mixer = AudioMixer(tempOut.absolutePath)
            val in1 = GeneralAudioInput(main.absolutePath)
            in1.setVolume(mainVolume.coerceIn(0f, 2f))
            val in2 = GeneralAudioInput(bg.absolutePath)
            in2.setVolume(bgVolume.coerceIn(0f, 2f))
            val d1 = try { in1.durationUs } catch (_: Exception) { 0L }
            val d2 = try { in2.durationUs } catch (_: Exception) { 0L }
            if (d1 > 0 && d2 > d1) {
                try { in2.setEndTimeUs(d1) } catch (_: Exception) {}
            }
            try { mixer.setLoopingEnabled(true) } catch (_: Exception) {}
            mixer.addDataSource(in1)
            mixer.addDataSource(in2)
            mixer.setSampleRate(44100)
            mixer.setBitRate(128000)
            mixer.setChannelCount(2)
            mixer.start()
            if (cancelFlag.get()) return false
            mixer.processSync()
            if (cancelFlag.get()) {
                try { tempOut.delete() } catch (_: Exception) {}
                return false
            }
            if (!tempOut.exists() || tempOut.length() < 500) return false
            if (tempOut.absolutePath != outFile.absolutePath) {
                tempOut.copyTo(outFile, overwrite = true)
                tempOut.delete()
            }
            outFile.exists() && outFile.length() > 500
        } catch (_: Throwable) {
            false
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

    private fun runFfmpegMix(
        main: File, bg: File, outFile: File,
        durationSec: Double, outExt: String,
        overlays: List<Pair<String, Long>>
    ): Boolean {
        if (cancelFlag.get()) return false
        return try {
            try { outFile.delete() } catch (_: Exception) {}
            val t = if (durationSec > 0.5) durationSec else 0.0

            val mainF = buildTrack(mainVolume, mainSpeed, mainPitch, mainEcho)
            val bgF = buildTrack(bgVolume, bgSpeed, bgPitch, bgEcho)

            val args = ArrayList<String>()
            args.add("-y")
            args.add("-i")
            args.add(main.absolutePath)
            // Loop background until main ends
            args.add("-stream_loop")
            args.add("-1")
            args.add("-i")
            args.add(bg.absolutePath)
            for ((path, _) in overlays) {
                args.add("-i")
                args.add(path)
            }

            val filter = StringBuilder()
            filter.append("[0:a]$mainF[a0];[1:a]$bgF[a1]")
            val labels = ArrayList<String>()
            labels.add("[a0]")
            labels.add("[a1]")
            overlays.forEachIndexed { i, o ->
                val idx = i + 2
                filter.append(";[$idx:a]volume=1.0,adelay=${o.second}|${o.second}[e$i]")
                labels.add("[e$i]")
            }
            filter.append(";")
            filter.append(labels.joinToString(""))
            filter.append("amix=inputs=${labels.size}:duration=first:dropout_transition=2")

            if (binding.cbFade.isChecked && t > 3.0) {
                val outStart = d((t - 1.5).coerceAtLeast(0.0))
                filter.append("[am];[am]afade=t=in:st=0:d=1.0,afade=t=out:st=$outStart:d=1.5[a]")
            } else if (binding.cbFade.isChecked) {
                filter.append("[am];[am]afade=t=in:st=0:d=0.5,afade=t=out:st=0:d=0.5[a]")
            } else {
                filter.append("[a]")
            }

            args.add("-filter_complex")
            args.add(filter.toString())
            args.add("-map")
            args.add("[a]")

            when (outExt) {
                "wav" -> {
                    args.add("-c:a")
                    args.add("pcm_s16le")
                }
                else -> {
                    // m4a / mp3 request: AAC is always available in ffmpeg-kit-audio
                    args.add("-c:a")
                    args.add("aac")
                    args.add("-b:a")
                    args.add(if (t > 900) "64k" else "128k")
                }
            }
            args.add("-ac")
            args.add("2")
            args.add("-ar")
            args.add("44100")

            // Always bound length so FFmpeg cannot hang forever
            if (t > 0.5) {
                args.add("-t")
                args.add(d(t))
            } else {
                args.add("-shortest")
            }
            args.add(outFile.absolutePath)

            val session = FFmpegKit.executeWithArguments(args.toTypedArray())
            if (cancelFlag.get()) {
                try { outFile.delete() } catch (_: Exception) {}
                return false
            }
            ReturnCode.isSuccess(session.returnCode) && outFile.exists() && outFile.length() > 200
        } catch (_: Throwable) {
            false
        }
    }

    private fun startFakeProgress() {
        stopProgress()
        var p = 5
        progressRunnable = object : Runnable {
            override fun run() {
                if (!isMixing) return
                if (p < 90) {
                    p += if (p < 30) 3 else if (p < 60) 2 else 1
                    binding.progressBar.progress = p
                    binding.tvStatus.text = "در حال میکس... $p٪"
                }
                uiHandler.postDelayed(this, 700)
            }
        }
        uiHandler.post(progressRunnable!!)
    }

    private fun stopProgress() {
        progressRunnable?.let { uiHandler.removeCallbacks(it) }
        progressRunnable = null
    }

    private fun finishMix(file: File?, error: String) {
        isMixing = false
        stopProgress()
        binding.btnMix.isEnabled = true

        if (file != null && file.exists() && file.length() > 200) {
            outputFile = file
            binding.progressBar.progress = 100
            binding.progressBar.visibility = View.GONE
            binding.tvStatus.text = "✅ میکس آماده است — پخش یا ذخیره کنید"
            binding.btnPlay.isEnabled = true
            binding.btnSave.isEnabled = true
            Toast.makeText(this, "میکس با موفقیت انجام شد", Toast.LENGTH_SHORT).show()
        } else {
            binding.progressBar.visibility = View.GONE
            binding.tvStatus.text = "❌ ${error.ifEmpty { "میکس ناموفق" }}"
            binding.btnPlay.isEnabled = false
            binding.btnSave.isEnabled = false
            Toast.makeText(this, error.ifEmpty { "میکس ناموفق" }, Toast.LENGTH_LONG).show()
        }
    }

    private fun resetAll() {
        cancelFlag.set(true)
        mixJob?.cancel()
        try { FFmpegKit.cancel() } catch (_: Throwable) {}
        isMixing = false
        stopProgress()
        binding.btnMix.isEnabled = true
        binding.progressBar.visibility = View.GONE

        try { mediaPlayer?.stop() } catch (_: Exception) {}
        mediaPlayer?.release()
        mediaPlayer = null
        isPlaying = false

        mainFile = null
        bgFile = null
        mainFileName = ""
        bgFileName = ""
        outputFile = null
        mainVolume = 1f
        bgVolume = 0.5f
        mainSpeed = 1f
        bgSpeed = 1f
        mainPitch = 1f
        bgPitch = 1f
        mainEcho = 0f
        bgEcho = 0f
        try { binding.cbFade.isChecked = false } catch (_: Exception) {}

        updateMainUi(getString(R.string.no_file_selected))
        updateBgUi(getString(R.string.no_file_selected))
        binding.tvStatus.text = "آماده"
        binding.btnPlay.isEnabled = false
        binding.btnSave.isEnabled = false
        Toast.makeText(this, "بازنشانی شد", Toast.LENGTH_SHORT).show()
    }

    private fun togglePlay() {
        val f = outputFile
        if (f == null || !f.exists()) {
            Toast.makeText(this, "ابتدا میکس را انجام دهید", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            if (isPlaying) {
                mediaPlayer?.pause()
                isPlaying = false
                binding.btnPlay.text = getString(R.string.play_result)
            } else {
                if (mediaPlayer == null) {
                    val player = MediaPlayer()
                    player.setDataSource(f.absolutePath)
                    player.setOnCompletionListener {
                        this@MainActivity.isPlaying = false
                        binding.btnPlay.text = getString(R.string.play_result)
                    }
                    player.prepare()
                    mediaPlayer = player
                }
                mediaPlayer?.start()
                isPlaying = true
                binding.btnPlay.text = getString(R.string.pause_result)
            }
        } catch (e: Exception) {
            Toast.makeText(this, "پخش ممکن نیست: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun saveToDownloads() {
        val f = outputFile
        if (f == null || !f.exists()) {
            Toast.makeText(this, "فایلی برای ذخیره نیست", Toast.LENGTH_SHORT).show()
            return
        }
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) {
                try {
                    val name = "mixed_${System.currentTimeMillis()}.${f.extension.ifBlank { "m4a" }}"
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        val mime = when (f.extension.lowercase()) {
                            "wav" -> "audio/wav"
                            "mp3" -> "audio/mpeg"
                            else -> "audio/mp4"
                        }
                        val values = ContentValues().apply {
                            put(MediaStore.Audio.Media.DISPLAY_NAME, name)
                            put(MediaStore.Audio.Media.MIME_TYPE, mime)
                            put(MediaStore.Audio.Media.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                        }
                        val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                            ?: return@withContext false
                        contentResolver.openOutputStream(uri)?.use { out ->
                            FileInputStream(f).use { it.copyTo(out) }
                        } ?: return@withContext false
                        true
                    } else {
                        val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                        if (!dir.exists()) dir.mkdirs()
                        val dest = File(dir, name)
                        FileInputStream(f).use { input ->
                            FileOutputStream(dest).use { output -> input.copyTo(output) }
                        }
                        true
                    }
                } catch (_: Exception) {
                    false
                }
            }
            if (ok) Toast.makeText(this@MainActivity, "در Downloads ذخیره شد", Toast.LENGTH_LONG).show()
            else Toast.makeText(this@MainActivity, "ذخیره ناموفق", Toast.LENGTH_LONG).show()
        }
    }

    private fun toggleRecord() {
        if (isRecording) {
            try { mediaRecorder?.stop() } catch (_: Exception) {}
            mediaRecorder?.release()
            mediaRecorder = null
            isRecording = false
            binding.btnRecord.text = getString(R.string.record_start)
            binding.btnDeleteRecord.isEnabled = recordedFile != null
            binding.btnUseRecordAsMain.isEnabled = recordedFile != null
            if (recordedFile != null && recordedFile!!.exists()) {
                Toast.makeText(this, "ضبط تمام شد", Toast.LENGTH_SHORT).show()
            }
            return
        }
        try {
            val out = File(cacheDir, "rec_${System.currentTimeMillis()}.m4a")
            recordedFile = out
            val recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(this)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }
            recorder.setAudioSource(MediaRecorder.AudioSource.MIC)
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            recorder.setOutputFile(out.absolutePath)
            recorder.prepare()
            recorder.start()
            mediaRecorder = recorder
            isRecording = true
            binding.btnRecord.text = getString(R.string.record_stop)
            Toast.makeText(this, "در حال ضبط...", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "ضبط ممکن نیست: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun deleteRecord() {
        if (isRecording) toggleRecord()
        recordedFile?.delete()
        recordedFile = null
        binding.btnDeleteRecord.isEnabled = false
        binding.btnUseRecordAsMain.isEnabled = false
        Toast.makeText(this, "ضبط حذف شد", Toast.LENGTH_SHORT).show()
    }

    private fun useRecordAsMain() {
        val f = recordedFile
        if (f == null || !f.exists()) {
            Toast.makeText(this, "فایل ضبط‌شده‌ای نیست", Toast.LENGTH_SHORT).show()
            return
        }
        mainFile = f
        mainFileName = f.name
        updateMainUi("✅ ${f.name}")
        Toast.makeText(this, "ضبط به عنوان فایل اصلی تنظیم شد", Toast.LENGTH_SHORT).show()
    }
}
