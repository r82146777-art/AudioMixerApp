package com.audiomixer.app

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
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
import zeroonezero.android.audio_mixer.AudioMixer
import zeroonezero.android.audio_mixer.input.GeneralAudioInput
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: SharedPreferences

    private var mainUri: Uri? = null
    private var bgUri: Uri? = null
    private var mainFileName: String? = null
    private var bgFileName: String? = null

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

    private val selectMainLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let {
            try { contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) {}
            mainUri = it
            mainFileName = getFileName(it)
            binding.tvMainFile.text = mainFileName ?: getString(R.string.no_file_selected)
        }
    }

    private val selectBgLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let {
            try { contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) {}
            bgUri = it
            bgFileName = getFileName(it)
            binding.tvBgFile.text = bgFileName ?: getString(R.string.no_file_selected)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        prefs = getSharedPreferences("audiomixer_prefs", Context.MODE_PRIVATE)

        if (savedInstanceState != null) {
            mainUri = savedInstanceState.getParcelable("mainUri")
            bgUri = savedInstanceState.getParcelable("bgUri")
            mainFileName = savedInstanceState.getString("mainFileName")
            bgFileName = savedInstanceState.getString("bgFileName")
            if (mainUri != null) binding.tvMainFile.text = mainFileName ?: getString(R.string.no_file_selected)
            if (bgUri != null) binding.tvBgFile.text = bgFileName ?: getString(R.string.no_file_selected)
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
        outState.putParcelable("mainUri", mainUri)
        outState.putParcelable("bgUri", bgUri)
        outState.putString("mainFileName", mainFileName)
        outState.putString("bgFileName", bgFileName)
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
        binding.btnSelectMain.setOnClickListener { selectMainLauncher.launch(arrayOf("audio/*")) }
        binding.btnSelectBg.setOnClickListener { selectBgLauncher.launch(arrayOf("audio/*")) }
        binding.btnMainSettings.setOnClickListener { showVolumeDialog(true) }
        binding.btnBgSettings.setOnClickListener { showVolumeDialog(false) }
        binding.btnEffects.setOnClickListener { startActivity(Intent(this, EffectsActivity::class.java)) }
        binding.btnMix.setOnClickListener {
            if (mainUri == null || bgUri == null) {
                Toast.makeText(this, getString(R.string.select_both_files), Toast.LENGTH_SHORT).show()
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

    private fun getFileName(uri: Uri): String? {
        var name: String? = null
        try {
            contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (cursor.moveToFirst() && idx >= 0) name = cursor.getString(idx)
            }
        } catch (_: Exception) {}
        return name
    }

    private fun copyUriToTemp(uri: Uri, prefix: String): File? {
        return try {
            val input = contentResolver.openInputStream(uri) ?: return null
            val file = File(cacheDir, "${prefix}_${System.currentTimeMillis()}.tmp")
            FileOutputStream(file).use { out -> input.copyTo(out) }
            input.close()
            file
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /** Root-stable mix: 1) Java mixer first  2) FFmpeg with argument array (no crash from quotes) */
    private fun doMix() {
        val main = mainUri ?: return
        val bg = bgUri ?: return

        binding.progressBar.visibility = View.VISIBLE
        binding.progressBar.isIndeterminate = true
        binding.tvStatus.text = getString(R.string.mixing)
        binding.btnMix.isEnabled = false

        val format = when {
            binding.rbWav.isChecked -> "wav"
            binding.rbM4a.isChecked -> "m4a"
            else -> "mp3"
        }

        lifecycleScope.launch {
            var errorMsg = ""
            val result = withContext(Dispatchers.IO) {
                // --- Method 1: android_audio_mixer (stable, no native crash) ---
                try {
                    val outM4a = File(cacheDir, "mixed_${System.currentTimeMillis()}.m4a")
                    val mixer = AudioMixer(outM4a.absolutePath)
                    val in1 = GeneralAudioInput(this@MainActivity, main, null)
                    in1.setVolume(mainVolume)
                    val in2 = GeneralAudioInput(this@MainActivity, bg, null)
                    in2.setVolume(bgVolume)
                    val d1 = in1.durationUs
                    val d2 = in2.durationUs
                    if (d2 > d1 && d1 > 0) in2.setEndTimeUs(d1)
                    mixer.setLoopingEnabled(true)
                    mixer.addDataSource(in1)
                    mixer.addDataSource(in2)
                    mixer.setSampleRate(44100)
                    mixer.setBitRate(128000)
                    mixer.setChannelCount(2)
                    mixer.start()
                    mixer.processSync()
                    if (outM4a.exists() && outM4a.length() > 500) {
                        // Convert to requested format if needed
                        if (format == "m4a") return@withContext outM4a
                        val converted = convertWithFFmpeg(outM4a, format)
                        if (converted != null) return@withContext converted
                        return@withContext outM4a // fallback keep m4a
                    }
                } catch (e: Exception) {
                    errorMsg = "میکسر داخلی: ${e.message}"
                    e.printStackTrace()
                }

                // --- Method 2: FFmpeg with safe argument array ---
                try {
                    val mainFile = copyUriToTemp(main, "main") ?: return@withContext null
                    val bgFile = copyUriToTemp(bg, "bg") ?: return@withContext null
                    val outFile = File(cacheDir, "mixed_ff_${System.currentTimeMillis()}.$format")

                    val codecArgs = when (format) {
                        "wav" -> arrayOf("-c:a", "pcm_s16le")
                        "mp3" -> arrayOf("-c:a", "libmp3lame", "-b:a", "128k")
                        else -> arrayOf("-c:a", "aac", "-b:a", "128k")
                    }

                    val args = arrayOf(
                        "-y",
                        "-i", mainFile.absolutePath,
                        "-stream_loop", "-1",
                        "-i", bgFile.absolutePath,
                        "-filter_complex",
                        "[0:a]volume=$mainVolume[a0];[1:a]volume=$bgVolume[a1];[a0][a1]amix=inputs=2:duration=first[a]",
                        "-map", "[a]"
                    ) + codecArgs + arrayOf("-shortest", outFile.absolutePath)

                    val session = FFmpegKit.executeWithArguments(args)
                    if (ReturnCode.isSuccess(session.returnCode) && outFile.exists() && outFile.length() > 500) {
                        return@withContext outFile
                    }
                    errorMsg = (session.allLogsAsString ?: "").takeLast(300).ifEmpty { "FFmpeg ناموفق" }
                } catch (e: Exception) {
                    errorMsg = (errorMsg + " | FFmpeg: ${e.message}").trim()
                    e.printStackTrace()
                }
                null
            }

            binding.progressBar.visibility = View.GONE
            binding.btnMix.isEnabled = true

            if (result != null) {
                outputFile = result
                binding.tvStatus.text = getString(R.string.mix_success)
                binding.btnPlay.isEnabled = true
                binding.btnSave.isEnabled = true
                Toast.makeText(this@MainActivity, getString(R.string.mix_success), Toast.LENGTH_SHORT).show()
            } else {
                binding.tvStatus.text = getString(R.string.mix_failed)
                Toast.makeText(this@MainActivity, "خطا: ${errorMsg.ifEmpty { "میکس ناموفق" }}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun convertWithFFmpeg(input: File, format: String): File? {
        return try {
            val out = File(cacheDir, "conv_${System.currentTimeMillis()}.$format")
            val codecArgs = when (format) {
                "wav" -> arrayOf("-c:a", "pcm_s16le")
                "mp3" -> arrayOf("-c:a", "libmp3lame", "-b:a", "128k")
                else -> return input
            }
            val args = arrayOf("-y", "-i", input.absolutePath) + codecArgs + arrayOf(out.absolutePath)
            val session = FFmpegKit.executeWithArguments(args)
            if (ReturnCode.isSuccess(session.returnCode) && out.exists() && out.length() > 500) out else null
        } catch (_: Exception) {
            null
        }
    }

    private fun resetAll() {
        mainUri = null; bgUri = null
        mainFileName = null; bgFileName = null
        outputFile = null
        mediaPlayer?.release(); mediaPlayer = null; isPlaying = false
        mainVolume = 1f; bgVolume = 0.5f
        binding.tvMainFile.text = getString(R.string.no_file_selected)
        binding.tvBgFile.text = getString(R.string.no_file_selected)
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
        mainUri = Uri.fromFile(file)
        mainFileName = file.name
        binding.tvMainFile.text = mainFileName
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
