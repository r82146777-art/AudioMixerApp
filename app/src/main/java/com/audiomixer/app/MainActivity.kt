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
import com.audiomixer.app.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.ceil

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: SharedPreferences

    private var mainFile: File? = null
    private var bgFile: File? = null
    private var mainFileName: String = ""
    private var bgFileName: String = ""

    private var mainVolume = 1.0f
    private var bgVolume = 0.5f
    private var mixSpeed = 1.0f
    private var mixPitch = 1.0f
    private var eqGain = 50
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
    private var lastProgress = 5

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (!permissions.values.all { it }) {
            Toast.makeText(this, getString(R.string.permission_needed), Toast.LENGTH_LONG).show()
        }
    }

    private val selectMainLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri -> if (uri != null) onMainSelected(uri) }

    private val selectBgLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri -> if (uri != null) onBgSelected(uri) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        prefs = getSharedPreferences("audiomixer_prefs", Context.MODE_PRIVATE)

        binding.tvMainFile.visibility = View.VISIBLE
        binding.tvBgFile.visibility = View.VISIBLE
        binding.tvMainFile.text = getString(R.string.no_file_selected)
        binding.tvBgFile.text = getString(R.string.no_file_selected)

        if (savedInstanceState != null) {
            mainFileName = savedInstanceState.getString("mainFileName") ?: ""
            bgFileName = savedInstanceState.getString("bgFileName") ?: ""
            savedInstanceState.getString("mainPath")?.let { path ->
                val f = File(path)
                if (f.exists()) {
                    mainFile = f
                    showMainSelected(mainFileName.ifBlank { f.name }, f.length())
                }
            }
            savedInstanceState.getString("bgPath")?.let { path ->
                val f = File(path)
                if (f.exists()) {
                    bgFile = f
                    showBgSelected(bgFileName.ifBlank { f.name }, f.length())
                }
            }
        }
        checkPermissions()
        setupButtons()
        setupMixSliders()
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
            R.id.menu_guide -> { showTextDialog(getString(R.string.menu_guide), getString(R.string.guide_text)); true }
            R.id.menu_about -> { showAboutDialog(); true }
            R.id.menu_effects -> { startActivity(Intent(this, EffectsActivity::class.java)); true }
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
        binding.btnSelectMain.setOnClickListener { selectMainLauncher.launch("*/*") }
        binding.btnSelectBg.setOnClickListener { selectBgLauncher.launch("*/*") }
        binding.btnMainSettings.setOnClickListener { showVolumeDialog(true) }
        binding.btnBgSettings.setOnClickListener { showVolumeDialog(false) }
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

    private fun setupMixSliders() {
        binding.seekMixSpeed.progress = 100
        binding.seekMixPitch.progress = 100
        binding.seekEq.progress = 50
        bindSeek(binding.seekMixSpeed, 50) { p ->
            mixSpeed = p / 100f
            binding.tvMixSpeed.text = "سرعت میکس: $p٪"
        }
        bindSeek(binding.seekMixPitch, 50) { p ->
            mixPitch = p / 100f
            binding.tvMixPitch.text = "زیر و بمی میکس: $p٪"
        }
        bindSeek(binding.seekEq, 0) { p ->
            eqGain = p
            binding.tvEq.text = when {
                p < 45 -> "اکولایزر: بم ($p)"
                p > 55 -> "اکولایزر: زیر ($p)"
                else -> "اکولایزر: عادی"
            }
        }
        binding.tvMixSpeed.text = "سرعت میکس: ۱۰۰٪"
        binding.tvMixPitch.text = "زیر و بمی میکس: ۱۰۰٪"
        binding.tvEq.text = "اکولایزر: عادی"
    }

    private fun bindSeek(bar: SeekBar, min: Int, onValue: (Int) -> Unit) {
        bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                onValue(p.coerceAtLeast(min))
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
    }

    private fun onMainSelected(uri: Uri) {
        val name = resolveName(uri)
        mainFileName = name
        showMainSelected(name, 0)
        binding.tvMainFile.text = "در حال آماده‌سازی:\n$name"
        Toast.makeText(this, "انتخاب شد: $name", Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            val file = withContext(Dispatchers.IO) { copyUriToCache(uri, "main", name) }
            if (file != null && file.exists() && file.length() > 0) {
                mainFile = file
                showMainSelected(name, file.length())
                Toast.makeText(this@MainActivity, "فایل اصلی آماده شد", Toast.LENGTH_SHORT).show()
            } else {
                mainFile = null
                binding.tvMainFile.visibility = View.VISIBLE
                binding.tvMainFile.text = "خطا در خواندن فایل:\n$name"
                binding.tvMainFile.setTextColor(Color.RED)
                binding.btnSelectMain.text = "انتخاب دوباره فایل اصلی"
                Toast.makeText(this@MainActivity, "فایل خوانده نشد. دوباره انتخاب کنید", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun onBgSelected(uri: Uri) {
        val name = resolveName(uri)
        bgFileName = name
        showBgSelected(name, 0)
        binding.tvBgFile.text = "در حال آماده‌سازی:\n$name"
        Toast.makeText(this, "انتخاب شد: $name", Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            val file = withContext(Dispatchers.IO) { copyUriToCache(uri, "bg", name) }
            if (file != null && file.exists() && file.length() > 0) {
                bgFile = file
                showBgSelected(name, file.length())
                Toast.makeText(this@MainActivity, "فایل پس‌زمینه آماده شد", Toast.LENGTH_SHORT).show()
            } else {
                bgFile = null
                binding.tvBgFile.visibility = View.VISIBLE
                binding.tvBgFile.text = "خطا در خواندن فایل:\n$name"
                binding.tvBgFile.setTextColor(Color.RED)
                binding.btnSelectBg.text = "انتخاب دوباره فایل پس‌زمینه"
                Toast.makeText(this@MainActivity, "فایل خوانده نشد. دوباره انتخاب کنید", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun showMainSelected(name: String, sizeBytes: Long) {
        val sizeStr = if (sizeBytes > 0) "  •  ${formatSize(sizeBytes)}" else ""
        val text = "فایل اصلی:\n$name$sizeStr"
        binding.tvMainFile.visibility = View.VISIBLE
        binding.tvMainFile.text = text
        binding.tvMainFile.setTextColor(Color.parseColor("#1B5E20"))
        binding.tvMainFile.textSize = 18f
        binding.btnSelectMain.text = "انتخاب شد: $name"
        binding.tvMainFile.contentDescription = "فایل اصلی انتخاب شده: $name"
        binding.btnSelectMain.contentDescription = "فایل اصلی: $name"
    }

    private fun showBgSelected(name: String, sizeBytes: Long) {
        val sizeStr = if (sizeBytes > 0) "  •  ${formatSize(sizeBytes)}" else ""
        val text = "فایل پس‌زمینه:\n$name$sizeStr"
        binding.tvBgFile.visibility = View.VISIBLE
        binding.tvBgFile.text = text
        binding.tvBgFile.setTextColor(Color.parseColor("#0D47A1"))
        binding.tvBgFile.textSize = 18f
        binding.btnSelectBg.text = "انتخاب شد: $name"
        binding.tvBgFile.contentDescription = "فایل پس‌زمینه انتخاب شده: $name"
        binding.btnSelectBg.contentDescription = "فایل پس‌زمینه: $name"
    }

    private fun formatSize(bytes: Long): String {
        return when {
            bytes >= 1_000_000 -> String.format(Locale.US, "%.1f MB", bytes / 1_000_000.0)
            bytes >= 1000 -> String.format(Locale.US, "%.0f KB", bytes / 1000.0)
            else -> "$bytes B"
        }
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
        return name!!.substringAfterLast('/')
    }

    private fun copyUriToCache(uri: Uri, prefix: String, displayName: String): File? {
        return try {
            val input = contentResolver.openInputStream(uri) ?: return null
            val ext = displayName.substringAfterLast('.', "").lowercase().let { e ->
                when (e) {
                    "mp3", "m4a", "aac", "wav", "ogg", "flac", "3gp", "amr", "opus" -> e
                    else -> {
                        val mime = try { contentResolver.getType(uri) ?: "" } catch (_: Exception) { "" }
                        when {
                            mime.contains("wav") -> "wav"
                            mime.contains("mpeg") || mime.contains("mp3") -> "mp3"
                            mime.contains("mp4") || mime.contains("m4a") || mime.contains("aac") -> "m4a"
                            mime.contains("ogg") -> "ogg"
                            else -> "wav"
                        }
                    }
                }
            }
            val file = File(cacheDir, "${prefix}_${System.currentTimeMillis()}.$ext")
            FileOutputStream(file).use { out -> input.copyTo(out, 64 * 1024) }
            try { input.close() } catch (_: Exception) {}
            if (file.exists() && file.length() > 0) file else null
        } catch (_: Exception) {
            null
        }
    }

    private fun showVolumeDialog(isMain: Boolean) {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 24)
        }
        fun addSeek(label: String, max: Int, progress: Int, onChange: (Int) -> Unit) {
            val tv = TextView(this).apply { text = "$label: $progress" }
            layout.addView(tv)
            layout.addView(SeekBar(this).apply {
                this.max = max
                this.progress = progress
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(s: SeekBar?, p: Int, f: Boolean) {
                        tv.text = "$label: $p"; onChange(p)
                    }
                    override fun onStartTrackingTouch(s: SeekBar?) {}
                    override fun onStopTrackingTouch(s: SeekBar?) {}
                })
            })
        }
        val vol = if (isMain) (mainVolume * 100).toInt() else (bgVolume * 100).toInt()
        val echo = if (isMain) (mainEcho * 100).toInt() else (bgEcho * 100).toInt()
        addSeek("صدا (۰–۱۰۰)", 100, vol) { p -> if (isMain) mainVolume = p / 100f else bgVolume = p / 100f }
        addSeek("اکو (۰–۱۰۰)", 100, echo) { p -> if (isMain) mainEcho = p / 100f else bgEcho = p / 100f }
        AlertDialog.Builder(this)
            .setTitle(if (isMain) "صدای فایل اصلی" else "صدای فایل پس‌زمینه")
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
                try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://t.me/Akademi_hamdel"))) } catch (_: Exception) {}
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

    private fun f(v: Float) = String.format(Locale.US, "%.4f", v)
    private fun d(v: Double) = String.format(Locale.US, "%.3f", v)

    private fun setProgressUi(pct: Int, msg: String) {
        lastProgress = pct.coerceIn(0, 99)
        uiHandler.post {
            if (!isMixing) return@post
            binding.progressBar.progress = lastProgress
            binding.tvStatus.text = msg
        }
    }

    private fun doMix() {
        val main = mainFile ?: return
        val bg = bgFile ?: return
        if (!main.exists() || !bg.exists()) {
            Toast.makeText(this, "فایل‌ها پیدا نشدند. دوباره انتخاب کنید.", Toast.LENGTH_LONG).show()
            return
        }

        isMixing = true
        cancelFlag.set(false)
        lastProgress = 5
        binding.progressBar.visibility = View.VISIBLE
        binding.progressBar.isIndeterminate = false
        binding.progressBar.max = 100
        binding.progressBar.progress = 5
        binding.btnMix.isEnabled = false
        binding.btnPlay.isEnabled = false
        binding.btnSave.isEnabled = false

        val mainDur = getAudioDurationSec(main).let { if (it < 0.4) 0.0 else it }
        val bgDur = getAudioDurationSec(bg).let { if (it < 0.2) 0.0 else it }
        val mins = if (mainDur > 0) mainDur / 60.0 else 0.0
        binding.tvStatus.text = if (mins >= 1)
            String.format(Locale.US, "در حال میکس فایل %.0f دقیقه‌ای...", mins)
        else
            "در حال میکس WAV..."

        val outFile = File(cacheDir, "mixed_${System.currentTimeMillis()}.wav")
        val fade = binding.cbFade.isChecked
        val mVol = mainVolume
        val bVol = bgVolume
        val speed = mixSpeed
        val pitch = mixPitch
        val eq = eqGain
        val echo = (mainEcho + bgEcho).coerceAtMost(1f)
        val overlays = parseOverlays()

        val globalTimeoutMs = when {
            mainDur > 1800 -> 10 * 60 * 1000L
            mainDur > 600 -> 5 * 60 * 1000L
            mainDur > 180 -> 3 * 60 * 1000L
            else -> 90 * 1000L
        }

        startProgressTicker()

        mixJob = lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                val r = withTimeoutOrNull(globalTimeoutMs) {
                    wavMix(main, bg, outFile, mainDur, bgDur, fade, mVol, bVol, speed, pitch, echo, eq, overlays)
                }
                when {
                    r == true -> true
                    else -> {
                        try { FFmpegKit.cancel() } catch (_: Throwable) {}
                        outFile.exists() && outFile.length() > 1000
                    }
                }
            }
            if (!isActive) return@launch
            if (result) finishMix(outFile, "")
            else finishMix(null, "میکس انجام نشد. فایل‌ها را دوباره انتخاب کنید.")
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
        }.take(6)
    }

    /**
     * Always encode PCM WAV. ffmpeg-kit-audio has no AAC encoder.
     * Speed / pitch / EQ / echo / fade are applied on the mixed result.
     */
    private fun wavMix(
        main: File, bg: File, outFile: File,
        mainDur: Double, bgDur: Double, fade: Boolean,
        mVol: Float, bVol: Float,
        speed: Float, pitch: Float, echo: Float, eq: Int,
        overlays: List<Pair<String, Long>>
    ): Boolean {
        try {
            if (cancelFlag.get()) return false
            try { outFile.delete() } catch (_: Exception) {}

            val t = if (mainDur > 0.4) mainDur else 0.0
            val extraLoops = if (t > 0.4 && bgDur > 0.25 && t > bgDur + 0.5) {
                (ceil(t / bgDur).toInt() - 1).coerceIn(0, 80)
            } else 0
            val timeout = when {
                t > 1800 -> 480L
                t > 600 -> 240L
                t > 180 -> 120L
                else -> 70L
            }

            setProgressUi(18, "در حال میکس WAV...")
            if (runFfmpegMix(main, bg, outFile, t, extraLoops, mVol, bVol, speed, pitch, echo, eq, fade, overlays, timeout)) {
                return true
            }
            if (cancelFlag.get()) return false

            setProgressUi(45, "میکس بدون افکت...")
            if (runFfmpegMix(main, bg, outFile, t, extraLoops, mVol, bVol, 1f, 1f, 0f, 50, false, emptyList(), timeout)) {
                val needFx = fade ||
                    kotlin.math.abs(speed - 1f) > 0.03f ||
                    kotlin.math.abs(pitch - 1f) > 0.03f ||
                    echo > 0.05f ||
                    kotlin.math.abs(eq - 50) > 3
                if (!needFx) return outFile.exists() && outFile.length() > 500
                setProgressUi(72, "اعمال افکت‌ها روی میکس...")
                val fxOut = File(cacheDir, "fx_${System.currentTimeMillis()}.wav")
                if (applyFx(outFile, fxOut, speed, pitch, echo, eq, fade, t, timeout) &&
                    fxOut.exists() && fxOut.length() > 500
                ) {
                    try { outFile.delete() } catch (_: Exception) {}
                    fxOut.copyTo(outFile, overwrite = true)
                    fxOut.delete()
                }
                return outFile.exists() && outFile.length() > 500
            }

            setProgressUi(82, "آخرین تلاش...")
            val simple = mutableListOf(
                "-y", "-hide_banner", "-loglevel", "error", "-threads", "0",
                "-i", main.absolutePath
            )
            if (extraLoops > 0) {
                simple.add("-stream_loop")
                simple.add(extraLoops.toString())
            }
            simple.add("-i")
            simple.add(bg.absolutePath)
            simple.add("-filter_complex")
            simple.add("amix=inputs=2:duration=first:dropout_transition=0")
            simple.add("-c:a")
            simple.add("pcm_s16le")
            simple.add("-ar")
            simple.add("44100")
            simple.add("-ac")
            simple.add("2")
            simple.add("-vn")
            if (t > 0.4) {
                simple.add("-t")
                simple.add(d(t))
            }
            simple.add(outFile.absolutePath)
            if (ffmpegOk(simple.toTypedArray(), outFile, timeout)) return true

            return outFile.exists() && outFile.length() > 500
        } catch (_: Throwable) {
            return outFile.exists() && outFile.length() > 500
        }
    }

    private fun runFfmpegMix(
        main: File, bg: File, outFile: File,
        t: Double, extraLoops: Int, mVol: Float, bVol: Float,
        speed: Float, pitch: Float, echo: Float, eq: Int, fade: Boolean,
        overlays: List<Pair<String, Long>>,
        timeoutSec: Long
    ): Boolean {
        try { outFile.delete() } catch (_: Exception) {}

        val sb = StringBuilder()
        if (t > 0.4) {
            sb.append("[0:a]aformat=sample_rates=44100:channel_layouts=stereo,atrim=duration=${d(t)},asetpts=PTS-STARTPTS,volume=${f(mVol.coerceIn(0.05f, 2f))}[a0];")
            sb.append("[1:a]aformat=sample_rates=44100:channel_layouts=stereo,atrim=duration=${d(t)},asetpts=PTS-STARTPTS,volume=${f(bVol.coerceIn(0.05f, 2f))}[a1]")
        } else {
            sb.append("[0:a]aformat=sample_rates=44100:channel_layouts=stereo,volume=${f(mVol.coerceIn(0.05f, 2f))}[a0];")
            sb.append("[1:a]aformat=sample_rates=44100:channel_layouts=stereo,volume=${f(bVol.coerceIn(0.05f, 2f))}[a1]")
        }
        val labels = ArrayList<String>()
        labels.add("[a0]"); labels.add("[a1]")
        overlays.forEachIndexed { i, o ->
            val delay = o.second.coerceAtLeast(1L)
            sb.append(";[${i + 2}:a]volume=1.0,adelay=${delay}|${delay}[e$i]")
            labels.add("[e$i]")
        }
        sb.append(";").append(labels.joinToString(""))
        sb.append("amix=inputs=${labels.size}:duration=first:dropout_transition=0:normalize=0[mix]")

        val post = buildPostFx(speed, pitch, echo, eq, fade, t)
        if (post.isNotBlank()) sb.append(";[mix]").append(post).append("[a]")
        else sb.append(";[mix]anull[a]")

        val args = ArrayList<String>()
        args.add("-y"); args.add("-hide_banner"); args.add("-loglevel"); args.add("error")
        args.add("-threads"); args.add("0")
        args.add("-i"); args.add(main.absolutePath)
        if (extraLoops > 0) {
            args.add("-stream_loop"); args.add(extraLoops.toString())
        }
        args.add("-i"); args.add(bg.absolutePath)
        for ((path, _) in overlays) {
            args.add("-i"); args.add(path)
        }
        args.add("-filter_complex"); args.add(sb.toString())
        args.add("-map"); args.add("[a]")
        args.add("-c:a"); args.add("pcm_s16le")
        args.add("-ar"); args.add("44100")
        args.add("-ac"); args.add("2")
        args.add("-vn")
        args.add(outFile.absolutePath)
        return ffmpegOk(args.toTypedArray(), outFile, timeoutSec)
    }

    private fun applyFx(
        src: File, dest: File,
        speed: Float, pitch: Float, echo: Float, eq: Int, fade: Boolean,
        t: Double, timeoutSec: Long
    ): Boolean {
        try { dest.delete() } catch (_: Exception) {}
        val af = buildPostFx(speed, pitch, echo, eq, fade, t)
        if (af.isBlank()) {
            src.copyTo(dest, overwrite = true)
            return dest.exists()
        }
        val args = arrayOf(
            "-y", "-hide_banner", "-loglevel", "error", "-threads", "0",
            "-i", src.absolutePath,
            "-af", af,
            "-c:a", "pcm_s16le",
            "-ar", "44100",
            "-ac", "2",
            dest.absolutePath
        )
        return ffmpegOk(args, dest, timeoutSec)
    }

    private fun buildPostFx(
        speed: Float, pitch: Float, echo: Float, eq: Int, fade: Boolean, dur: Double
    ): String {
        val parts = ArrayList<String>()
        val p = pitch.coerceIn(0.5f, 2f)
        if (kotlin.math.abs(p - 1f) > 0.03f) {
            parts.add("asetrate=${f(44100f * p)}")
            parts.add("aresample=44100")
            parts.add("atempo=${f((1f / p).coerceIn(0.5f, 2f))}")
        }
        val sp = speed.coerceIn(0.5f, 2f)
        if (kotlin.math.abs(sp - 1f) > 0.03f) parts.add("atempo=${f(sp)}")
        if (echo > 0.05f) {
            val g = f((0.4f * echo).coerceIn(0.12f, 0.65f))
            parts.add("aecho=0.8:$g:60:0.35")
        }
        if (kotlin.math.abs(eq - 50) > 3) {
            val treble = ((eq - 50) / 5.0).coerceIn(-10.0, 10.0)
            val bass = -treble
            parts.add("bass=g=${String.format(Locale.US, "%.1f", bass)}")
            parts.add("treble=g=${String.format(Locale.US, "%.1f", treble)}")
        }
        if (fade && dur > 2.5) {
            val outSt = d((dur - 1.2).coerceAtLeast(0.0))
            parts.add("afade=t=in:st=0:d=0.8")
            parts.add("afade=t=out:st=$outSt:d=1.2")
        } else if (fade) {
            parts.add("afade=t=in:st=0:d=0.3")
        }
        return parts.joinToString(",")
    }

    private fun ffmpegOk(args: Array<String>, outFile: File, timeoutSec: Long): Boolean {
        if (cancelFlag.get()) return false
        val done = AtomicBoolean(false)
        val thread = Thread {
            try {
                FFmpegKit.executeWithArguments(args)
            } catch (_: Throwable) {
            } finally {
                done.set(true)
            }
        }
        thread.start()
        val deadline = System.currentTimeMillis() + timeoutSec * 1000L
        while (!done.get() && System.currentTimeMillis() < deadline) {
            if (cancelFlag.get()) {
                try { FFmpegKit.cancel() } catch (_: Throwable) {}
                break
            }
            try { Thread.sleep(250) } catch (_: InterruptedException) { break }
        }
        if (!done.get()) {
            try { FFmpegKit.cancel() } catch (_: Throwable) {}
            try { thread.join(2500) } catch (_: Exception) {}
        }
        return outFile.exists() && outFile.length() > 500
    }

    private fun startProgressTicker() {
        stopProgress()
        progressRunnable = object : Runnable {
            override fun run() {
                if (!isMixing) return
                if (lastProgress < 94) {
                    lastProgress = (lastProgress + 1).coerceAtMost(94)
                    binding.progressBar.progress = lastProgress
                }
                uiHandler.postDelayed(this, 600)
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
            binding.tvStatus.text = "میکس آماده است — پخش یا ذخیره کنید"
            binding.btnPlay.isEnabled = true
            binding.btnSave.isEnabled = true
            try { mediaPlayer?.release() } catch (_: Exception) {}
            mediaPlayer = null
            isPlaying = false
            binding.btnPlay.text = getString(R.string.play_result)
            Toast.makeText(this, "میکس آماده است", Toast.LENGTH_LONG).show()
        } else {
            binding.progressBar.visibility = View.GONE
            binding.progressBar.progress = 0
            val msg = error.ifEmpty { "میکس انجام نشد" }
            binding.tvStatus.text = msg
            binding.btnPlay.isEnabled = false
            binding.btnSave.isEnabled = false
            Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
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
        binding.progressBar.progress = 0
        try { mediaPlayer?.stop() } catch (_: Exception) {}
        mediaPlayer?.release()
        mediaPlayer = null
        isPlaying = false
        mainFile = null; bgFile = null
        mainFileName = ""; bgFileName = ""
        outputFile = null
        mainVolume = 1f; bgVolume = 0.5f
        mixSpeed = 1f; mixPitch = 1f
        mainEcho = 0f; bgEcho = 0f
        eqGain = 50
        try { binding.cbFade.isChecked = false } catch (_: Exception) {}
        try {
            binding.seekMixSpeed.progress = 100
            binding.seekMixPitch.progress = 100
            binding.seekEq.progress = 50
            binding.tvMixSpeed.text = "سرعت میکس: ۱۰۰٪"
            binding.tvMixPitch.text = "زیر و بمی میکس: ۱۰۰٪"
            binding.tvEq.text = "اکولایزر: عادی"
        } catch (_: Exception) {}
        binding.tvMainFile.visibility = View.VISIBLE
        binding.tvMainFile.text = getString(R.string.no_file_selected)
        binding.tvMainFile.setTextColor(Color.parseColor("#1B5E20"))
        binding.tvBgFile.visibility = View.VISIBLE
        binding.tvBgFile.text = getString(R.string.no_file_selected)
        binding.tvBgFile.setTextColor(Color.parseColor("#0D47A1"))
        binding.btnSelectMain.text = getString(R.string.select_main_file)
        binding.btnSelectBg.text = getString(R.string.select_bg_file)
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
            Toast.makeText(this, "پخش ممکن نیست", Toast.LENGTH_LONG).show()
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
                    val name = "mixed_${System.currentTimeMillis()}.wav"
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        val values = ContentValues().apply {
                            put(MediaStore.Audio.Media.DISPLAY_NAME, name)
                            put(MediaStore.Audio.Media.MIME_TYPE, "audio/wav")
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
                        FileInputStream(f).use { input ->
                            FileOutputStream(File(dir, name)).use { output -> input.copyTo(output) }
                        }
                        true
                    }
                } catch (_: Exception) { false }
            }
            Toast.makeText(
                this@MainActivity,
                if (ok) "در Downloads ذخیره شد" else "ذخیره ناموفق",
                Toast.LENGTH_LONG
            ).show()
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
            binding.tvRecordStatus.text = if (recordedFile != null) "ضبط ذخیره شد" else ""
            return
        }
        try {
            val out = File(cacheDir, "rec_${System.currentTimeMillis()}.m4a")
            recordedFile = out
            val recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(this)
            else @Suppress("DEPRECATION") MediaRecorder()
            recorder.setAudioSource(MediaRecorder.AudioSource.MIC)
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            recorder.setOutputFile(out.absolutePath)
            recorder.prepare()
            recorder.start()
            mediaRecorder = recorder
            isRecording = true
            binding.btnRecord.text = getString(R.string.record_stop)
            binding.tvRecordStatus.text = getString(R.string.recording)
        } catch (_: Exception) {
            Toast.makeText(this, "ضبط ممکن نیست", Toast.LENGTH_LONG).show()
        }
    }

    private fun deleteRecord() {
        if (isRecording) toggleRecord()
        recordedFile?.delete()
        recordedFile = null
        binding.btnDeleteRecord.isEnabled = false
        binding.btnUseRecordAsMain.isEnabled = false
        binding.tvRecordStatus.text = ""
    }

    private fun useRecordAsMain() {
        val f = recordedFile
        if (f == null || !f.exists()) {
            Toast.makeText(this, "فایل ضبط‌شده‌ای نیست", Toast.LENGTH_SHORT).show()
            return
        }
        mainFile = f
        mainFileName = f.name
        showMainSelected(f.name, f.length())
    }
}
