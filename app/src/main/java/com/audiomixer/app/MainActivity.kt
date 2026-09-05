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
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFmpegKitConfig
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

    private var mainUri: Uri? = null
    private var bgUri: Uri? = null
    private var mainFileName: String? = null
    private var bgFileName: String? = null

    private var outputFile: File? = null
    private var mediaPlayer: MediaPlayer? = null
    private var isPlaying = false

    private var mediaRecorder: MediaRecorder? = null
    private var recordedFile: File? = null
    private var isRecording = false

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val granted = permissions.values.all { it }
        if (!granted) {
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
            binding.tvMainFile.contentDescription = "فایل اصلی انتخاب شده: ${binding.tvMainFile.text}"
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
            binding.tvBgFile.contentDescription = "فایل پس‌زمینه انتخاب شده: ${binding.tvBgFile.text}"
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
            if (mainUri != null) {
                binding.tvMainFile.text = mainFileName ?: getString(R.string.no_file_selected)
            }
            if (bgUri != null) {
                binding.tvBgFile.text = bgFileName ?: getString(R.string.no_file_selected)
            }
        }

        checkPermissions()
        setupSeekBars()
        setupButtons()
        showInviteIfNeeded()
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.menu_guide -> {
                showTextDialog(getString(R.string.menu_guide), getString(R.string.guide_text))
                true
            }
            R.id.menu_about -> {
                showAboutDialog()
                true
            }
            R.id.menu_effects -> {
                // Open Freesound search in browser (internet required)
                try {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://freesound.org/search/?q="))
                    startActivity(intent)
                } catch (e: Exception) {
                    Toast.makeText(this, "مرورگر پیدا نشد", Toast.LENGTH_SHORT).show()
                }
                true
            }
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
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.READ_MEDIA_AUDIO)
            }
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.RECORD_AUDIO)
        }
        if (permissions.isNotEmpty()) {
            requestPermissionLauncher.launch(permissions.toTypedArray())
        }
    }

    private fun setupSeekBars() {
        binding.seekMainVolume.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                binding.tvMainVolume.text = "$progress٪"
                binding.tvMainVolume.contentDescription = "صدای فایل اصلی: $progress درصد"
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
        binding.seekBgVolume.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                binding.tvBgVolume.text = "$progress٪"
                binding.tvBgVolume.contentDescription = "صدای فایل پس‌زمینه: $progress درصد"
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
    }

    private fun setupButtons() {
        binding.btnSelectMain.setOnClickListener { selectMainLauncher.launch(arrayOf("audio/*")) }
        binding.btnSelectBg.setOnClickListener { selectBgLauncher.launch(arrayOf("audio/*")) }

        binding.btnMix.setOnClickListener {
            if (mainUri == null || bgUri == null) {
                Toast.makeText(this, getString(R.string.select_both_files), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            mixWithFFmpeg()
        }

        binding.btnReset.setOnClickListener { resetAll() }

        binding.btnPlay.setOnClickListener { togglePlay() }
        binding.btnSave.setOnClickListener { saveToDownloads() }

        binding.btnRecord.setOnClickListener { toggleRecord() }
        binding.btnDeleteRecord.setOnClickListener { deleteRecord() }
        binding.btnUseRecordAsMain.setOnClickListener { useRecordAsMain() }
    }

    private fun showInviteIfNeeded() {
        if (prefs.getBoolean("dont_show_invite", false)) return
        val view = layoutInflater.inflate(android.R.layout.simple_list_item_1, null)
        val checkBox = CheckBox(this).apply {
            text = getString(R.string.invite_dont_show)
            contentDescription = "دیگر این پیام را نشان نده"
        }
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
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton("باشه", null)
            .show()
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
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (cursor.moveToFirst() && nameIndex >= 0) name = cursor.getString(nameIndex)
            }
        } catch (_: Exception) {}
        return name
    }

    private fun copyUriToTemp(uri: Uri, name: String): File? {
        return try {
            val input = contentResolver.openInputStream(uri) ?: return null
            val file = File(cacheDir, name)
            FileOutputStream(file).use { out -> input.copyTo(out) }
            input.close()
            file
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private fun mixWithFFmpeg() {
        val main = mainUri ?: return
        val bg = bgUri ?: return

        binding.progressBar.visibility = View.VISIBLE
        binding.progressBar.isIndeterminate = true
        binding.tvStatus.text = getString(R.string.mixing)
        binding.btnMix.isEnabled = false

        val mainVol = binding.seekMainVolume.progress / 100.0
        val bgVol = binding.seekBgVolume.progress / 100.0

        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                try {
                    val mainFile = copyUriToTemp(main, "main_temp_${System.currentTimeMillis()}") ?: return@withContext null
                    val bgFile = copyUriToTemp(bg, "bg_temp_${System.currentTimeMillis()}") ?: return@withContext null
                    val outFile = File(cacheDir, "mixed_${System.currentTimeMillis()}.m4a")

                    // FFmpeg: loop bg if shorter, cut if longer, apply volumes, duration = main
                    // [1] = bg, [0] = main
                    val cmd = "-y -i \"${mainFile.absolutePath}\" -i \"${bgFile.absolutePath}\" " +
                            "-filter_complex \"[1:a]volume=${bgVol},aloop=loop=-1:size=2e+09[bg];" +
                            "[0:a]volume=${mainVol}[main];" +
                            "[main][bg]amix=inputs=2:duration=first:dropout_transition=0[a]\" " +
                            "-map \"[a]\" -c:a aac -b:a 128k \"${outFile.absolutePath}\""

                    val session = FFmpegKit.execute(cmd)
                    if (ReturnCode.isSuccess(session.returnCode)) {
                        outFile
                    } else {
                        null
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                    null
                }
            }

            binding.progressBar.visibility = View.GONE
            binding.btnMix.isEnabled = true

            if (result != null && result.exists() && result.length() > 1000) {
                outputFile = result
                binding.tvStatus.text = getString(R.string.mix_success)
                binding.btnPlay.isEnabled = true
                binding.btnSave.isEnabled = true
                Toast.makeText(this@MainActivity, getString(R.string.mix_success), Toast.LENGTH_SHORT).show()
            } else {
                binding.tvStatus.text = getString(R.string.mix_failed)
                Toast.makeText(this@MainActivity, getString(R.string.mix_failed), Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun resetAll() {
        mainUri = null
        bgUri = null
        mainFileName = null
        bgFileName = null
        outputFile = null
        mediaPlayer?.release()
        mediaPlayer = null
        isPlaying = false
        binding.tvMainFile.text = getString(R.string.no_file_selected)
        binding.tvBgFile.text = getString(R.string.no_file_selected)
        binding.btnPlay.isEnabled = false
        binding.btnSave.isEnabled = false
        binding.tvStatus.text = ""
        binding.btnPlay.text = getString(R.string.play_result)
        Toast.makeText(this, "بازنشانی انجام شد", Toast.LENGTH_SHORT).show()
    }

    private fun toggleRecord() {
        if (isRecording) {
            stopRecording()
        } else {
            startRecording()
        }
    }

    private fun startRecording() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissionLauncher.launch(arrayOf(Manifest.permission.RECORD_AUDIO))
            return
        }
        try {
            recordedFile = File(cacheDir, "record_${System.currentTimeMillis()}.m4a")
            mediaRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(this)
            } else {
                @Suppress("DEPRECATION") MediaRecorder()
            }.apply {
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
        } catch (e: Exception) {
            e.printStackTrace()
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
            Toast.makeText(this, getString(R.string.record_saved), Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun deleteRecord() {
        recordedFile?.delete()
        recordedFile = null
        binding.btnDeleteRecord.isEnabled = false
        binding.btnUseRecordAsMain.isEnabled = false
        binding.tvRecordStatus.text = ""
        Toast.makeText(this, "ضبط حذف شد", Toast.LENGTH_SHORT).show()
    }

    private fun useRecordAsMain() {
        val file = recordedFile ?: return
        if (!file.exists()) return
        mainUri = Uri.fromFile(file)
        mainFileName = file.name
        binding.tvMainFile.text = mainFileName
        binding.tvMainFile.contentDescription = "فایل اصلی: ${mainFileName}"
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
            } catch (e: Exception) {
                e.printStackTrace()
                Toast.makeText(this, "خطا در پخش", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun saveToDownloads() {
        val file = outputFile ?: return
        val displayName = "mixed_audio_${System.currentTimeMillis()}.m4a"
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Audio.Media.DISPLAY_NAME, displayName)
                    put(MediaStore.Audio.Media.MIME_TYPE, "audio/mp4")
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
                val dest = File(downloads, displayName)
                FileInputStream(file).use { input ->
                    FileOutputStream(dest).use { output -> input.copyTo(output) }
                }
            }
            Toast.makeText(this, getString(R.string.saved_success), Toast.LENGTH_LONG).show()
            binding.tvStatus.text = getString(R.string.saved_success)
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(this, "خطا در ذخیره: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        mediaPlayer?.release()
        mediaPlayer = null
        if (isRecording) {
            try { mediaRecorder?.stop() } catch (_: Exception) {}
            mediaRecorder?.release()
        }
    }
}
