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
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
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
import com.arthenica.ffmpegkit.StatisticsCallback
import com.audiomixer.app.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import zeroonezero.android.audio_mixer.AudioMixer
import zeroonezero.android.audio_mixer.input.GeneralAudioInput
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
                if (f.exists()) { mainFile = f; updateMainUi("✅ ${mainFileName.ifBlank { f.name }}") }
            }
            savedInstanceState.getString("bgPath")?.let { path ->
                val f = File(path)
                if (f.exists()) { bgFile = f; updateBgUi("✅ ${bgFileName.ifBlank { f.name }}") }
            }
        }

        checkPermissions()
        setupButtons()
        showInviteIfNeeded()
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

    /** Settings: volume, speed, pitch, echo (fade is global near format) */
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

    private fun needsAdvancedEffects(): Boolean {
        return mainSpeed != 1f || bgSpeed != 1f ||
                mainPitch != 1f || bgPitch != 1f ||
                mainEcho > 0.01f || bgEcho > 0.01f ||
                binding.cbFade.isChecked
    }

    private fun isLongFile(f: File): Boolean = f.length() > 3_000_000L // ~3MB ≈ چند دقیقه

    private fun doMix() {
        val main = mainFile ?: return
        val bg = bgFile ?: return
        if (!main.exists() || !bg.exists()) {
            Toast.makeText(this, "فایل‌ها پیدا نشدند. دوباره انتخاب کنید.", Toast.LENGTH_LONG).show()
            return
        }

        isMixing = true
        binding.progressBar.visibility = View.VISIBLE
        binding.progressBar.isIndeterminate = true
        binding.tvStatus.text = "در حال میکس... لطفاً صبر کنید"
        binding.btnMix.isEnabled = false

        val useFfmpeg = needsAdvancedEffects() || isLongFile(main) || isLongFile(bg)

        if (useFfmpeg) {
            mixWithFFmpegAsync(main, bg)
        } else {
            mixWithJava(main, bg)
        }
    }

    private fun mixWithJava(main: File, bg: File) {
        val mv = mainVolume
        val bv = bgVolume
        lifecycleScope.launch {
            var errorMsg = ""
            val result = withContext(Dispatchers.IO) {
                try {
                    val outFile = File(cacheDir, "mixed_${System.currentTimeMillis()}.m4a")
                    val mixer = AudioMixer(outFile.absolutePath)
                    val in1 = GeneralAudioInput(main.absolutePath)
                    in1.setVolume(mv)
                    val in2 = GeneralAudioInput(bg.absolutePath)
                    in2.setVolume(bv)
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
                    if (outFile.exists() && outFile.length() > 500) outFile else {
                        errorMsg = "خروجی خالی"
                        null
                    }
                } catch (e: Exception) {
                    errorMsg = e.message ?: "خطا"
                    null
                }
            }
            finishMix(result, errorMsg)
        }
    }

    private fun buildTrackFilter(vol: Float, speed: Float, pitch: Float, echo: Float): String {
        val parts = mutableListOf("volume=${vol.coerceIn(0f, 2f)}")
        val sp = speed.coerceIn(0.5f, 2.0f)
        if (sp != 1f) parts.add("atempo=$sp")
        val p = pitch.coerceIn(0.5f, 2.0f)
        if (p != 1f) {
            parts.add("asetrate=44100*$p")
            parts.add("aresample=44100")
            // compensate duration change from asetrate
            val comp = (1.0 / p).toFloat().coerceIn(0.5f, 2.0f)
            parts.add("atempo=$comp")
        }
        if (echo > 0.05f) {
            val g = (0.5f * echo).coerceIn(0.1f, 0.8f)
            parts.add("aecho=0.8:$g:60:0.4")
        }
        return parts.joinToString(",")
    }

    private fun mixWithFFmpegAsync(main: File, bg: File) {
        val format = when {
            binding.rbWav.isChecked -> "wav"
            binding.rbM4a.isChecked -> "m4a"
            else -> "mp3"
        }
        // audio package may not have libmp3lame → use aac for mp3 choice as m4a
        val outExt = if (format == "wav") "wav" else "m4a"
        val outFile = File(cacheDir, "mixed_${System.currentTimeMillis()}.$outExt")

        val mainF = buildTrackFilter(mainVolume, mainSpeed, mainPitch, mainEcho)
        val bgF = buildTrackFilter(bgVolume, bgSpeed, bgPitch, bgEcho)

        var filter = "[0:a]$mainF[a0];[1:a]$bgF[a1];[a0][a1]amix=inputs=2:duration=first:dropout_transition=2[am]"
        if (binding.cbFade.isChecked) {
            filter += ";[am]afade=t=in:st=0:d=2,afade=t=out:st=0:d=2[a]"
        } else {
            filter += "[a]"
            // fix: last label
            filter = "[0:a]$mainF[a0];[1:a]$bgF[a1];[a0][a1]amix=inputs=2:duration=first:dropout_transition=2[a]"
        }

        if (binding.cbFade.isChecked) {
            filter = "[0:a]$mainF[a0];[1:a]$bgF[a1];[a0][a1]amix=inputs=2:duration=first:dropout_transition=2[am];[am]afade=t=in:st=0:d=1.5,afade=t=out:st=0:d=1.5[a]"
        }

        val codecArgs = if (outExt == "wav") {
            arrayOf("-c:a", "pcm_s16le")
        } else {
            arrayOf("-c:a", "aac", "-b:a", "128k")
        }

        val args = arrayOf(
            "-y",
            "-i", main.absolutePath,
            "-stream_loop", "-1",
            "-i", bg.absolutePath,
            "-filter_complex", filter,
            "-map", "[a]"
        ) + codecArgs + arrayOf("-shortest", outFile.absolutePath)

        binding.tvStatus.text = "میکس فایل‌های طولانی / افکت‌ها... صبر کنید"

        try {
            FFmpegKit.executeWithArgumentsAsync(args, { session ->
                runOnUiThread {
                    if (ReturnCode.isSuccess(session.returnCode) && outFile.exists() && outFile.length() > 200) {
                        finishMix(outFile, "")
                    } else {
                        // Fallback simple mix without advanced filters
                        fallbackSimpleFFmpeg(main, bg, outFile)
                    }
                }
            }, null, StatisticsCallback { stats ->
                val t = stats.time
                if (t > 0) {
                    runOnUiThread {
                        binding.tvStatus.text = "در حال میکس... ${t / 1000} ثانیه پردازش شده"
                        binding.progressBar.isIndeterminate = false
                        binding.progressBar.progress = ((t / 1000) % 100).toInt()
                    }
                }
            })
        } catch (e: Exception) {
            // If FFmpeg native fails to load, fall back to Java
            Toast.makeText(this, "FFmpeg در دسترس نیست، میکس ساده...", Toast.LENGTH_SHORT).show()
            mixWithJava(main, bg)
        }
    }

    private fun fallbackSimpleFFmpeg(main: File, bg: File, outFile: File) {
        val args = arrayOf(
            "-y",
            "-i", main.absolutePath,
            "-stream_loop", "-1",
            "-i", bg.absolutePath,
            "-filter_complex", "[0:a]volume=$mainVolume[a0];[1:a]volume=$bgVolume[a1];[a0][a1]amix=inputs=2:duration=first[a]",
            "-map", "[a]",
            "-c:a", "aac", "-b:a", "128k",
            "-shortest", outFile.absolutePath
        )
        try {
            FFmpegKit.executeWithArgumentsAsync(args, { session ->
                runOnUiThread {
                    if (ReturnCode.isSuccess(session.returnCode) && outFile.exists() && outFile.length() > 200) {
                        finishMix(outFile, "")
                    } else {
                        finishMix(null, "میکس ناموفق. فایل کوتاه‌تر را امتحان کنید.")
                    }
                }
            }, null, null)
        } catch (e: Exception) {
            finishMix(null, e.message ?: "خطای FFmpeg")
        }
    }

    private fun finishMix(result: File?, errorMsg: String) {
        isMixing = false
        binding.progressBar.visibility = View.GONE
        binding.btnMix.isEnabled = true
        if (result != null) {
            outputFile = result
            binding.tvStatus.text = "✅ میکس موفق — ${result.name}"
            binding.btnPlay.isEnabled = true
            binding.btnSave.isEnabled = true
            Toast.makeText(this, "میکس موفق بود!", Toast.LENGTH_SHORT).show()
        } else {
            binding.tvStatus.text = getString(R.string.mix_failed)
            Toast.makeText(this, "خطا: ${errorMsg.ifEmpty { "میکس ناموفق" }}", Toast.LENGTH_LONG).show()
        }
    }

    private fun resetAll() {
        if (isMixing) {
            try { FFmpegKit.cancel() } catch (_: Exception) {}
            isMixing = false
        }
        mainFile = null; bgFile = null
        mainFileName = ""; bgFileName = ""
        outputFile = null
        mediaPlayer?.release(); mediaPlayer = null; isPlaying = false
        mainVolume = 1f; bgVolume = 0.5f
        mainSpeed = 1f; bgSpeed = 1f
        mainPitch = 1f; bgPitch = 1f
        mainEcho = 0f; bgEcho = 0f
        binding.cbFade.isChecked = false
        updateMainUi(getString(R.string.no_file_selected))
        updateBgUi(getString(R.string.no_file_selected))
        binding.btnPlay.isEnabled = false
        binding.btnSave.isEnabled = false
        binding.tvStatus.text = ""
        binding.btnPlay.text = getString(R.string.play_result)
        Toast.makeText(this, "بازنشانی انجام شد", Toast.LENGTH_SHORT).show()
    }

    private fun toggleRecord() {
        if (isRecording) stopRecording() else startRecording()
    }

    private fun startRecording() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissionLauncher.launch(arrayOf(Manifest.permission.RECORD_AUDIO))
            return
        }
        try {
            recordedFile = File(cacheDir, "record_${System.currentTimeMillis()}.m4a")
            mediaRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(this)
            else @Suppress("DEPRECATION") MediaRecorder()
            mediaRecorder!!.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setOutputFile(recordedFile!!.absolutePath)
                prepare()
                start()
            }
            isRecording = true
            binding.btnRecord.text = getString(R.string.record_stop)
            binding.tvRecordStatus.text = getString(R.string.recording)
            binding.btnDeleteRecord.isEnabled = false
            binding.btnUseRecordAsMain.isEnabled = false
        } catch (_: Exception) {
            Toast.makeText(this, "خطا در شروع ضبط", Toast.LENGTH_SHORT).show()
        }
    }

    private fun stopRecording() {
        try {
            mediaRecorder?.stop()
            mediaRecorder?.release()
            mediaRecorder = null
            isRecording = false
            binding.btnRecord.text = getString(R.string.record_start)
            binding.tvRecordStatus.text = getString(R.string.record_saved)
            binding.btnDeleteRecord.isEnabled = true
            binding.btnUseRecordAsMain.isEnabled = true
        } catch (_: Exception) {}
    }

    private fun deleteRecord() {
        recordedFile?.delete()
        recordedFile = null
        binding.btnDeleteRecord.isEnabled = false
        binding.btnUseRecordAsMain.isEnabled = false
        binding.tvRecordStatus.text = ""
    }

    private fun useRecordAsMain() {
        val file = recordedFile ?: return
        if (!file.exists()) return
        mainFile = file
        mainFileName = file.name
        updateMainUi("✅ ${file.name}")
        Toast.makeText(this, "ضبط به عنوان فایل اصلی تنظیم شد", Toast.LENGTH_SHORT).show()
    }

    private fun togglePlay() {
        val file = outputFile ?: return
        if (isPlaying) {
            mediaPlayer?.pause()
            isPlaying = false
            binding.btnPlay.text = getString(R.string.play_result)
        } else {
            try {
                mediaPlayer?.release()
                mediaPlayer = MediaPlayer().apply {
                    setDataSource(file.absolutePath)
                    prepare()
                    start()
                    setOnCompletionListener {
                        this@MainActivity.isPlaying = false
                        binding.btnPlay.text = getString(R.string.play_result)
                    }
                }
                isPlaying = true
                binding.btnPlay.text = getString(R.string.pause_result)
            } catch (_: Exception) {
                Toast.makeText(this, "خطا در پخش", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun saveToDownloads() {
        val file = outputFile ?: return
        val ext = file.extension.ifEmpty { "m4a" }
        val mime = if (ext == "wav") "audio/wav" else "audio/mp4"
        val displayName = "mixed_audio_${System.currentTimeMillis()}.$ext"
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Audio.Media.DISPLAY_NAME, displayName)
                    put(MediaStore.Audio.Media.MIME_TYPE, mime)
                    put(MediaStore.Audio.Media.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                    put(MediaStore.Audio.Media.IS_PENDING, 1)
                }
                val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                val itemUri = contentResolver.insert(collection, values)
                itemUri?.let { uri ->
                    contentResolver.openOutputStream(uri)?.use { output ->
                        FileInputStream(file).use { input -> input.copyTo(output) }
                    }
                    values.clear()
                    values.put(MediaStore.Audio.Media.IS_PENDING, 0)
                    contentResolver.update(uri, values, null, null)
                }
            } else {
                val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                if (!downloads.exists()) downloads.mkdirs()
                FileInputStream(file).use { input ->
                    FileOutputStream(File(downloads, displayName)).use { output -> input.copyTo(output) }
                }
            }
            Toast.makeText(this, getString(R.string.saved_success), Toast.LENGTH_LONG).show()
            binding.tvStatus.text = getString(R.string.saved_success)
        } catch (e: Exception) {
            Toast.makeText(this, "خطا در ذخیره: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        mediaPlayer?.release()
        if (isMixing) try { FFmpegKit.cancel() } catch (_: Exception) {}
        if (isRecording) {
            try { mediaRecorder?.stop() } catch (_: Exception) {}
            mediaRecorder?.release()
        }
    }
}
