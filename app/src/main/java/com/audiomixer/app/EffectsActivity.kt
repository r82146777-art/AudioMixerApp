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
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

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

    // لیست نمونه‌های رایگان برای نمایش فقط فایل (نه سایت)
    private val onlineCatalog = listOf(
        EffectItem("باد Wind", "online", "https://cdn.pixabay.com/download/audio/2022/03/15/audio_c9a4a1d834.mp3"),
        EffectItem("باران Rain", "online", "https://cdn.pixabay.com/download/audio/2021/08/09/audio_dc39bde808.mp3"),
        EffectItem("رعد Thunder", "online", "https://cdn.pixabay.com/download/audio/2021/08/04/audio_12b0c7443c.mp3"),
        EffectItem("دریا Ocean", "online", "https://cdn.pixabay.com/download/audio/2022/03/24/audio_c8c8a73467.mp3"),
        EffectItem("جنگل Forest", "online", "https://cdn.pixabay.com/download/audio/2022/03/09/audio_c8c7a1d1e3.mp3"),
        EffectItem("آتش Fire", "online", "https://cdn.pixabay.com/download/audio/2022/03/10/audio_4a514cdeaa.mp3"),
        EffectItem("هوه Whoosh", "online", "https://cdn.pixabay.com/download/audio/2021/08/04/audio_bb630cc098.mp3"),
        EffectItem("پاپ Click", "online", "https://cdn.pixabay.com/download/audio/2022/03/24/audio_b7e3f1a2c9.mp3")
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

        allEffects.clear()
        allEffects.addAll(loadLocalEffects())
        allEffects.addAll(onlineCatalog)
        shownEffects.clear()
        shownEffects.addAll(allEffects)
        refreshList()

        btnSearch.setOnClickListener { filterEffects(etSearch.text.toString().trim()) }
        btnAddLocal.setOnClickListener { pickEffectLauncher.launch(arrayOf("audio/*")) }
    }

    private fun loadLocalEffects(): List<EffectItem> {
        val dir = File(filesDir, "effects")
        if (!dir.exists()) dir.mkdirs()
        return dir.listFiles()?.map { EffectItem(it.nameWithoutExtension, "local", it.absolutePath) } ?: emptyList()
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
        tvHint.text = "تعداد: ${shownEffects.size} — فقط فایل افکت (نه سایت)"
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
                    if (item.source == "local") "ذخیره در برنامه" else "نمونه آنلاین"
                view.findViewById<Button>(R.id.btnPlayEffect).setOnClickListener { playEffect(item) }
                view.findViewById<Button>(R.id.btnSaveEffect).setOnClickListener {
                    if (item.source == "local") Toast.makeText(this@EffectsActivity, "قبلاً ذخیره شده", Toast.LENGTH_SHORT).show()
                    else downloadAndSave(item)
                }
                view.findViewById<Button>(R.id.btnUseEffect).setOnClickListener {
                    Toast.makeText(this@EffectsActivity, "می‌توانید این فایل را به‌عنوان پس‌زمینه در صفحه اصلی انتخاب کنید", Toast.LENGTH_LONG).show()
                }
                return view
            }
        }
    }

    private fun playEffect(item: EffectItem) {
        try {
            player?.release()
            player = MediaPlayer().apply {
                setDataSource(item.pathOrUrl)
                prepareAsync()
                setOnPreparedListener { it.start() }
                setOnErrorListener { _, _, _ ->
                    Toast.makeText(this@EffectsActivity, "پخش ممکن نیست", Toast.LENGTH_SHORT).show()
                    true
                }
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
                    val out = File(dir, "${item.name.replace(" ", "_")}_${System.currentTimeMillis()}.mp3")
                    val conn = URL(item.pathOrUrl).openConnection() as HttpURLConnection
                    conn.connectTimeout = 15000
                    conn.readTimeout = 30000
                    conn.inputStream.use { input -> FileOutputStream(out).use { output -> input.copyTo(output) } }
                    conn.disconnect()
                    out.exists() && out.length() > 100
                } catch (_: Exception) { false }
            }
            if (ok) {
                Toast.makeText(this@EffectsActivity, "ذخیره شد", Toast.LENGTH_SHORT).show()
                allEffects.clear()
                allEffects.addAll(loadLocalEffects())
                allEffects.addAll(onlineCatalog)
                filterEffects(etSearch.text.toString().trim())
            } else {
                Toast.makeText(this@EffectsActivity, "دانلود ناموفق", Toast.LENGTH_LONG).show()
            }
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
            Toast.makeText(this, "افکت اضافه شد", Toast.LENGTH_SHORT).show()
            allEffects.clear()
            allEffects.addAll(loadLocalEffects())
            allEffects.addAll(onlineCatalog)
            filterEffects(etSearch.text.toString().trim())
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
