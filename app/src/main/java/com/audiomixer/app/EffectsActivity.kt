package com.audiomixer.app

import android.content.Context
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
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
    val source: String, // catalog | local | online
    val pathOrUrl: String
)

class EffectsActivity : AppCompatActivity() {

    private lateinit var etSearch: EditText
    private lateinit var btnSearch: Button
    private lateinit var btnAddLocal: Button
    private lateinit var listView: ListView
    private lateinit var tvHint: TextView

    private val shownEffects = mutableListOf<EffectItem>()
    private var player: MediaPlayer? = null

    private val prefs by lazy { getSharedPreferences("audiomixer_prefs", Context.MODE_PRIVATE) }

    /** فقط نام در کاتالوگ — فایل بعد از دکمه دانلود ساخته/ذخیره می‌شود */
    private val catalogNames = listOf(
        "در", "در باز", "در بسته", "کوبه در", "زنگ در",
        "باد", "باد شدید", "باد ملایم", "طوفان",
        "باران", "باران ملایم", "باران شدید", "رعد", "رعد و برق",
        "ماشین", "بوق ماشین", "موتور", "موتورسیکلت", "ترمز",
        "سوت", "سوت قطار", "سوت پلیس",
        "پا", "قدم", "دویدن", "پله",
        "خنده", "گریه", "نفس", "سرفه",
        "پرنده", "سگ", "گربه", "اسب",
        "آتش", "آب", "رودخانه", "دریا", "موج",
        "تلفن", "پیامک", "اعلان",
        "ساعت", "تیک تاک", "زنگ ساعت",
        "شلیک", "انفجار", "موشک",
        "کلید", "قفل", "صندلی",
        "کاغذ", "کتاب", "نوشتن",
        "آشپزخانه", "ظروف", "شیر آب",
        "جنگل", "حشره", "جیرجیرک",
        "قطار", "هواپیما", "هلیکوپتر",
        "کف زدن", "تشویق", "سوت تماشاگر",
        "پچ پچ", "همهمه",
        "پیانو", "گیتار", "ویولن", "درام",
        "هوه", "سوئیپ", "کلیک", "پاپ",
        "زنگ کلیسا", "ناقوس", "آژیر"
    )

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

