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
import android.widget.NumberPicker
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

    private val prefs by lazy { getSharedPreferences("audiomixer_prefs", Context.MODE_PRIVATE) }

    private val onlineCatalog = listOf(
        EffectItem("Sample music 1", "online", "https://www.soundhelix.com/examples/mp3/SoundHelix-Song-1.mp3"),
        EffectItem("Sample music 2", "online", "https://www.soundhelix.com/examples/mp3/SoundHelix-Song-2.mp3"),
        EffectItem("Test MP3", "online", "https://archive.org/download/testmp3testfile/mpthreetest.mp3")
    )

    /** نام‌های قابل جستجو — فایل کوتاه آفلاین ساخته می‌شود */
    private val effectNames = listOf(
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
        "پچ پچ", "همهمه", "سکوت شکسته",
        "پیانو", "گیتار", "ویولن", "درام",
        "هوه", "سوئیپ", "کلیک", "پاپ",
        "زنگ کلیسا", "ناقوس", "آژیر"
    )

    private val pickEffectLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let { saveLocalEffect(it) } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_effects)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "افکت‌های صوتی (لایه زمانی)"

        etSearch = findViewById(R.id.etSearch)
        btnSearch = findViewById(R.id.btnSearch)
        btnAddLocal = findViewById(R.id.btnAddLocal)
        listView = findViewById(R.id.listEffects)
        tvHint = findViewById(R.id.tvHint)

        ensureBuiltinEffects()
        rebuildList("")

        btnSearch.setOnClickListener { rebuildList(etSearch.text.toString().trim()) }
        btnAddLocal.setOnClickListener { pickEffectLauncher.launch(arrayOf("audio/*")) }
    }

    private fun rebuildList(query: String) {
        allEffects.clear()
        allEffects.addAll(loadLocalEffects())
        // offline generated catalog entries (searchable)
        for (name in effectNames) {
            val path = ensureNamedEffect(name)
            if (path != null) allEffects.add(EffectItem(name, "local", path))
        }
        allEffects.addAll(onlineCatalog)
        // unique by name+path
        val seen = HashSet<String>()
        shownEffects.clear()
        val q = query.lowercase()
        for (e in allEffects) {
            val key = e.name + "|" + e.pathOrUrl
            if (!seen.add(key)) continue
            if (q.isEmpty() || e.name.lowercase().contains(q)) shownEffects.add(e)
        }
        refreshList()
        if (shownEffects.isEmpty()) {
            Toast.makeText(this, "افکتی پیدا نشد", Toast.LENGTH_SHORT).show()
        }
    }

    private fun ensureNamedEffect(name: String): String? {
        val dir = File(filesDir, "effects")
        if (!dir.exists()) dir.mkdirs()
        val id = "fx_" + name.hashCode().toString().replace('-', 'n')
        val f = File(dir, "$id.wav")
        if (!f.exists() || f.length() < 100L) {
            try {
                val freq = 200.0 + (kotlin.math.abs(name.hashCode()) % 1500)
                if (name.contains("باد") || name.contains("باران") || name.contains("نویز") || name.contains("همهمه")) {
                    writeNoiseWav(f, 0.7)
                } else {
                    writeToneWav(f, freq, 0.45)
                }
                File(dir, "$id.txt").writeText(name)
            } catch (_: Exception) {
                return null
            }
        }
        return if (f.exists()) f.absolutePath else null
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

    private fun loadLocalEffects(): List<EffectItem> {
        val dir = File(filesDir, "effects")
        if (!dir.exists()) dir.mkdirs()
        return dir.listFiles()?.filter {
            it.isFile && it.extension.lowercase() in listOf("wav", "mp3", "m4a", "audio") && it.length() > 100L
        }?.map { f ->
            val base = f.nameWithoutExtension
            val labelFile = File(dir, "$base.txt")
            val label = if (labelFile.exists()) labelFile.readText().trim()
            else base.removePrefix("builtin_").replace('_', ' ')
            EffectItem(label.ifBlank { f.name }, "local", f.absolutePath)
        } ?: emptyList()
    }

    private fun refreshList() {
        val overlays = loadOverlayCount()
        tvHint.text = "تعداد نمایش: ${shownEffects.size} | افکت‌های زمان‌بندی‌شده: $overlays"
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
                    if (item.source == "local") "لایه روی صدای اصلی (آفلاین)" else "آنلاین"
                view.findViewById<Button>(R.id.btnPlayEffect).setOnClickListener { playEffect(item) }
                view.findViewById<Button>(R.id.btnSaveEffect).setOnClickListener {
                    if (item.source == "local") {
                        Toast.makeText(this@EffectsActivity, "آماده استفاده است", Toast.LENGTH_SHORT).show()
                    } else downloadAndSave(item)
                }
                view.findViewById<Button>(R.id.btnUseEffect).text = "افزودن با زمان"
                view.findViewById<Button>(R.id.btnUseEffect).setOnClickListener {
                    ensureLocalThenAdd(item)
                }
                return view
            }
        }
    }

    private fun ensureLocalThenAdd(item: EffectItem) {
        if (item.source == "local") {
            showTimeDialog(item.pathOrUrl, item.name)
            return
        }
        Toast.makeText(this, "دانلود برای استفاده...", Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            val path = withContext(Dispatchers.IO) {
                val dir = File(filesDir, "effects")
                dir.mkdirs()
                val out = File(dir, "dl_${System.currentTimeMillis()}.mp3")
                if (downloadToFile(item.pathOrUrl, out) != null) out.absolutePath else null
            }
            if (path != null) showTimeDialog(path, item.name)
            else Toast.makeText(this@EffectsActivity, "دانلود ناموفق", Toast.LENGTH_LONG).show()
        }
    }

    private fun showTimeDialog(path: String, name: String) {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 24, 40, 24)
        }
        val info = TextView(this).apply {
            text = "زمان پخش افکت «$name» روی فایل اصلی:\nساعت : دقیقه : ثانیه"
        }
        layout.addView(info)

        fun np(max: Int, value: Int): NumberPicker = NumberPicker(this).apply {
            minValue = 0
            maxValue = max
            this.value = value
            wrapSelectorWheel = true
        }
        val hour = np(5, 0)
        val min = np(59, 0)
        val sec = np(59, 0)
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(hour)
        row.addView(min)
        row.addView(sec)
        layout.addView(row)

        val timesPreview = TextView(this).apply { text = "زمان‌های فعلی این افزودن: (هنوز خالی)" }
        layout.addView(timesPreview)
        val pending = ArrayList<Long>()

        fun refreshPreview() {
            timesPreview.text = if (pending.isEmpty()) "زمان‌های این افزودن: (خالی)"
            else "زمان‌ها: " + pending.joinToString { ms ->
                val s = ms / 1000
                String.format("%d:%02d:%02d", s / 3600, (s % 3600) / 60, s % 60)
            }
        }

        AlertDialog.Builder(this)
            .setTitle("زمان افکت")
            .setView(layout)
            .setPositiveButton("تأیید و افزودن") { _, _ ->
                if (pending.isEmpty()) {
                    val ms = ((hour.value * 3600L) + (min.value * 60L) + sec.value) * 1000L
                    pending.add(ms)
                }
                appendOverlays(path, pending)
                Toast.makeText(this, "افکت با ${pending.size} زمان اضافه شد", Toast.LENGTH_SHORT).show()
                refreshList()
            }
            .setNeutralButton("افزودن این زمان") { dialog, _ ->
                val ms = ((hour.value * 3600L) + (min.value * 60L) + sec.value) * 1000L
                pending.add(ms)
                refreshPreview()
                // keep dialog open roughly by showing again
                dialog.dismiss()
                showTimeDialogContinue(path, name, pending)
            }
            .setNegativeButton("انصراف", null)
            .show()
    }

    private fun showTimeDialogContinue(path: String, name: String, pending: ArrayList<Long>) {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 24, 40, 24)
        }
        layout.addView(TextView(this).apply {
            text = "افکت «$name» — زمان بعدی (ساعت:دقیقه:ثانیه)\nزمان‌های ثبت‌شده: " +
                    pending.joinToString { ms ->
                        val s = ms / 1000
                        String.format("%d:%02d:%02d", s / 3600, (s % 3600) / 60, s % 60)
                    }
        })
        fun np(max: Int, value: Int) = NumberPicker(this).apply {
            minValue = 0; maxValue = max; this.value = value
        }
        val hour = np(5, 0); val min = np(59, 0); val sec = np(59, 0)
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(hour); row.addView(min); row.addView(sec)
        layout.addView(row)
        AlertDialog.Builder(this)
            .setTitle("افزودن زمان جدید")
            .setView(layout)
            .setPositiveButton("تأیید نهایی") { _, _ ->
                appendOverlays(path, pending)
                Toast.makeText(this, "ذخیره شد (${pending.size} زمان)", Toast.LENGTH_SHORT).show()
                refreshList()
            }
            .setNeutralButton("+ زمان دیگر") { d, _ ->
                pending.add(((hour.value * 3600L) + (min.value * 60L) + sec.value) * 1000L)
                d.dismiss()
                showTimeDialogContinue(path, name, pending)
            }
            .setNegativeButton("انصراف", null)
            .show()
    }

    private fun appendOverlays(path: String, timesMs: List<Long>) {
        val cur = prefs.getString(KEY_OVERLAYS, "") ?: ""
        val parts = ArrayList<String>()
        if (cur.isNotBlank()) parts.addAll(cur.split(';').filter { it.isNotBlank() })
        for (ms in timesMs) {
            parts.add("$path|$ms")
        }
        prefs.edit().putString(KEY_OVERLAYS, parts.joinToString(";")).apply()
    }

    private fun loadOverlayCount(): Int {
        val cur = prefs.getString(KEY_OVERLAYS, "") ?: ""
        if (cur.isBlank()) return 0
        return cur.split(';').count { it.isNotBlank() }
    }

    private fun playEffect(item: EffectItem) {
        try {
            player?.release()
            player = null
            if (item.source == "local") playLocal(item.pathOrUrl)
            else {
                Toast.makeText(this, "آماده‌سازی پخش...", Toast.LENGTH_SHORT).show()
                lifecycleScope.launch {
                    val tmp = withContext(Dispatchers.IO) {
                        downloadToFile(item.pathOrUrl, File(cacheDir, "preview_${System.currentTimeMillis()}.mp3"))
                    }
                    if (tmp != null) playLocal(tmp.absolutePath)
                    else Toast.makeText(this@EffectsActivity, "پخش ممکن نیست", Toast.LENGTH_LONG).show()
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
                    dir.mkdirs()
                    val out = File(dir, "dl_${System.currentTimeMillis()}.mp3")
                    downloadToFile(item.pathOrUrl, out) != null
                } catch (_: Exception) {
                    false
                }
            }
            if (ok) {
                Toast.makeText(this@EffectsActivity, "ذخیره شد", Toast.LENGTH_SHORT).show()
                rebuildList(etSearch.text.toString().trim())
            } else {
                Toast.makeText(this@EffectsActivity, "دانلود ناموفق", Toast.LENGTH_LONG).show()
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
            dir.mkdirs()
            val out = File(dir, "local_${System.currentTimeMillis()}.audio")
            contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(out).use { output -> input.copyTo(output) }
            }
            if (out.exists() && out.length() > 0L) {
                Toast.makeText(this, "افکت اضافه شد", Toast.LENGTH_SHORT).show()
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
