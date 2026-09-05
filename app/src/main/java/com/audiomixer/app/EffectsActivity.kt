package com.audiomixer.app

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import java.io.File
import java.io.FileOutputStream

/**
 * صفحه جستجوی افکت داخل برنامه
 * کادر جستجو + نمایش نتایج در WebView (Freesound)
 * امکان افزودن فایل افکت از حافظه گوشی
 */
class EffectsActivity : AppCompatActivity() {

    private lateinit var etSearch: EditText
    private lateinit var btnSearch: Button
    private lateinit var btnAddLocal: Button
    private lateinit var webView: WebView
    private lateinit var progress: ProgressBar

    private val pickEffectLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let { saveLocalEffect(it) }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_effects)

        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "جستجوی افکت‌ها"

        etSearch = findViewById(R.id.etSearch)
        btnSearch = findViewById(R.id.btnSearch)
        btnAddLocal = findViewById(R.id.btnAddLocal)
        webView = findViewById(R.id.webView)
        progress = findViewById(R.id.progressEffects)

        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                progress.visibility = if (newProgress < 100) View.VISIBLE else View.GONE
                progress.progress = newProgress
            }
        }
        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val url = request?.url?.toString() ?: return false
                // Keep navigation inside WebView for freesound
                if (url.contains("freesound.org")) {
                    view?.loadUrl(url)
                    return true
                }
                // External links open in browser
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                } catch (_: Exception) {}
                return true
            }
        }

        btnSearch.setOnClickListener {
            val q = etSearch.text.toString().trim()
            if (q.isEmpty()) {
                Toast.makeText(this, "نام افکت را وارد کنید (مثلاً باد، باران، اکو)", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val url = "https://freesound.org/search/?q=" + Uri.encode(q)
            webView.loadUrl(url)
        }

        btnAddLocal.setOnClickListener {
            pickEffectLauncher.launch(arrayOf("audio/*"))
        }

        // صفحه اولیه راهنما
        webView.loadDataWithBaseURL(
            null,
            """
            <html dir="rtl"><body style="font-family:sans-serif;padding:16px;background:#f5f5f5;">
            <h2>جستجوی افکت صوتی</h2>
            <p>نام افکت را در کادر بالا بنویسید (مثلاً: باد، باران، رعد، پاپ، اکو) و دکمه جستجو را بزنید.</p>
            <p>نتایج از سایت Freesound داخل همین صفحه نمایش داده می‌شود.</p>
            <p>برای افزودن افکت از فایل‌های خود گوشی، دکمه «افزودن از فایل‌های گوشی» را بزنید.</p>
            <p style="color:#666;font-size:13px;">این بخش برای جستجو به اینترنت نیاز دارد. بقیه برنامه آفلاین کار می‌کند.</p>
            </body></html>
            """.trimIndent(),
            "text/html",
            "UTF-8",
            null
        )
    }

    private fun saveLocalEffect(uri: Uri) {
        try {
            val dir = File(filesDir, "effects")
            if (!dir.exists()) dir.mkdirs()
            val name = "effect_${System.currentTimeMillis()}.audio"
            val out = File(dir, name)
            contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(out).use { output -> input.copyTo(output) }
            }
            Toast.makeText(this, "افکت در پوشه افکت‌های برنامه ذخیره شد", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(this, "خطا در ذخیره افکت: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (webView.canGoBack()) webView.goBack() else super.onBackPressed()
    }
}
