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
        // Show name IMMEDIATELY so user sees selection
        showMainSelected(name, 0)
        Toast.makeText(this, "انتخاب شد: $name", Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            val file = withContext(Dispatchers.IO) { copyUriToCache(uri, "main", name) }
            if (file != null && file.exists() && file.length() > 0) {
                mainFile = file
                showMainSelected(name, file.length())
                Toast.makeText(this@MainActivity, "✅ فایل اصلی آماده: $name", Toast.LENGTH_SHORT).show()
            } else {
                mainFile = null
                binding.tvMainFile.text = "❌ خطا در خواندن: $name"
                binding.tvMainFile.setTextColor(Color.RED)
                Toast.makeText(this@MainActivity, "خطا در خواندن فایل اصلی", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun onBgSelected(uri: Uri) {
        val name = resolveName(uri)
        bgFileName = name
        showBgSelected(name, 0)
        Toast.makeText(this, "انتخاب شد: $name", Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            val file = withContext(Dispatchers.IO) { copyUriToCache(uri, "bg", name) }
            if (file != null && file.exists() && file.length() > 0) {
                bgFile = file
                showBgSelected(name, file.length())
                Toast.makeText(this@MainActivity, "✅ فایل پس‌زمینه آماده: $name", Toast.LENGTH_SHORT).show()
            } else {
                bgFile = null
                binding.tvBgFile.text = "❌ خطا در خواندن: $name"
                binding.tvBgFile.setTextColor(Color.RED)
                Toast.makeText(this@MainActivity, "خطا در خواندن فایل پس‌زمینه", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun showMainSelected(name: String, sizeBytes: Long) {
        val sizeStr = if (sizeBytes > 0) " (${formatSize(sizeBytes)})" else ""
        val text = "✅ $name$sizeStr"
        binding.tvMainFile.text = text
        binding.tvMainFile.setTextColor(Color.parseColor("#1B5E20"))
        binding.tvMainFile.contentDescription = "فایل اصلی انتخاب شده: $name"
        binding.btnSelectMain.text = "تغییر فایل اصلی"
    }

    private fun showBgSelected(name: String, sizeBytes: Long) {
        val sizeStr = if (sizeBytes > 0) " (${formatSize(sizeBytes)})" else ""
        val text = "✅ $name$sizeStr"
        binding.tvBgFile.text = text
        binding.tvBgFile.setTextColor(Color.parseColor("#0D47A1"))
        binding.tvBgFile.contentDescription = "فایل پس‌زمینه انتخاب شده: $name"
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
                            else -> "m4a"
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

        val outExt = if (binding.rbWav.isChecked) "wav" else "m4a"
        val outFile = File(cacheDir, "mixed_${System.currentTimeMillis()}.$outExt")

        val fade = binding.cbFade.isChecked
        val fx = needsFx()
        val mVol = mainVolume; val bVol = bgVolume
        val mSp = mainSpeed; val mPi = mainPitch; val mEc = mainEcho

        // Global max wait: scale with duration but never infinite
        val globalTimeoutMs = when {
            mainDur > 3600 -> 20 * 60 * 1000L
            mainDur > 1800 -> 12 * 60 * 1000L
            mainDur > 600 -> 8 * 60 * 1000L
            mainDur > 120 -> 4 * 60 * 1000L
            else -> 90 * 1000L
        }

        startProgressTicker()

        mixJob = lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                val r = withTimeoutOrNull(globalTimeoutMs) {
                    simpleMix(main, bg, outFile, mainDur, outExt, fx, fade, mVol, bVol, mSp, mPi, mEc)
                }
                r ?: run {
                    try { FFmpegKit.cancel() } catch (_: Throwable) {}
                    // Accept partial output if large enough
                    if (outFile.exists() && outFile.length() > 50_000) Pair(outFile, "")
                    else Pair(null, "زمان میکس تمام شد. فایل کوتاه‌تری امتحان کنید یا دوباره بزنید.")
                }
            }
            if (!isActive) return@launch
            if (result.first != null) finishMix(result.first, "")
            else finishMix(null, result.second)
        }
    }

    /**
     * Simplest reliable path:
     * 1) One FFmpeg amix (no stream_loop) — always terminates with -t / duration=first
     * 2) Optional second pass for FX on single file
     * Java only for very short files and only with hard timeout
     */
    private fun simpleMix(
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
            val bitrate = when {
                t > 1800 -> "48k"
                t > 600 -> "64k"
                t > 120 -> "96k"
                else -> "128k"
            }

            // Short + no FX: timed Java (max 40s)
            if (!fx && t > 0 && t <= 45.0) {
                setProgressUi(25, "میکس سریع...")
                if (runJavaMixTimed(main, bg, outFile, mVol, bVol, 40_000L)) {
                    return Pair(outFile, "")
                }
            }

            if (cancelFlag.get()) return Pair(null, "میکس لغو شد")

            // Main FFmpeg mix — NO stream_loop (that was hanging)
            setProgressUi(35, "در حال میکس...")
            val mixedTmp = File(cacheDir, "mix_${System.currentTimeMillis()}.m4a")
            val filter =
                "[0:a]volume=${f(mVol.coerceIn(0f, 2f))}[a0];" +
                "[1:a]volume=${f(bVol.coerceIn(0f, 2f))}[a1];" +
                "[a0][a1]amix=inputs=2:duration=first:dropout_transition=0[a]"

            val args = ArrayList<String>()
            args.add("-y")
            args.add("-i"); args.add(main.absolutePath)
            args.add("-i"); args.add(bg.absolutePath)
            args.add("-filter_complex"); args.add(filter)
            args.add("-map"); args.add("[a]")
            args.add("-c:a"); args.add("aac")
            args.add("-b:a"); args.add(bitrate)
            args.add("-ac"); args.add("2")
            args.add("-ar"); args.add("44100")
            if (t > 0.5) {
                args.add("-t"); args.add(d(t))
            } else {
                args.add("-shortest")
            }
            args.add(mixedTmp.absolutePath)

            val stepTimeout = when {
                t > 1800 -> 600L
                t > 600 -> 300L
                t > 120 -> 150L
                else -> 60L
            }

            val mixOk = ffmpegRun(args.toTypedArray(), stepTimeout) &&
                mixedTmp.exists() && mixedTmp.length() > 200

            if (!mixOk) {
                try { mixedTmp.delete() } catch (_: Exception) {}
                setProgressUi(50, "تلاش مجدد...")
                if (runJavaMixTimed(main, bg, outFile, mVol, bVol, 60_000L)) {
                    return Pair(outFile, "")
                }
                return Pair(null, "میکس انجام نشد. فایل دیگری امتحان کنید.")
            }

            if (cancelFlag.get()) {
                mixedTmp.delete()
                return Pair(null, "میکس لغو شد")
            }

            val needFx = fx || fade
            if (!needFx) {
                setProgressUi(90, "نهایی‌سازی...")
                return finalizeOutput(mixedTmp, outFile, outExt, bitrate, t)
            }

            setProgressUi(75, "اعمال افکت‌ها...")
            val af = buildPostFx(mSp, mPi, mEc, fade, t)
            val fxArgs = ArrayList<String>()
            fxArgs.add("-y")
            fxArgs.add("-i"); fxArgs.add(mixedTmp.absolutePath)
            if (af.isNotBlank()) {
                fxArgs.add("-af"); fxArgs.add(af)
            }
            if (outExt == "wav") {
                fxArgs.add("-c:a"); fxArgs.add("pcm_s16le")
            } else {
                fxArgs.add("-c:a"); fxArgs.add("aac")
                fxArgs.add("-b:a"); fxArgs.add(bitrate)
            }
            fxArgs.add("-ac"); fxArgs.add("2")
            fxArgs.add("-ar"); fxArgs.add("44100")
            if (t > 0.5) {
                fxArgs.add("-t"); fxArgs.add(d(t))
            }
            fxArgs.add(outFile.absolutePath)

            val fxOk = ffmpegRun(fxArgs.toTypedArray(), stepTimeout) &&
                outFile.exists() && outFile.length() > 200

            if (fxOk) {
                try { mixedTmp.delete() } catch (_: Exception) {}
                return Pair(outFile, "")
            }

            // FX failed — still deliver the mixed file without FX
            return finalizeOutput(mixedTmp, outFile, outExt, bitrate, t)
        } catch (ex: Throwable) {
            return Pair(null, "خطا: ${ex.message ?: "نامشخص"}")
        }
    }

    private fun finalizeOutput(
        mixedTmp: File, outFile: File, outExt: String, bitrate: String, t: Double
    ): Pair<File?, String> {
        return try {
            if (outExt == "wav") {
                val ok = ffmpegRun(
                    arrayOf(
                        "-y", "-i", mixedTmp.absolutePath,
                        "-c:a", "pcm_s16le", outFile.absolutePath
                    ),
                    90L
                )
                try { mixedTmp.delete() } catch (_: Exception) {}
                if (ok && outFile.exists()) Pair(outFile, "")
                else {
                    mixedTmp.copyTo(outFile, overwrite = true)
                    if (outFile.exists()) Pair(outFile, "") else Pair(null, "خروجی ساخته نشد")
                }
            } else {
                mixedTmp.copyTo(outFile, overwrite = true)
                try { mixedTmp.delete() } catch (_: Exception) {}
                if (outFile.exists() && outFile.length() > 200) Pair(outFile, "")
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
            parts.add("afade=t=out:st=0:d=0.3")
        }
        return parts.joinToString(",")
    }

    private fun ffmpegRun(args: Array<String>, timeoutSec: Long): Boolean {
        if (cancelFlag.get()) return false
        val done = AtomicBoolean(false)
        val ok = AtomicBoolean(false)
        val thread = Thread {
            try {
                val session = FFmpegKit.executeWithArguments(args)
                ok.set(ReturnCode.isSuccess(session.returnCode))
            } catch (_: Throwable) {
                ok.set(false)
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
        return ok.get() && !cancelFlag.get()
    }

    /** Java mixer with hard timeout — never blocks forever */
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
            // Cannot force-stop Java mixer safely; abandon thread
            try { outFile.delete() } catch (_: Exception) {}
            return false
        }
        return ok.get()
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
        binding.tvMainFile.text = getString(R.string.no_file_selected)
        binding.tvMainFile.setTextColor(Color.BLACK)
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
