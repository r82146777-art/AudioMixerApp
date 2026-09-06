package com.audiomixer.app

import android.Manifest
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
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
import com.audiomixer.app.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

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
    private var mixTimeoutRunnable: Runnable? = null
    private var progressRunnable: Runnable? = null
    private var currentOutPath: String? = null

    private val mixReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != MixService.ACTION_MIX_DONE) return
            val ok = intent.getBooleanExtra(MixService.KEY_OK, false)
            val path = intent.getStringExtra(MixService.KEY_PATH)
            val err = intent.getStringExtra(MixService.KEY_ERROR) ?: ""
            if (ok && path != null) {
                val f = File(path)
                if (f.exists() && f.length() > 200) finishMix(f, "")
                else finishMix(null, "فایل خروجی پیدا نشد")
            } else {
                finishMix(null, err.ifEmpty { "میکس ناموفق" })
            }
        }
    }

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

        val filter = IntentFilter(MixService.ACTION_MIX_DONE)
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(mixReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(mixReceiver, filter)
        }

        checkPermissions()
        setupButtons()
        showInviteIfNeeded()
    }

    override fun onDestroy() {
        super.onDestroy()
        try { unregisterReceiver(mixReceiver) } catch (_: Exception) {}
        stopProgress()
        mixTimeoutRunnable?.let { uiHandler.removeCallbacks(it) }
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
            val file = withContext(Dispatchers.IO) { copyUriToCache(uri, "main") }
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
            val file = withContext(Dispatchers.IO) { copyUriToCache(uri, "bg") }
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

    private fun copyUriToCache(uri: Uri, prefix: String): File? {
        return try {
            val input = contentResolver.openInputStream(uri) ?: return null
            val file = File(cacheDir, "${prefix}_${System.currentTimeMillis()}.audio")
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

    private fun doMix() {
        val main = mainFile ?: return
        val bg = bgFile ?: return
        if (!main.exists() || !bg.exists()) {
            Toast.makeText(this, "فایل‌ها پیدا نشدند. دوباره انتخاب کنید.", Toast.LENGTH_LONG).show()
            return
        }

        isMixing = true
        binding.progressBar.visibility = View.VISIBLE
        binding.progressBar.isIndeterminate = false
        binding.progressBar.max = 100
        binding.progressBar.progress = 2
        binding.btnMix.isEnabled = false

        val durationSec = getAudioDurationSec(main)
        val mins = if (durationSec > 0) (durationSec / 60.0) else 0.0
        binding.tvStatus.text = if (mins >= 1) {
            String.format(java.util.Locale.US, "در حال میکس فایل %.0f دقیقه‌ای... صبر کنید", mins)
        } else {
            "در حال میکس... لطفاً صبر کنید"
        }

        val outFile = File(cacheDir, "mixed_${System.currentTimeMillis()}.m4a")
        currentOutPath = outFile.absolutePath

        val intent = Intent(this, MixService::class.java).apply {
            putExtra(MixService.EXTRA_MAIN, main.absolutePath)
            putExtra(MixService.EXTRA_BG, bg.absolutePath)
            putExtra(MixService.EXTRA_OUT, outFile.absolutePath)
            putExtra(MixService.EXTRA_MAIN_VOL, mainVolume)
            putExtra(MixService.EXTRA_BG_VOL, bgVolume)
            putExtra(MixService.EXTRA_MAIN_SPEED, mainSpeed)
            putExtra(MixService.EXTRA_BG_SPEED, bgSpeed)
            putExtra(MixService.EXTRA_MAIN_PITCH, mainPitch)
            putExtra(MixService.EXTRA_BG_PITCH, bgPitch)
            putExtra(MixService.EXTRA_MAIN_ECHO, mainEcho)
            putExtra(MixService.EXTRA_BG_ECHO, bgEcho)
            putExtra(MixService.EXTRA_FADE, binding.cbFade.isChecked)
            putExtra(MixService.EXTRA_DURATION_SEC, durationSec)
            putExtra(MixService.EXTRA_OVERLAYS, prefs.getString(EffectsActivity.KEY_OVERLAYS, "") ?: "")
        }
        try {
            startService(intent)
        } catch (e: Exception) {
            finishMix(null, e.message ?: "شروع سرویس میکس ناموفق")
            return
        }

        startProgress(outFile, durationSec)

        val timeoutMs = when {
            durationSec > 3600 -> 45 * 60_000L
            durationSec > 1800 -> 30 * 60_000L
            durationSec > 600 -> 20 * 60_000L
            durationSec > 120 -> 10 * 60_000L
            else -> 5 * 60_000L
        }
        mixTimeoutRunnable?.let { uiHandler.removeCallbacks(it) }
        mixTimeoutRunnable = Runnable {
            if (isMixing) {
                val f = currentOutPath?.let { File(it) }
                if (f != null && f.exists() && f.length() > 1000) {
                    finishMix(f, "")
                } else {
                    cancelOngoingMix()
                    finishMix(null, "زمان میکس تمام شد. دوباره تلاش کنید.")
                }
            }
        }
        uiHandler.postDelayed(mixTimeoutRunnable!!, timeoutMs)
    }

    private fun startProgress(outFile: File, durationSec: Double) {
        stopProgress()
        val expectedBytes = if (durationSec > 1) (durationSec * 12000).toLong() else 500_000L
        progressRunnable = object : Runnable {
            override fun run() {
                if (!isMixing) return
                val len = if (outFile.exists()) outFile.length() else 0L
                val pct = if (expectedBytes > 0) {
                    ((len * 90) / expectedBytes).toInt().coerceIn(2, 90)
                } else 5
                binding.progressBar.progress = pct
                binding.tvStatus.text = "در حال میکس... $pct٪"
                uiHandler.postDelayed(this, 800)
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
        mixTimeoutRunnable?.let { uiHandler.removeCallbacks(it) }
        mixTimeoutRunnable = null
        binding.btnMix.isEnabled = true
        binding.progressBar.visibility = View.GONE

        if (file != null && file.exists() && file.length() > 200) {
            outputFile = file
            binding.progressBar.progress = 100
            binding.tvStatus.text = "✅ میکس آماده است — پخش یا ذخیره کنید"
            Toast.makeText(this, "میکس با موفقیت انجام شد", Toast.LENGTH_SHORT).show()
        } else {
            binding.tvStatus.text = "❌ ${error.ifEmpty { "میکس ناموفق" }}"
            Toast.makeText(this, error.ifEmpty { "میکس ناموفق" }, Toast.LENGTH_LONG).show()
        }
    }

    private fun cancelOngoingMix() {
        try {
            val i = Intent(this, MixService::class.java).apply { action = MixService.ACTION_CANCEL }
            startService(i)
        } catch (_: Exception) {}
        try { com.arthenica.ffmpegkit.FFmpegKit.cancel() } catch (_: Throwable) {}
    }

    private fun resetAll() {
        if (isMixing) cancelOngoingMix()
        isMixing = false
        stopProgress()
        mixTimeoutRunnable?.let { uiHandler.removeCallbacks(it) }
        mixTimeoutRunnable = null
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
                    val name = "mixed_${System.currentTimeMillis()}.m4a"
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        val values = ContentValues().apply {
                            put(MediaStore.Audio.Media.DISPLAY_NAME, name)
                            put(MediaStore.Audio.Media.MIME_TYPE, "audio/mp4")
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
