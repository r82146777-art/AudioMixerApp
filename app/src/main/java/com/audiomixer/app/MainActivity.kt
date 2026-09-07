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
import kotlinx.coroutines.withTimeoutOrNull
import zeroonezero.android.audio_mixer.AudioMixer
import zeroonezero.android.audio_mixer.input.GeneralAudioInput
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

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
    private var lastProgress = 5
    private var lastFfmpegLog: String = ""

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

        // Ensure file name labels are visible
        binding.tvMainFile.visibility = View.VISIBLE
        binding.tvBgFile.visibility = View.VISIBLE

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
        // Immediate UI feedback on main thread
        runOnUiThread {
            showMainSelected(name, 0)
            Toast.makeText(this, "انتخاب شد: $name", Toast.LENGTH_SHORT).show()
        }
        lifecycleScope.launch {
            val file = withContext(Dispatchers.IO) { copyUriToCache(uri, "main", name) }
            runOnUiThread {
                if (file != null && file.exists() && file.length() > 0) {
                    mainFile = file
                    showMainSelected(name, file.length())
                    Toast.makeText(this@MainActivity, "✅ فایل اصلی آماده", Toast.LENGTH_SHORT).show()
                } else {
                    mainFile = null
                    binding.tvMainFile.visibility = View.VISIBLE
                    binding.tvMainFile.text = "❌ خطا در خواندن: $name"
                    binding.tvMainFile.setTextColor(Color.RED)
                }
            }
        }
    }

    private fun onBgSelected(uri: Uri) {
        val name = resolveName(uri)
        bgFileName = name
        runOnUiThread {
            showBgSelected(name, 0)
            Toast.makeText(this, "انتخاب شد: $name", Toast.LENGTH_SHORT).show()
        }
        lifecycleScope.launch {
            val file = withContext(Dispatchers.IO) { copyUriToCache(uri, "bg", name) }
            runOnUiThread {
                if (file != null && file.exists() && file.length() > 0) {
                    bgFile = file
                    showBgSelected(name, file.length())
                    Toast.makeText(this@MainActivity, "✅ فایل پس‌زمینه آماده", Toast.LENGTH_SHORT).show()
                } else {
                    bgFile = null
                    binding.tvBgFile.visibility = View.VISIBLE
                    binding.tvBgFile.text = "❌ خطا در خواندن: $name"
                    binding.tvBgFile.setTextColor(Color.RED)
                }
            }
        }
    }

    private fun showMainSelected(name: String, sizeBytes: Long) {
        val sizeStr = if (sizeBytes > 0) " (${formatSize(sizeBytes)})" else ""
        binding.tvMainFile.visibility = View.VISIBLE
        binding.tvMainFile.text = "✅ انتخاب شد: $name$sizeStr"
        binding.tvMainFile.setTextColor(Color.parseColor("#1B5E20"))
        binding.tvMainFile.textSize = 16f
        binding.btnSelectMain.text = "تغییر فایل اصلی"
    }

    private fun showBgSelected(name: String, sizeBytes: Long) {
        val sizeStr = if (sizeBytes > 0) " (${formatSize(sizeBytes)})" else ""
        binding.tvBgFile.visibility = View.VISIBLE
        binding.tvBgFile.text = "✅ انتخاب شد: $name$sizeStr"
        binding.tvBgFile.setTextColor(Color.parseColor("#0D47A1"))
        binding.tvBgFile.textSize = 16f
        binding.btnSelectBg.text = "تغییر فایل پس‌زمینه"
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
        return name!!
    }

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
                            else -> "mp3"
                        }
                    }
                }
            }
            val file = File(cacheDir, "${prefix}_${System.currentTimeMillis()}.$ext")
            FileOutputStream(file).use { out -> input.copyTo(out) }
            input.close()
            if (file.exists() && file.length() > 0) file else null
        } catch (_: Exception) {
            null
        }
    }

    private fun showFileSettingsDialog(isMain: Boolean) {
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

    private fun needsFx(): Boolean {
        return kotlin.math.abs(mainSpeed - 1f) > 0.02f || kotlin.math.abs(bgSpeed - 1f) > 0.02f ||
            kotlin.math.abs(mainPitch - 1f) > 0.02f || kotlin.math.abs(bgPitch - 1f) > 0.02f ||
            mainEcho > 0.05f || bgEcho > 0.05f || binding.cbFade.isChecked
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
        lastFfmpegLog = ""
        binding.progressBar.visibility = View.VISIBLE
        binding.progressBar.isIndeterminate = false
        binding.progressBar.max = 100
        binding.progressBar.progress = 5
        binding.btnMix.isEnabled = false
        binding.btnPlay.isEnabled = false
        binding.btnSave.isEnabled = false

        val mainDur = getAudioDurationSec(main).let { if (it < 0.5) 0.0 else it }
        val mins = if (mainDur > 0) mainDur / 60.0 else 0.0
        binding.tvStatus.text = if (mins >= 1)
            String.format(Locale.US, "در حال میکس فایل %.0f دقیقه‌ای...", mins)
        else
            "در حال میکس..."

        // ffmpeg-kit-audio has LAME not AAC → always mp3 or wav
        val outExt = if (binding.rbWav.isChecked) "wav" else "mp3"
        val outFile = File(cacheDir, "mixed_${System.currentTimeMillis()}.$outExt")

        val fade = binding.cbFade.isChecked
        val fx = needsFx()
        val mVol = mainVolume; val bVol = bgVolume
        val mSp = mainSpeed; val mPi = mainPitch; val mEc = mainEcho

        val globalTimeoutMs = when {
            mainDur > 1800 -> 15 * 60 * 1000L
            mainDur > 600 -> 8 * 60 * 1000L
            mainDur > 120 -> 4 * 60 * 1000L
            else -> 120 * 1000L
        }

        startProgressTicker()

        mixJob = lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                val r = withTimeoutOrNull(globalTimeoutMs) {
                    robustMix(main, bg, outFile, mainDur, outExt, fx, fade, mVol, bVol, mSp, mPi, mEc)
                }
                r ?: run {
                    try { FFmpegKit.cancel() } catch (_: Throwable) {}
                    if (outFile.exists() && outFile.length() > 1000) Pair(outFile, "")
                    else Pair(null, "زمان میکس تمام شد")
                }
            }
            if (!isActive) return@launch
            if (result.first != null) finishMix(result.first, "")
            else finishMix(null, result.second)
        }
    }

    private fun robustMix(
        main: File, bg: File, outFile: File,
        mainDur: Double, outExt: String,
        fx: Boolean, fade: Boolean,
        mVol: Float, bVol: Float,
        mSp: Float, mPi: Float, mEc: Float
    ): Pair<File?, String> {
        try {
            if (cancelFlag.get()) return Pair(null, "میکس لغو شد")
            try { outFile.delete() } catch (_: Exception) {}

            val t = if (mainDur > 0.5) mainDur else 0.0
            val timeout = when {
                t > 1800 -> 500L
                t > 600 -> 240L
                t > 120 -> 120L
                else -> 90L
            }

            // Java first for short (MediaCodec path worked in early versions)
            if (!fx && t > 0 && t <= 90.0) {
                setProgressUi(20, "میکس سریع...")
                val javaOut = if (outExt == "mp3" || outExt == "m4a") outFile
                else File(cacheDir, "java_${System.currentTimeMillis()}.m4a")
                if (runJavaMixTimed(main, bg, javaOut, mVol, bVol, (t * 2000 + 30_000).toLong().coerceAtMost(120_000))) {
                    if (javaOut.absolutePath != outFile.absolutePath) {
                        return copyOrConvert(javaOut, outFile, outExt, timeout)
                    }
                    return Pair(outFile, "")
                }
            }

            if (cancelFlag.get()) return Pair(null, "میکس لغو شد")

            // Convert both to WAV then mix with libmp3lame
            setProgressUi(25, "تبدیل فایل اصلی...")
            val mainWav = File(cacheDir, "main_${System.currentTimeMillis()}.wav")
            val bgWav = File(cacheDir, "bg_${System.currentTimeMillis()}.wav")

            val mainConv = convertToWav(main, mainWav, t, timeout)
            setProgressUi(40, "تبدیل پس‌زمینه...")
            val bgConv = convertToWav(bg, bgWav, t, timeout)

            if (mainConv && bgConv) {
                setProgressUi(55, "در حال میکس...")
                val mixed = File(cacheDir, "mix_${System.currentTimeMillis()}.mp3")
                if (mixWavs(mainWav, bgWav, mixed, mVol, bVol, t, timeout)) {
                    try { mainWav.delete() } catch (_: Exception) {}
                    try { bgWav.delete() } catch (_: Exception) {}
                    return postProcess(mixed, outFile, outExt, fx, fade, mSp, mPi, mEc, t, timeout)
                }
            }
            try { mainWav.delete() } catch (_: Exception) {}
            try { bgWav.delete() } catch (_: Exception) {}

            if (cancelFlag.get()) return Pair(null, "میکس لغو شد")

            setProgressUi(60, "میکس مستقیم...")
            val mixed2 = File(cacheDir, "mix2_${System.currentTimeMillis()}.mp3")
            if (directMix(main, bg, mixed2, mVol, bVol, t, timeout)) {
                return postProcess(mixed2, outFile, outExt, fx, fade, mSp, mPi, mEc, t, timeout)
            }

            setProgressUi(75, "مسیر جایگزین...")
            val javaOut2 = File(cacheDir, "java2_${System.currentTimeMillis()}.m4a")
            if (runJavaMixTimed(main, bg, javaOut2, mVol, bVol, 120_000L)) {
                return copyOrConvert(javaOut2, outFile, outExt, timeout)
            }

            val hint = if (lastFfmpegLog.contains("codec", ignoreCase = true) ||
                lastFfmpegLog.contains("Encoder", ignoreCase = true)
            ) {
                "خطای کدک برطرف شد — دوباره نصب کنید. اگر باز خطا بود فایل MP3 امتحان کنید."
            } else if (lastFfmpegLog.isNotBlank()) {
                "میکس ناموفق: ${lastFfmpegLog.takeLast(80).replace('\n', ' ')}"
            } else {
                "میکس ناموفق. دو فایل کوتاه MP3 امتحان کنید."
            }
            return Pair(null, hint)
        } catch (ex: Throwable) {
            return Pair(null, "خطا: ${ex.message ?: "نامشخص"}")
        }
    }

    private fun convertToWav(src: File, dest: File, maxDur: Double, timeoutSec: Long): Boolean {
        try { dest.delete() } catch (_: Exception) {}
        val args = ArrayList<String>()
        args.add("-y")
        args.add("-i"); args.add(src.absolutePath)
        if (maxDur > 0.5) {
            args.add("-t"); args.add(d(maxDur))
        }
        args.add("-ac"); args.add("2")
        args.add("-ar"); args.add("44100")
        args.add("-c:a"); args.add("pcm_s16le")
        args.add(dest.absolutePath)
        return ffmpegOk(args.toTypedArray(), dest, timeoutSec)
    }

    private fun mixWavs(
        mainWav: File, bgWav: File, out: File,
        mVol: Float, bVol: Float, maxDur: Double, timeoutSec: Long
    ): Boolean {
        try { out.delete() } catch (_: Exception) {}
        val filter =
            "[0:a]volume=${f(mVol.coerceIn(0.05f, 2f))}[a0];" +
            "[1:a]volume=${f(bVol.coerceIn(0.05f, 2f))}[a1];" +
            "[a0][a1]amix=inputs=2:duration=first[aout]"

        // libmp3lame is in ffmpeg-kit-audio; aac is NOT
        val args = arrayOf(
            "-y",
            "-i", mainWav.absolutePath,
            "-i", bgWav.absolutePath,
            "-filter_complex", filter,
            "-map", "[aout]",
            "-c:a", "libmp3lame",
            "-b:a", "128k",
            "-ac", "2",
            "-ar", "44100",
            *(if (maxDur > 0.5) arrayOf("-t", d(maxDur)) else arrayOf("-shortest")),
            out.absolutePath
        )
        if (ffmpegOk(args, out, timeoutSec)) return true

        try { out.delete() } catch (_: Exception) {}
        val args2 = arrayOf(
            "-y",
            "-i", mainWav.absolutePath,
            "-i", bgWav.absolutePath,
            "-filter_complex", "amix=inputs=2:duration=first",
            "-c:a", "libmp3lame",
            "-b:a", "96k",
            *(if (maxDur > 0.5) arrayOf("-t", d(maxDur)) else emptyArray()),
            out.absolutePath
        )
        return ffmpegOk(args2, out, timeoutSec)
    }

    private fun directMix(
        main: File, bg: File, out: File,
        mVol: Float, bVol: Float, maxDur: Double, timeoutSec: Long
    ): Boolean {
        try { out.delete() } catch (_: Exception) {}
        val filter =
            "[0:a]volume=${f(mVol.coerceIn(0.05f, 2f))}[a0];" +
            "[1:a]volume=${f(bVol.coerceIn(0.05f, 2f))}[a1];" +
            "[a0][a1]amix=inputs=2:duration=first[aout]"
        val args = arrayOf(
            "-y",
            "-i", main.absolutePath,
            "-i", bg.absolutePath,
            "-filter_complex", filter,
            "-map", "[aout]",
            "-c:a", "libmp3lame",
            "-b:a", "128k",
            "-ac", "2",
            *(if (maxDur > 0.5) arrayOf("-t", d(maxDur)) else arrayOf("-shortest")),
            out.absolutePath
        )
        if (ffmpegOk(args, out, timeoutSec)) return true

        try { out.delete() } catch (_: Exception) {}
        val args2 = arrayOf(
            "-y",
            "-i", main.absolutePath,
            "-i", bg.absolutePath,
            "-filter_complex", "amix=inputs=2:duration=first",
            "-c:a", "libmp3lame",
            "-b:a", "96k",
            *(if (maxDur > 0.5) arrayOf("-t", d(maxDur)) else emptyArray()),
            out.absolutePath
        )
        return ffmpegOk(args2, out, timeoutSec)
    }

    private fun postProcess(
        mixed: File, outFile: File, outExt: String,
        fx: Boolean, fade: Boolean,
        mSp: Float, mPi: Float, mEc: Float,
        t: Double, timeoutSec: Long
    ): Pair<File?, String> {
        val needFx = fx || fade
        if (!needFx) {
            return copyOrConvert(mixed, outFile, outExt, timeoutSec)
        }
        setProgressUi(85, "اعمال افکت‌ها...")
        val af = buildPostFx(mSp, mPi, mEc, fade, t)
        try { outFile.delete() } catch (_: Exception) {}
        val args = ArrayList<String>()
        args.add("-y")
        args.add("-i"); args.add(mixed.absolutePath)
        if (af.isNotBlank()) {
            args.add("-af"); args.add(af)
        }
        if (outExt == "wav") {
            args.add("-c:a"); args.add("pcm_s16le")
        } else {
            args.add("-c:a"); args.add("libmp3lame")
            args.add("-b:a"); args.add("128k")
        }
        if (t > 0.5) {
            args.add("-t"); args.add(d(t))
        }
        args.add(outFile.absolutePath)

        if (ffmpegOk(args.toTypedArray(), outFile, timeoutSec)) {
            try { mixed.delete() } catch (_: Exception) {}
            return Pair(outFile, "")
        }
        return copyOrConvert(mixed, outFile, outExt, timeoutSec)
    }

    private fun copyOrConvert(src: File, dest: File, outExt: String, timeoutSec: Long): Pair<File?, String> {
        return try {
            if (outExt == "wav" && !src.name.endsWith(".wav")) {
                try { dest.delete() } catch (_: Exception) {}
                val ok = ffmpegOk(
                    arrayOf("-y", "-i", src.absolutePath, "-c:a", "pcm_s16le", dest.absolutePath),
                    dest, timeoutSec
                )
                try { src.delete() } catch (_: Exception) {}
                if (ok) Pair(dest, "") else Pair(null, "خروجی ساخته نشد")
            } else if (outExt == "mp3" && !src.name.endsWith(".mp3")) {
                try { dest.delete() } catch (_: Exception) {}
                val ok = ffmpegOk(
                    arrayOf(
                        "-y", "-i", src.absolutePath,
                        "-c:a", "libmp3lame", "-b:a", "128k", dest.absolutePath
                    ),
                    dest, timeoutSec
                )
                try { src.delete() } catch (_: Exception) {}
                if (ok) Pair(dest, "") else {
                    src.copyTo(dest, overwrite = true)
                    if (dest.exists()) Pair(dest, "") else Pair(null, "خروجی ساخته نشد")
                }
            } else {
                src.copyTo(dest, overwrite = true)
                try { src.delete() } catch (_: Exception) {}
                if (dest.exists() && dest.length() > 200) Pair(dest, "")
                else Pair(null, "خروجی ساخته نشد")
            }
        } catch (_: Exception) {
            Pair(null, "خروجی ساخته نشد")
        }
    }

    private fun buildPostFx(speed: Float, pitch: Float, echo: Float, fade: Boolean, dur: Double): String {
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
        val codeOk = AtomicBoolean(false)
        val logRef = AtomicReference("")

        val thread = Thread {
            try {
                val session = FFmpegKit.executeWithArguments(args)
                codeOk.set(ReturnCode.isSuccess(session.returnCode))
                try {
                    logRef.set(session.failStackTrace ?: session.allLogsAsString?.takeLast(400) ?: "")
                } catch (_: Exception) {}
            } catch (e: Throwable) {
                logRef.set(e.message ?: "")
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
            try { Thread.sleep(200) } catch (_: InterruptedException) { break }
        }
        if (!done.get()) {
            try { FFmpegKit.cancel() } catch (_: Throwable) {}
            try { thread.join(2000) } catch (_: Exception) {}
        }
        lastFfmpegLog = logRef.get() ?: ""
        if (outFile.exists() && outFile.length() > 500) return true
        return codeOk.get() && outFile.exists() && outFile.length() > 200
    }

    private fun runJavaMixTimed(
        main: File, bg: File, outFile: File,
        mVol: Float, bVol: Float, timeoutMs: Long
    ): Boolean {
        if (cancelFlag.get()) return false
        val done = AtomicBoolean(false)
        val ok = AtomicBoolean(false)
        val thread = Thread {
            try {
                ok.set(runJavaMixInner(main, bg, outFile, mVol, bVol))
            } catch (_: Throwable) {
                ok.set(false)
            } finally {
                done.set(true)
            }
        }
        thread.start()
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!done.get() && System.currentTimeMillis() < deadline) {
            if (cancelFlag.get()) break
            try { Thread.sleep(200) } catch (_: InterruptedException) { break }
        }
        if (!done.get()) {
            try { outFile.delete() } catch (_: Exception) {}
            return false
        }
        return ok.get() && outFile.exists() && outFile.length() > 500
    }

    private fun runJavaMixInner(main: File, bg: File, outFile: File, mVol: Float, bVol: Float): Boolean {
        try { outFile.delete() } catch (_: Exception) {}
        val tempOut = if (outFile.extension.lowercase() in listOf("m4a", "mp3")) outFile
        else File(cacheDir, "tmp_java_${System.currentTimeMillis()}.m4a")
        val mixer = AudioMixer(tempOut.absolutePath)
        val in1 = GeneralAudioInput(main.absolutePath)
        in1.setVolume(mVol.coerceIn(0f, 2f))
        val in2 = GeneralAudioInput(bg.absolutePath)
        in2.setVolume(bVol.coerceIn(0f, 2f))
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
        mixer.processSync()
        if (!tempOut.exists() || tempOut.length() < 500) return false
        if (tempOut.absolutePath != outFile.absolutePath) {
            tempOut.copyTo(outFile, overwrite = true)
            tempOut.delete()
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
            try { mediaPlayer?.release() } catch (_: Exception) {}
            mediaPlayer = null
            isPlaying = false
            binding.btnPlay.text = getString(R.string.play_result)
            Toast.makeText(this, "✅ میکس آماده است", Toast.LENGTH_LONG).show()
        } else {
            binding.progressBar.visibility = View.GONE
            binding.progressBar.progress = 0
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
        binding.progressBar.progress = 0
        try { mediaPlayer?.stop() } catch (_: Exception) {}
        mediaPlayer?.release()
        mediaPlayer = null
        isPlaying = false
        mainFile = null; bgFile = null
        mainFileName = ""; bgFileName = ""
        outputFile = null
        mainVolume = 1f; bgVolume = 0.5f
        mainSpeed = 1f; bgSpeed = 1f
        mainPitch = 1f; bgPitch = 1f
        mainEcho = 0f; bgEcho = 0f
        try { binding.cbFade.isChecked = false } catch (_: Exception) {}
        binding.tvMainFile.visibility = View.VISIBLE
        binding.tvMainFile.text = getString(R.string.no_file_selected)
        binding.tvMainFile.setTextColor(Color.BLACK)
        binding.tvBgFile.visibility = View.VISIBLE
        binding.tvBgFile.text = getString(R.string.no_file_selected)
        binding.tvBgFile.setTextColor(Color.BLACK)
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
                    val name = "mixed_${System.currentTimeMillis()}.${f.extension.ifBlank { "mp3" }}"
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        val mime = when (f.extension.lowercase()) {
                            "wav" -> "audio/wav"
                            "mp3" -> "audio/mpeg"
                            else -> "audio/mpeg"
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
