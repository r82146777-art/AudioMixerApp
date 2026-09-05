package com.audiomixer.app

import android.Manifest
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.view.View
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.audiomixer.app.databinding.ActivityMainBinding
import zeroonezero.android.audio_mixer.AudioMixer
import zeroonezero.android.audio_mixer.input.GeneralAudioInput
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private var mainUri: Uri? = null
    private var bgUri: Uri? = null
    private var mainFileName: String? = null
    private var bgFileName: String? = null

    private var outputFile: File? = null
    private var mediaPlayer: MediaPlayer? = null
    private var isPlaying = false

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
            try {
                contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: Exception) {}
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
            try {
                contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: Exception) {}
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

        // Restore previous selections after activity recreate (document picker often causes this)
        if (savedInstanceState != null) {
            mainUri = savedInstanceState.getParcelable("mainUri")
            bgUri = savedInstanceState.getParcelable("bgUri")
            mainFileName = savedInstanceState.getString("mainFileName")
            bgFileName = savedInstanceState.getString("bgFileName")
            if (mainUri != null) {
                binding.tvMainFile.text = mainFileName ?: getString(R.string.no_file_selected)
                binding.tvMainFile.contentDescription = "فایل اصلی انتخاب شده: ${binding.tvMainFile.text}"
            }
            if (bgUri != null) {
                binding.tvBgFile.text = bgFileName ?: getString(R.string.no_file_selected)
                binding.tvBgFile.contentDescription = "فایل پس‌زمینه انتخاب شده: ${binding.tvBgFile.text}"
            }
        }

        checkPermissions()
        setupSeekBars()
        setupButtons()
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
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_AUDIO)
                != PackageManager.PERMISSION_GRANTED
            ) {
                permissions.add(Manifest.permission.READ_MEDIA_AUDIO)
            }
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED
            ) {
                permissions.add(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
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
        binding.btnSelectMain.setOnClickListener {
            selectMainLauncher.launch(arrayOf("audio/*"))
        }

        binding.btnSelectBg.setOnClickListener {
            selectBgLauncher.launch(arrayOf("audio/*"))
        }

        binding.btnMix.setOnClickListener {
            if (mainUri == null || bgUri == null) {
                Toast.makeText(this, getString(R.string.select_both_files), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            mixAudio()
        }

        binding.btnPlay.setOnClickListener {
            togglePlay()
        }

        binding.btnSave.setOnClickListener {
            saveToDownloads()
        }
    }

    private fun getFileName(uri: Uri): String? {
        var name: String? = null
        try {
            contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (cursor.moveToFirst() && nameIndex >= 0) {
                    name = cursor.getString(nameIndex)
                }
            }
        } catch (_: Exception) {}
        return name
    }

    private fun mixAudio() {
        val main = mainUri ?: return
        val bg = bgUri ?: return

        binding.progressBar.visibility = View.VISIBLE
        binding.tvStatus.text = getString(R.string.mixing)
        binding.btnMix.isEnabled = false

        val mainVol = binding.seekMainVolume.progress / 100f
        val bgVol = binding.seekBgVolume.progress / 100f

        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                try {
                    val outFile = File(cacheDir, "mixed_${System.currentTimeMillis()}.m4a")
                    val mixer = AudioMixer(outFile.absolutePath)

                    // Use Uri directly (better for long files, no full copy to cache)
                    val inputMain = GeneralAudioInput(this@MainActivity, main, null)
                    inputMain.setVolume(mainVol)

                    val inputBg = GeneralAudioInput(this@MainActivity, bg, null)
                    inputBg.setVolume(bgVol)

                    // Make main the primary track (output length = main length)
                    // If bg is shorter → loop it until main ends
                    // If bg is longer → cut it to main length
                    val mainDuration = inputMain.durationUs
                    val bgDuration = inputBg.durationUs

                    if (bgDuration > mainDuration && mainDuration > 0) {
                        // Cut longer background to match main
                        inputBg.setEndTimeUs(mainDuration)
                    }

                    // Enable looping so shorter track(s) repeat to fill the longer one
                    mixer.setLoopingEnabled(true)

                    mixer.addDataSource(inputMain)
                    mixer.addDataSource(inputBg)
                    mixer.setSampleRate(44100)
                    mixer.setBitRate(128000)
                    mixer.setChannelCount(2)

                    mixer.start()
                    mixer.processSync()

                    outFile
                } catch (e: Exception) {
                    e.printStackTrace()
                    null
                }
            }

            binding.progressBar.visibility = View.GONE
            binding.btnMix.isEnabled = true

            if (result != null && result.exists() && result.length() > 0) {
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

    private fun togglePlay() {
        val file = outputFile ?: return
        if (isPlaying) {
            mediaPlayer?.pause()
            isPlaying = false
            binding.btnPlay.text = getString(R.string.play_result)
            binding.btnPlay.contentDescription = "دکمه پخش نتیجه. برای پخش فایل ساخته شده فشار دهید"
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
                binding.btnPlay.contentDescription = "دکمه مکث. برای مکث پخش فشار دهید"
            } catch (e: Exception) {
                e.printStackTrace()
                Toast.makeText(this, "خطا در پخش", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun saveToDownloads() {
        val file = outputFile ?: return
        val mime = "audio/mp4"
        val displayName = "mixed_audio_${System.currentTimeMillis()}.m4a"

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
                        FileInputStream(file).use { input ->
                            input.copyTo(output)
                        }
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
                    FileOutputStream(dest).use { output ->
                        input.copyTo(output)
                    }
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
    }
}