        rebuildList("")
        btnSearch.setOnClickListener { rebuildList(etSearch.text.toString().trim()) }
        btnAddLocal.setOnClickListener { pickEffectLauncher.launch(arrayOf("audio/*")) }
    }

    private fun rebuildList(query: String) {
        shownEffects.clear()
        val q = query.lowercase()

        // downloaded / local first
        for (e in loadLocalEffects()) {
            if (q.isEmpty() || e.name.lowercase().contains(q)) shownEffects.add(e)
        }

        // catalog names (not downloaded yet)
        for (name in catalogNames) {
            if (q.isNotEmpty() && !name.lowercase().contains(q)) continue
            if (isDownloaded(name)) continue
            shownEffects.add(EffectItem(name, "catalog", ""))
        }

        for (e in onlineCatalog) {
            if (q.isEmpty() || e.name.lowercase().contains(q)) shownEffects.add(e)
        }

        refreshList()
        if (shownEffects.isEmpty()) {
            Toast.makeText(this, "افکتی پیدا نشد", Toast.LENGTH_SHORT).show()
        }
    }

    private fun isDownloaded(name: String): Boolean {
        val dir = File(filesDir, "effects")
        if (!dir.exists()) return false
        val id = effectId(name)
        return File(dir, "$id.wav").exists() || File(dir, "$id.mp3").exists()
    }

    private fun effectId(name: String): String {
        return "fx_" + name.hashCode().toString().replace('-', 'n')
    }

    private fun loadLocalEffects(): List<EffectItem> {
        val dir = File(filesDir, "effects")
        if (!dir.exists()) return emptyList()
        return dir.listFiles()?.filter {
            it.isFile && it.extension.lowercase() in listOf("wav", "mp3", "m4a", "audio") && it.length() > 100L
        }?.map { f ->
            val base = f.nameWithoutExtension
            val labelFile = File(dir, "$base.txt")
            val label = if (labelFile.exists()) labelFile.readText().trim() else base
            EffectItem(label.ifBlank { f.name }, "local", f.absolutePath)
        } ?: emptyList()
    }

    private fun refreshList() {
        val nOverlay = loadOverlayCount()
        tvHint.text = "نمایش: ${shownEffects.size} | زمان‌بندی‌شده برای میکس: $nOverlay\nاول دانلود کنید، بعد با زمان روی صدای اصلی بگذارید"
        listView.adapter = object : BaseAdapter() {
            override fun getCount() = shownEffects.size
            override fun getItem(p: Int) = shownEffects[p]
            override fun getItemId(p: Int) = p.toLong()
            override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
                val view = convertView ?: LayoutInflater.from(this@EffectsActivity)
                    .inflate(R.layout.item_effect, parent, false)
                val item = shownEffects[position]
                view.findViewById<TextView>(R.id.tvEffectName).text = item.name
                view.findViewById<TextView>(R.id.tvEffectSource).text = when (item.source) {
                    "local" -> "دانلود شده — آماده استفاده"
                    "catalog" -> "نیاز به دانلود"
                    else -> "آنلاین — نیاز به اینترنت"
                }
                val btnPlay = view.findViewById<Button>(R.id.btnPlayEffect)
                val btnSave = view.findViewById<Button>(R.id.btnSaveEffect)
                val btnUse = view.findViewById<Button>(R.id.btnUseEffect)

                btnSave.text = if (item.source == "local") "دانلود شده" else "دانلود"
                btnUse.text = "افزودن با زمان"

                btnPlay.setOnClickListener {
                    when (item.source) {
                        "local" -> playLocal(item.pathOrUrl)
                        "catalog" -> Toast.makeText(this@EffectsActivity, "اول دانلود کنید", Toast.LENGTH_SHORT).show()
                        else -> previewOnline(item)
                    }
                }
                btnSave.setOnClickListener {
                    if (item.source == "local") {
                        Toast.makeText(this@EffectsActivity, "قبلاً دانلود شده", Toast.LENGTH_SHORT).show()
                    } else if (item.source == "catalog") {
                        downloadCatalogEffect(item.name)
                    } else {
                        downloadOnline(item)
                    }
                }
                btnUse.setOnClickListener {
                    when (item.source) {
                        "local" -> showTimeDialog(item.pathOrUrl, item.name)
                        "catalog" -> Toast.makeText(this@EffectsActivity, "اول دکمه دانلود را بزنید", Toast.LENGTH_SHORT).show()
                        else -> {
                            Toast.makeText(this@EffectsActivity, "ابتدا دانلود کنید", Toast.LENGTH_SHORT).show()
                            downloadOnline(item)
                        }
                    }
                }
                return view
            }
        }
    }

    /** دانلود کاتالوگ = ساخت فایل کوتاه داخل برنامه (بدون نیاز به اینترنت) */
    private fun downloadCatalogEffect(name: String) {
        Toast.makeText(this, "در حال آماده‌سازی افکت...", Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            val path = withContext(Dispatchers.IO) {
                try {
                    val dir = File(filesDir, "effects")
                    if (!dir.exists()) dir.mkdirs()
                    val id = effectId(name)
                    val f = File(dir, "$id.wav")
                    val freq = 200.0 + (kotlin.math.abs(name.hashCode()) % 1500)
                    if (name.contains("باد") || name.contains("باران") || name.contains("همهمه") || name.contains("طوفان")) {
                        writeNoiseWav(f, 0.7)
                    } else {
                        writeToneWav(f, freq, 0.45)
                    }
                    File(dir, "$id.txt").writeText(name)
                    if (f.exists() && f.length() > 100L) f.absolutePath else null
                } catch (_: Exception) {
                    null
                }
            }
            if (path != null) {
                Toast.makeText(this@EffectsActivity, "دانلود و ذخیره شد", Toast.LENGTH_SHORT).show()
                rebuildList(etSearch.text.toString().trim())
            } else {
                Toast.makeText(this@EffectsActivity, "خطا در آماده‌سازی افکت", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun downloadOnline(item: EffectItem) {
        Toast.makeText(this, "در حال دانلود از اینترنت...", Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) {
                try {
                    val dir = File(filesDir, "effects")
                    dir.mkdirs()
                    val out = File(dir, "dl_${System.currentTimeMillis()}.mp3")
                    val f = downloadToFile(item.pathOrUrl, out)
                    if (f != null) {
                        File(dir, f.nameWithoutExtension + ".txt").writeText(item.name)
                        true
                    } else false
                } catch (_: Exception) {
                    false
                }
            }
            if (ok) {
                Toast.makeText(this@EffectsActivity, "ذخیره شد", Toast.LENGTH_SHORT).show()
                rebuildList(etSearch.text.toString().trim())
            } else {
                Toast.makeText(this@EffectsActivity, "دانلود ناموفق — اینترنت را چک کنید", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun previewOnline(item: EffectItem) {
        Toast.makeText(this, "آماده‌سازی پخش...", Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            val tmp = withContext(Dispatchers.IO) {
                downloadToFile(item.pathOrUrl, File(cacheDir, "preview_${System.currentTimeMillis()}.mp3"))
            }
            if (tmp != null) playLocal(tmp.absolutePath)
            else Toast.makeText(this@EffectsActivity, "پخش ممکن نیست", Toast.LENGTH_LONG).show()
        }
    }

    private fun showTimeDialog(path: String, name: String) {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 32, 48, 16)
        }
        layout.addView(TextView(this).apply {
            text = "زمان پخش افکت «$name» روی فایل اصلی\nساعت، دقیقه، ثانیه را وارد کنید:"
        })
        val etH = EditText(this).apply { hint = "ساعت (۰)"; setText("0"); inputType = android.text.InputType.TYPE_CLASS_NUMBER }
        val etM = EditText(this).apply { hint = "دقیقه (۰)"; setText("0"); inputType = android.text.InputType.TYPE_CLASS_NUMBER }
        val etS = EditText(this).apply { hint = "ثانیه (۰)"; setText("5"); inputType = android.text.InputType.TYPE_CLASS_NUMBER }
        layout.addView(etH)
        layout.addView(etM)
        layout.addView(etS)
        layout.addView(TextView(this).apply {
            text = "می‌توانید چند بار همین افکت را با زمان‌های مختلف اضافه کنید."
        })

        AlertDialog.Builder(this)
            .setTitle("زمان افکت")
            .setView(layout)
            .setPositiveButton("تأیید") { _, _ ->
                val h = etH.text.toString().toIntOrNull() ?: 0
                val m = etM.text.toString().toIntOrNull() ?: 0
                val s = etS.text.toString().toIntOrNull() ?: 0
                val ms = ((h * 3600L) + (m * 60L) + s) * 1000L
                appendOverlay(path, ms)
                Toast.makeText(this, "افکت در زمان ${h}:${m}:${s} اضافه شد", Toast.LENGTH_SHORT).show()
                refreshList()
            }
            .setNeutralButton("افزودن زمان دیگر") { _, _ ->
                val h = etH.text.toString().toIntOrNull() ?: 0
                val m = etM.text.toString().toIntOrNull() ?: 0
                val s = etS.text.toString().toIntOrNull() ?: 0
                val ms = ((h * 3600L) + (m * 60L) + s) * 1000L
                appendOverlay(path, ms)
                Toast.makeText(this, "اضافه شد — زمان بعدی را وارد کنید", Toast.LENGTH_SHORT).show()
                showTimeDialog(path, name)
            }
            .setNegativeButton("انصراف", null)
            .show()
    }

    private fun appendOverlay(path: String, ms: Long) {
        val cur = prefs.getString(KEY_OVERLAYS, "") ?: ""
        val parts = ArrayList<String>()
        if (cur.isNotBlank()) parts.addAll(cur.split(';').filter { it.isNotBlank() })
        parts.add("$path|$ms")
        prefs.edit().putString(KEY_OVERLAYS, parts.joinToString(";")).apply()
    }

    private fun loadOverlayCount(): Int {
        val cur = prefs.getString(KEY_OVERLAYS, "") ?: ""
        if (cur.isBlank()) return 0
        return cur.split(';').count { it.isNotBlank() }
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

    private fun writeToneWav(file: File, freqHz: Double, durationSec: Double) {
        val sampleRate = 22050
        val n = (sampleRate * durationSec).toInt()
        val data = ByteArray(n * 2)
        for (i in 0 until n) {
            val t = i.toDouble() / sampleRate
            val env = when {
                t < 0.05 -> t / 0.05
                t > durationSec - 0.08 -> ((durationSec - t) / 0.08).coerceAtLeast(0.0)
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
            dir.mkdirs()
            val out = File(dir, "local_${System.currentTimeMillis()}.audio")
            contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(out).use { output -> input.copyTo(output) }
            }
            if (out.exists() && out.length() > 0L) {
                Toast.makeText(this, "افکت از گوشی اضافه شد", Toast.LENGTH_SHORT).show()
                rebuildList(etSearch.text.toString().trim())
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

    companion object {
        const val KEY_OVERLAYS = "overlay_effects"
    }
}
