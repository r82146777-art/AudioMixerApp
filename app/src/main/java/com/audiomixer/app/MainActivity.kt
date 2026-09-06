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
import android.widget.CheckBox
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: SharedPreferences

    // URI + local cached file (most reliable for mix)
    private var mainUri: Uri? = null
    private var bgUri: Uri? = null
    private var mainFile: File? = null
    private var bgFile: File? = null
    private var mainFileName: String = ""
    private var bgFileName: String = ""

    private var mainVolume = 1.0f
    private var bgVolume = 0.5f

    private var outputFile: File? = null
    private var mediaPlayer: MediaPlayer? = null
    private var isPlaying = false

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

    // GetContent is more reliable on many phones than OpenDocument
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

        // Restore UI if we have files still in cache from saved paths
        if (savedInstanceState != null) {
            mainFileName = savedInstanceState.getString("mainFileName") ?: ""
            bgFileName = savedInstanceState.getString("bgFileName") ?: ""
            val mainPath = savedInstanceState.getString("mainPath")
            val bgPath = savedInstanceState.getString("bgPath")
            if (!mainPath.isNullOrBlank()) {
                val f = File(mainPath)
                if (f.exists()) {
                    mainFile = f
                    updateMainUi(mainFileName.ifBlank { f.name })
                }
            }
            if (!bgPath.isNullOrBlank()) {
                val f = File(bgPath)
                if (f.exists()) {
                    bgFile = f
                    updateBgUi(bgFileName.ifBlank { f.name })
                }
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
        binding.btnSelectMain.setOnClickListener {
            selectMainLauncher.launch("audio/*")
        }
        binding.btnSelectBg.setOnClickListener {
            selectBgLauncher.launch("audio/*")
        }
        binding.btnMainSettings.setOnClickListener { showVolumeDialog(true) }
        binding.btnBgSettings.setOnClickListener { showVolumeDialog(false) }
        binding.btnEffects.setOnClickListener { startActivity(Intent(this, EffectsActivity::class.java)) }
        binding.btnMix.setOnClickListener {
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
        mainUri = uri
        val name = resolveName(uri)
        mainFileName = name
        // Show immediately that something was selected
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
                Toast.makeText(this@MainActivity, "نتوانست فایل اصلی را بخواند", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun onBgSelected(uri: Uri) {
        bgUri = uri
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
                Toast.makeText(this@MainActivity, "نتوانست فایل پس‌زمینه را بخواند", Toast.LENGTH_LONG).show()
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

    private fun showVolumeDialog(isMain: Boolean) {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 24)
        }
        val tv = TextView(this).apply {
            text = "صدا: ${((if (isMain) mainVolume else bgVolume) * 100).toInt()}"
        }
        layout.addView(tv)
        layout.addView(SeekBar(this).apply {
            max = 100
            progress = ((if (isMain) mainVolume else bgVolume) * 100).toInt()
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, f: Boolean) {
                    tv.text = "صدا: $p"
                    if (isMain) mainVolume = p / 100f else bgVolume = p / 100f
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        })
        AlertDialog.Builder(this)
            .setTitle(if (isMain) "صدای فایل اصلی" else "صدای فایل پس‌زمینه")
            .setView(layout)
            .setPositiveButton("تأیید", null)
            .show()
    }

    private fun showInviteIfNeeded() {
        if (prefs.getBoolean("dont_show_invite", false)) return
        val checkBox = CheckBox(this).apply { text = getString(R.string.invite_dont_show) }
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

    /** Mix from local cached files only — most reliable */
    private fun doMix() {
        val main = mainFile ?: return
        val bg = bgFile ?: return
        if (!main.exists() || !bg.exists()) {
            Toast.makeText(this, "فایل‌ها پیدا نشدند. دوباره انتخاب کنید.", Toast.LENGTH_LONG).show()
            return
        }

        binding.progressBar.visibility = View.VISIBLE
        binding.progressBar.isIndeterminate = true
        binding.tvStatus.text = getString(R.string.mixing)
        binding.btnMix.isEnabled = false

        val format = when {
            binding.rbWav.isChecked -> "wav"
            binding.rbM4a.isChecked -> "m4a"
            else -> "mp3"
        }
        val mv = mainVolume
        val bv = bgVolume

        lifecycleScope.launch {
            var errorMsg = ""
            val result = withContext(Dispatchers.IO) {
                try {
                    val outFile = File(cacheDir, "mixed_${System.currentTimeMillis()}.$format")
                    val codecArgs = when (format) {
                        "wav" -> arrayOf("-c:a", "pcm_s16le")
                        "mp3" -> arrayOf("-c:a", "libmp3lame", "-b:a", "128k")
                        else -> arrayOf("-c:a", "aac", "-b:a", "128k")
                    }

                    // Simple reliable mix: loop bg to match main length
                    val args = arrayOf(
                        "-y",
                        "-i", main.absolutePath,
                        "-stream_loop", "-1",
                        "-i", bg.absolutePath,
                        "-filter_complex",
                        "[0:a]volume=$mv[a0];[1:a]volume=$bv[a1];[a0][a1]amix=inputs=2:duration=first[a]",
                        "-map", "[a]"
                    ) + codecArgs + arrayOf("-shortest", outFile.absolutePath)

                    val session = FFmpegKit.executeWithArguments(args)
                    if (ReturnCode.isSuccess(session.returnCode) && outFile.exists() && outFile.length() > 200) {
                        return@withContext outFile
                    }

                    // Ultra-simple fallback without volume filter
                    val args2 = arrayOf(
                        "-y",
                        "-i", main.absolutePath,
                        "-stream_loop", "-1",
                        "-i", bg.absolutePath,
                        "-filter_complex", "amix=inputs=2:duration=first",
                        "-shortest"
                    ) + codecArgs + arrayOf(outFile.absolutePath)
                    val session2 = FFmpegKit.executeWithArguments(args2)
                    if (ReturnCode.isSuccess(session2.returnCode) && outFile.exists() && outFile.length() > 200) {
                        return@withContext outFile
                    }

                    errorMsg = (session2.allLogsAsString ?: session.allLogsAsString ?: "").takeLast(400)
                        .ifBlank { "میکس ناموفق بود" }
                    null
                } catch (e: Exception) {
                    errorMsg = e.message ?: "خطای داخلی"
                    e.printStackTrace()
                    null
                }
            }

            binding.progressBar.visibility = View.GONE
            binding.btnMix.isEnabled = true

            if (result != null) {
                outputFile = result
                binding.tvStatus.text = "✅ میکس موفق — ${result.name}"
                binding.btnPlay.isEnabled = true
                binding.btnSave.isEnabled = true
                Toast.makeText(this@MainActivity, "میکس موفق بود!", Toast.LENGTH_SHORT).show()
            } else {
                binding.tvStatus.text = getString(R.string.mix_failed)
                Toast.makeText(this@MainActivity, "خطا در میکس:\n$errorMsg", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun resetAll() {
        mainUri = null; bgUri = null
        mainFile = null; bgFile = null
        mainFileName = ""; bgFileName = ""
        outputFile = null
        mediaPlayer?.release(); mediaPlayer = null; isPlaying = false
        mainVolume = 1f; bgVolume = 0.5f
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
        val ext = file.extension.ifEmpty { "mp3" }
        val mime = when (ext) {
            "wav" -> "audio/wav"
            "mp3" -> "audio/mpeg"
            else -> "audio/mp4"
        }
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
        if (isRecording) {
            try { mediaRecorder?.stop() } catch (_: Exception) {}
            mediaRecorder?.release()
        }
    }
}
