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

    private var mainVolume = 1.0
    private var bgVolume = 0.5
    private var mainSpeed = 1.0
    private var bgSpeed = 1.0
    private var mainPitch = 1.0
    private var bgPitch = 1.0
    private var mainEcho = 0.0
    private var bgEcho = 0.0
    private var mainFade = false
    private var bgFade = false

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
            binding.tvMainFile.contentDescription = "فایل اصلی: ${binding.tvMainFile.text}"
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
            binding.tvBgFile.contentDescription = "فایل پس‌زمینه: ${binding.tvBgFile.text}"
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
            R.id.menu_guide -> {
                showTextDialog(getString(R.string.menu_guide), getString(R.string.guide_text))
                true
            }
            R.id.menu_about -> {
                showAboutDialog()
                true
            }
            R.id.menu_effects -> {
                startActivity(Intent(this, EffectsActivity::class.java))
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
        binding.btnMainSettings.setOnClickListener { showFileSettingsDialog(true) }
        binding.btnBgSettings.setOnClickListener { showFileSettingsDialog(false) }
        binding.btnEffects.setOnClickListener {
            startActivity(Intent(this, EffectsActivity::class.java))
        }
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
        addSeek("صدا (۰–۱۰۰)", 100, vol) { p -> if (isMain) mainVolume = p / 100.0 else bgVolume = p / 100.0 }
        addSeek("سرعت (۵۰–۲۰۰٪)", 200, spd.coerceIn(50, 200)) { p ->
            val v = p.coerceIn(50, 200) / 100.0
            if (isMain) mainSpeed = v else bgSpeed = v
        }
        addSeek("زیر و بمی (۵۰–۲۰۰٪)", 200, pit.coerceIn(50, 200)) { p ->
            val v = p.coerceIn(50, 200) / 100.0
            if (isMain) mainPitch = v else bgPitch = v
        }
        addSeek("اکو (۰–۱۰۰)", 100, echo) { p ->
            val v = p / 100.0
            if (isMain) mainEcho = v else bgEcho = v
        }
        layout.addView(CheckBox(this).apply {
            text = "ورود و خروج نرم"
            isChecked = if (isMain) mainFade else bgFade
            contentDescription = "تیک ورود و خروج نرم صوت"
            setOnCheckedChangeListener { _, c -> if (isMain) mainFade = c else bgFade = c }
        })
        AlertDialog.Builder(this)
            .setTitle(if (isMain) "تنظیمات فایل اصلی" else "تنظیمات فایل پس‌زمینه")
            .setView(layout)
            .setPositiveButton("تأیید", null)
            .show()
    }

    private fun showInviteIfNeeded() {
        if (prefs.getBoolean("dont_show_invite", false)) return
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

        val useWav = binding.rbWav.isChecked
        val ext = if (useWav) "wav" else "m4a"
        val mv = mainVolume
        val bv = bgVolume

        lifecycleScope.launch {
            var errorMsg = ""
            val result = withContext(Dispatchers.IO) {
                try {
                    // Prefer SAF (no full copy) — fallback to temp copy
                    var mainPath: String? = null
                    var bgPath: String? = null
                    try {
                        mainPath = FFmpegKitConfig.getSafParameterForRead(this@MainActivity, main)
                        bgPath = FFmpegKitConfig.getSafParameterForRead(this@MainActivity, bg)
                    } catch (_: Exception) {}

                    if (mainPath.isNullOrBlank() || bgPath.isNullOrBlank()) {
                        val mf = copyUriToTemp(main, "main_${System.currentTimeMillis()}.tmp")
                        val bf = copyUriToTemp(bg, "bg_${System.currentTimeMillis()}.tmp")
                        if (mf == null || bf == null) {
                            errorMsg = "خواندن فایل‌ها ناموفق بود"
                            return@withContext null
                        }
                        mainPath = mf.absolutePath
                        bgPath = bf.absolutePath
                    }

                    val outFile = File(cacheDir, "mixed_${System.currentTimeMillis()}.$ext")

                    // SIMPLE reliable mix: loop bg to match main length, apply volumes only
                    // Advanced filters (speed/pitch/echo) applied only if changed from default
                    val mainParts = mutableListOf("volume=$mv")
                    val bgParts = mutableListOf("volume=$bv")

                    if (mainSpeed != 1.0) mainParts.add("atempo=${mainSpeed.coerceIn(0.5, 2.0)}")
                    if (bgSpeed != 1.0) bgParts.add("atempo=${bgSpeed.coerceIn(0.5, 2.0)}")
                    if (mainEcho > 0.05) mainParts.add("aecho=0.8:0.5:60:0.4")
                    if (bgEcho > 0.05) bgParts.add("aecho=0.8:0.5:60:0.4")
                    if (mainFade) {
                        mainParts.add("afade=t=in:st=0:d=1")
                        mainParts.add("afade=t=out:st=0:d=1")
                    }
                    if (bgFade) {
                        bgParts.add("afade=t=in:st=0:d=1")
                        bgParts.add("afade=t=out:st=0:d=1")
                    }

                    val mainF = mainParts.joinToString(",")
                    val bgF = bgParts.joinToString(",")

                    // stream_loop on bg input is more reliable than aloop filter
                    val codec = if (useWav) "-c:a pcm_s16le" else "-c:a aac -b:a 128k"
                    val cmd = "-y -i \"$mainPath\" -stream_loop -1 -i \"$bgPath\" " +
                            "-filter_complex \"[0:a]$mainF[a0];[1:a]$bgF[a1];[a0][a1]amix=inputs=2:duration=first:dropout_transition=2[a]\" " +
                            "-map \"[a]\" $codec -shortest \"${outFile.absolutePath}\""

                    val session = FFmpegKit.execute(cmd)
                    if (ReturnCode.isSuccess(session.returnCode) && outFile.exists() && outFile.length() > 500) {
                        outFile
                    } else {
                        // Fallback: even simpler command without extra filters
                        val simpleCmd = "-y -i \"$mainPath\" -stream_loop -1 -i \"$bgPath\" " +
                                "-filter_complex \"[0:a]volume=$mv[a0];[1:a]volume=$bv[a1];[a0][a1]amix=inputs=2:duration=first[a]\" " +
                                "-map \"[a]\" $codec -shortest \"${outFile.absolutePath}\""
                        val session2 = FFmpegKit.execute(simpleCmd)
                        if (ReturnCode.isSuccess(session2.returnCode) && outFile.exists() && outFile.length() > 500) {
                            outFile
                        } else {
                            errorMsg = (session2.allLogsAsString ?: session.allLogsAsString)?.takeLast(350)
                                ?: "میکس ناموفق بود"
                            null
                        }
                    }
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
                binding.tvStatus.text = getString(R.string.mix_success)
                binding.btnPlay.isEnabled = true
                binding.btnSave.isEnabled = true
                Toast.makeText(this@MainActivity, getString(R.string.mix_success), Toast.LENGTH_SHORT).show()
            } else {
                binding.tvStatus.text = getString(R.string.mix_failed)
                Toast.makeText(this@MainActivity, "خطا در میکس:\n$errorMsg", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun resetAll() {
        mainUri = null; bgUri = null
        mainFileName = null; bgFileName = null
        outputFile = null
        mediaPlayer?.release(); mediaPlayer = null; isPlaying = false
        mainVolume = 1.0; bgVolume = 0.5
        mainSpeed = 1.0; bgSpeed = 1.0
        mainPitch = 1.0; bgPitch = 1.0
        mainEcho = 0.0; bgEcho = 0.0
        mainFade = false; bgFade = false
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
        } catch (e: Exception) {
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
        } catch (_: Exception) {}
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
                val dest = File(downloads, displayName)
                FileInputStream(file).use { input -> FileOutputStream(dest).use { output -> input.copyTo(output) } }
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
