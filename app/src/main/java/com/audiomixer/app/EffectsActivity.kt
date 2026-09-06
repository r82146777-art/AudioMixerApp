package com.audiomixer.app

import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.PI
import kotlin.math.sin

data class EffectItem(
    val name: String,
    val source: String,
    val pathOrUrl: String
)

class EffectsActivity : AppCompatActivity() {

    private lateinit var etSearch: EditText
    private lateinit var btnSearch: Button
    private lateinit var btnAddLocal: Button
    private lateinit var listView: ListView
    private lateinit var tvHint: TextView

    private val allEffects = mutableListOf<EffectItem>()
    private val shownEffects = mutableListOf<EffectItem>()
    private var player: MediaPlayer? = null

    private val onlineCatalog = listOf(
        EffectItem("Sample 1", "online", "https://www.soundhelix.com/examples/mp3/SoundHelix-Song-1.mp3"),
        EffectItem("Sample 2", "online", "https://www.soundhelix.com/examples/mp3/SoundHelix-Song-2.mp3"),
        EffectItem("Test MP3", "online", "https://archive.org/download/testmp3testfile/mpthreetest.mp3")
    )

    private val pickEffectLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let { saveLocalEffect(it) } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_effects)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "افکت‌های صوتی"

        etSearch = findViewById(R.id.etSearch)
        btnSearch = findViewById(R.id.btnSearch)
        btnAddLocal = findViewById(R.id.btnAddLocal)
        listView = findViewById(R.id.listEffects)
        tvHint = findViewById(R.id.tvHint)

        ensureBuiltinEffects()

        allEffects.clear()
        allEffects.addAll(loadLocalEffects())
        allEffects.addAll(onlineCatalog)
        shownEffects.clear()
        shownEffects.addAll(allEffects)
        refreshList()

        btnSearch.setOnClickListener { filterEffects(etSearch.text.toString().trim()) }
        btnAddLocal.setOnClickListener { pickEffectLauncher.launch(arrayOf("audio/*")) }
    }

    private fun ensureBuiltinEffects() {
        val dir = File(filesDir, "effects")
        if (!dir.exists()) dir.mkdirs()
        val builtins = listOf(
            Triple("beep", "بوق", 880.0),
            Triple("ring", "زنگ", 1200.0),
            Triple("bass", "بم", 220.0),
            Triple("whistle", "سوت", 1760.0)
        )
        for ((fileId, label, freq) in builtins) {
            val f = File(dir, "builtin_$fileId.wav")
            if (!f.exists() || f.length() < 100L) {
                try {
                    writeToneWav(f, freq, 0.6)
                    // store label sidecar for Persian name
                    File(dir, "builtin_$fileId.txt").writeText(label)
                } catch (_: Exception) {}
            }
        }
        val noise = File(dir, "builtin_noise.wav")
        if (!noise.exists() || noise.length() < 100L) {
            try {
                writeNoiseWav(noise, 0.8)
                File(dir, "builtin_noise.txt").writeText("نویز")
            } catch (_: Exception) {}
        }
    }

    private fun writeToneWav(file: File, freqHz: Double, durationSec: Double) {
        val sampleRate = 22050
        val n = (sampleRate * durationSec).toInt()
        val data = ByteArray(n * 2)
        for (i in 0 until n) {
            val t = i.toDouble() / sampleRate
            val env = when {
                t < 0.05 -> t / 0.05
                t > durationSec - 0.1 -> ((durationSec - t) / 0.1).coerceAtLeast(0.0)
                else -> 1.0
            }
            val amp = sin(2.0 * PI * freqHz * t) * 0.4 * env
            val sample = (amp * 32767.0).toInt().coerceIn(-32768, 32767)
            data[i * 2] = (sample and 0xff).toByte()
            data[i * 2 + 1] = ((sample shr 8) and 0xff).toByte()
        }
        writeWav(file, sampleRate, data)
    }

    private fun writeNoiseWav(file: File, durationSec: Double) {
        val sampleRate = 22050
        val n = (sampleRate * durationSec).toInt()
        val data = ByteArray(n * 2)
        var seed = 1234567L
        for (i in 0 until n) {
            seed = (seed * 1103515245L + 12345L) and 0x7fffffffL
            val r = ((seed % 20000L) - 10000L).toDouble() / 10000.0
            val sample = (r * 0.25 * 32767.0).toInt().coerceIn(-32768, 32767)
            data[i * 2] = (sample and 0xff).toByte()
            data[i * 2 + 1] = ((sample shr 8) and 0xff).toByte()
        }
        writeWav(file, sampleRate, data)
    }

    private fun writeWav(file: File, sampleRate: Int, pcm: ByteArray) {
        FileOutputStream(file).use { fos ->
            val out = DataOutputStream(fos)
            val dataSize = pcm.size
            out.writeBytes("RIFF")
            writeIntLE(out, 36 + dataSize)
            out.writeBytes("WAVE")
            out.writeBytes("fmt ")
            writeIntLE(out, 16)
            writeShortLE(out, 1)
            writeShortLE(out, 1)
            writeIntLE(out, sampleRate)
            writeIntLE(out, sampleRate * 2)
            writeShortLE(out, 2)
            writeShortLE(out, 16)
            out.writeBytes("data")
            writeIntLE(out, dataSize)
            out.write(pcm)
        }
    }

    private fun writeIntLE(out: DataOutputStream, v: Int) {
        out.write(v and 0xff)
        out.write((v shr 8) and 0xff)
        out.write((v shr 16) and 0xff)
        out.write((v shr 24) and 0xff)
    }

    private fun writeShortLE(out: DataOutputStream, v: Int) {
        out.write(v and 0xff)
        out.write((v shr 8) and 0xff)
    }

    private fun loadLocalEffects(): List<EffectItem> {
        val dir = File(filesDir, "effects")
        if (!dir.exists()) dir.mkdirs()
        return dir.listFiles()?.filter { it.isFile && it.extension.lowercase() in listOf("wav", "mp3", "m4a", "audio") && it.length() > 100L }?.map { f ->
            val base = f.nameWithoutExtension
            val labelFile = File(dir, "$base.txt")
            val label = if (labelFile.exists()) labelFile.readText().trim() else base.removePrefix("builtin_").replace('_', ' ')
            EffectItem(label.ifBlank { f.name }, "local", f.absolutePath)
        } ?: emptyList()
    }

    private fun filterEffects(query: String) {
        shownEffects.clear()
        if (query.isEmpty()) shownEffects.addAll(allEffects)
        else {
            val q = query.lowercase()
            shownEffects.addAll(allEffects.filter { it.name.lowercase().contains(q) })
        }
        refreshList()
        if (shownEffects.isEmpty()) {
            Toast.makeText(this, "افکتی پیدا نشد. از افزودن از گوشی استفاده کنید.", Toast.LENGTH_LONG).show()
        }
    }

    private fun refreshList() {
        tvHint.text = "تعداد: ${shownEffects.size} — افکت‌های آفلاین همیشه کار می‌کنند"
        listView.adapter = object : BaseAdapter() {
            override fun getCount() = shownEffects.size
            override fun getItem(p: Int) = shownEffects[p]
            override fun getItemId(p: Int) = p.toLong()
            override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
                val view = convertView ?: LayoutInflater.from(this@EffectsActivity)
                    .inflate(R.layout.item_effect, parent, false)
                val item = shownEffects[position]
                view.findViewById<TextView>(R.id.tvEffectName).text = item.name
                view.findViewById<TextView>(R.id.tvEffectSource).text =
                    if (item.source == "local") "آفلاین / ذخیره شده" else "آنلاین — نیاز به اینترنت"
                view.findViewById<Button>(R.id.btnPlayEffect).setOnClickListener { playEffect(item) }
                view.findViewById<Button>(R.id.btnSaveEffect).setOnClickListener {
                    if (item.source == "local") {
                        Toast.makeText(this@EffectsActivity, "قبلاً ذخیره شده", Toast.LENGTH_SHORT).show()
                    } else {
                        downloadAndSave(item)
                    }
                }
                view.findViewById<Button>(R.id.btnUseEffect).setOnClickListener {
                    Toast.makeText(
                        this@EffectsActivity,
                        "در صفحه اصلی این فایل را از حافظه به‌عنوان پس‌زمینه انتخاب کنید",
                        Toast.LENGTH_LONG
                    ).show()
                }
                return view
            }
        }
    }

    private fun playEffect(item: EffectItem) {
        try {
            player?.release()
            player = null
            if (item.source == "local") {
                playLocal(item.pathOrUrl)
            } else {
                Toast.makeText(this, "آماده‌سازی پخش...", Toast.LENGTH_SHORT).show()
                lifecycleScope.launch {
                    val tmp = withContext(Dispatchers.IO) {
                        downloadToFile(item.pathOrUrl, File(cacheDir, "preview_${System.currentTimeMillis()}.mp3"))
                    }
                    if (tmp != null) playLocal(tmp.absolutePath)
                    else Toast.makeText(this@EffectsActivity, "پخش ممکن نیست (اینترنت یا لینک)", Toast.LENGTH_LONG).show()
                }
            }
        } catch (_: Exception) {
            Toast.makeText(this, "خطا در پخش", Toast.LENGTH_SHORT).show()
        }
    }

    private fun playLocal(path: String) {
        try {
            player?.release()
            player = MediaPlayer().apply {
                setDataSource(path)
                setOnPreparedListener { start() }
                setOnErrorListener { _, _, _ ->
                    Toast.makeText(this@EffectsActivity, "پخش ممکن نیست", Toast.LENGTH_SHORT).show()
                    true
                }
                prepareAsync()
            }
        } catch (_: Exception) {
            Toast.makeText(this, "خطا در پخش", Toast.LENGTH_SHORT).show()
        }
    }

    private fun downloadAndSave(item: EffectItem) {
        Toast.makeText(this, "در حال دانلود...", Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) {
                try {
                    val dir = File(filesDir, "effects")
                    if (!dir.exists()) dir.mkdirs()
                    val safeName = item.name.replace(Regex("[^a-zA-Z0-9_\u0600-\u06FF]+"), "_")
                    val out = File(dir, "${safeName}_${System.currentTimeMillis()}.mp3")
                    downloadToFile(item.pathOrUrl, out) != null
                } catch (_: Exception) {
                    false
                }
            }
            if (ok) {
                Toast.makeText(this@EffectsActivity, "ذخیره شد — آفلاین قابل پخش است", Toast.LENGTH_SHORT).show()
                allEffects.clear()
                allEffects.addAll(loadLocalEffects())
                allEffects.addAll(onlineCatalog)
                filterEffects(etSearch.text.toString().trim())
            } else {
                Toast.makeText(
                    this@EffectsActivity,
                    "دانلود ناموفق. اینترنت را چک کنید یا از افکت‌های آفلاین استفاده کنید.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun downloadToFile(urlStr: String, out: File): File? {
        return try {
            var current = urlStr
            var redirects = 0
            while (redirects < 5) {
                val conn = (URL(current).openConnection() as HttpURLConnection).apply {
                    instanceFollowRedirects = false
                    connectTimeout = 20000
                    readTimeout = 60000
                    requestMethod = "GET"
                    setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36")
                    setRequestProperty("Accept", "*/*")
                }
                val code = conn.responseCode
                if (code in 300..399) {
                    val loc = conn.getHeaderField("Location") ?: break
                    current = if (loc.startsWith("http")) loc else URL(URL(current), loc).toString()
                    conn.disconnect()
                    redirects++
                    continue
                }
                if (code !in 200..299) {
                    conn.disconnect()
                    return null
                }
                conn.inputStream.use { input ->
                    FileOutputStream(out).use { output -> input.copyTo(output) }
                }
                conn.disconnect()
                return if (out.exists() && out.length() > 100L) out else null
            }
            null
        } catch (_: Exception) {
            null
        }
    }

    private fun saveLocalEffect(uri: Uri) {
        try {
            val dir = File(filesDir, "effects")
            if (!dir.exists()) dir.mkdirs()
            val out = File(dir, "local_${System.currentTimeMillis()}.audio")
            contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(out).use { output -> input.copyTo(output) }
            }
            if (out.exists() && out.length() > 0L) {
                Toast.makeText(this, "افکت اضافه شد", Toast.LENGTH_SHORT).show()
                allEffects.clear()
                allEffects.addAll(loadLocalEffects())
                allEffects.addAll(onlineCatalog)
                filterEffects(etSearch.text.toString().trim())
            } else {
                Toast.makeText(this, "خواندن فایل ناموفق", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Toast.makeText(this, "خطا: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    override fun onDestroy() {
        super.onDestroy()
        player?.release()
        player = null
    }
}
