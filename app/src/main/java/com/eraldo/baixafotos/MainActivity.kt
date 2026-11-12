package com.eraldo.baixafotos

import android.Manifest
import android.app.Activity
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

class MainActivity : Activity() {

    private lateinit var urlInput: EditText
    private lateinit var downloadButton: Button
    private lateinit var progress: ProgressBar
    private lateinit var status: TextView
    private lateinit var preview: ImageView

    private val executor = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    companion object {
        private const val ALBUM = "BaixaFotos"
        private const val REQ_STORAGE = 42
        private val URL_REGEX = Regex("""https?://[^\s"'<>]+""")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        urlInput = findViewById(R.id.urlInput)
        downloadButton = findViewById(R.id.downloadButton)
        progress = findViewById(R.id.progress)
        status = findViewById(R.id.status)
        preview = findViewById(R.id.preview)

        findViewById<Button>(R.id.pasteButton).setOnClickListener { pasteFromClipboard() }
        findViewById<Button>(R.id.clearButton).setOnClickListener {
            urlInput.text.clear()
            status.text = ""
            preview.setImageDrawable(null)
            preview.visibility = View.GONE
        }
        downloadButton.setOnClickListener { startDownloads() }

        handleShareIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShareIntent(intent)
    }

    private fun handleShareIntent(intent: Intent?) {
        if (intent?.action == Intent.ACTION_SEND) {
            val shared = intent.getStringExtra(Intent.EXTRA_TEXT) ?: return
            urlInput.setText(URL_REGEX.findAll(shared).joinToString("\n") { it.value })
        }
    }

    private fun pasteFromClipboard() {
        val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        val text = cm.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString().orEmpty()
        val links = URL_REGEX.findAll(text).map { it.value }.toList()
        if (links.isEmpty()) {
            toast(getString(R.string.no_link_clipboard))
            return
        }
        val current = urlInput.text.toString().trim()
        urlInput.setText(if (current.isEmpty()) links.joinToString("\n") else current + "\n" + links.joinToString("\n"))
    }

    private fun startDownloads() {
        val urls = URL_REGEX.findAll(urlInput.text).map { it.value }.distinct().toList()
        if (urls.isEmpty()) {
            toast(getString(R.string.no_link))
            return
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
            checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), REQ_STORAGE)
            return
        }

        setBusy(true)
        status.text = getString(R.string.downloading, 0, urls.size)

        executor.execute {
            var ok = 0
            val errors = mutableListOf<String>()
            urls.forEachIndexed { i, url ->
                try {
                    val bytes = download(url)
                    saveToGallery(bytes.first, bytes.second)
                    ok++
                    val bmp = BitmapFactory.decodeByteArray(bytes.first, 0, bytes.first.size)
                    main.post {
                        if (bmp != null) {
                            preview.setImageBitmap(bmp)
                            preview.visibility = View.VISIBLE
                        }
                        status.text = getString(R.string.downloading, i + 1, urls.size)
                    }
                } catch (e: Exception) {
                    errors += "• ${shorten(url)}: ${e.message ?: e.javaClass.simpleName}"
                }
            }
            main.post {
                setBusy(false)
                val summary = StringBuilder(getString(R.string.done, ok, urls.size, ALBUM))
                if (errors.isNotEmpty()) summary.append("\n\n").append(getString(R.string.errors)).append("\n").append(errors.joinToString("\n"))
                status.text = summary
                if (ok > 0) toast(getString(R.string.saved_toast, ok))
            }
        }
    }

    /** Baixa a URL e retorna (bytes, mimeType). Lança exceção se não for uma imagem. */
    private fun download(url: String): Pair<ByteArray, String> {
        var current = url
        repeat(5) { // segue até 5 redirecionamentos (inclusive http -> https)
            val conn = (URL(current).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 30_000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "Mozilla/5.0 (Android) BaixaFotos/1.0")
                setRequestProperty("Accept", "image/*,*/*;q=0.8")
            }
            try {
                val code = conn.responseCode
                if (code in 300..399) {
                    current = URL(URL(current), conn.getHeaderField("Location")).toString()
                    return@repeat
                }
                if (code !in 200..299) throw Exception("HTTP $code")
                val bytes = conn.inputStream.use { it.readBytes() }
                val mime = detectMime(bytes, conn.contentType)
                    ?: throw Exception(getString(R.string.not_image))
                return bytes to mime
            } finally {
                conn.disconnect()
            }
        }
        throw Exception(getString(R.string.too_many_redirects))
    }

    /** Descobre o tipo da imagem pelos primeiros bytes (mais confiável que o cabeçalho). */
    private fun detectMime(b: ByteArray, header: String?): String? {
        fun at(i: Int) = if (i < b.size) b[i].toInt() and 0xFF else -1
        return when {
            at(0) == 0xFF && at(1) == 0xD8 -> "image/jpeg"
            at(0) == 0x89 && at(1) == 0x50 && at(2) == 0x4E && at(3) == 0x47 -> "image/png"
            at(0) == 0x47 && at(1) == 0x49 && at(2) == 0x46 -> "image/gif"
            at(0) == 0x52 && at(1) == 0x49 && at(2) == 0x46 && at(3) == 0x46 &&
                at(8) == 0x57 && at(9) == 0x45 && at(10) == 0x42 && at(11) == 0x50 -> "image/webp"
            at(0) == 0x42 && at(1) == 0x4D -> "image/bmp"
            b.size > 12 && String(b, 4, 8, Charsets.US_ASCII).let { it.startsWith("ftypheic") || it.startsWith("ftypheix") || it.startsWith("ftypmif1") } -> "image/heic"
            b.size > 12 && String(b, 4, 8, Charsets.US_ASCII).startsWith("ftypavif") -> "image/avif"
            header?.substringBefore(';')?.trim()?.startsWith("image/") == true -> header!!.substringBefore(';').trim()
            else -> null
        }
    }

    private fun extensionFor(mime: String) = when (mime) {
        "image/jpeg" -> "jpg"
        "image/png" -> "png"
        "image/gif" -> "gif"
        "image/webp" -> "webp"
        "image/bmp" -> "bmp"
        "image/heic" -> "heic"
        "image/avif" -> "avif"
        "image/svg+xml" -> "svg"
        else -> "img"
    }

    private fun saveToGallery(bytes: ByteArray, mime: String) {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
        val name = "foto_$stamp.${extensionFor(mime)}"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name)
                put(MediaStore.Images.Media.MIME_TYPE, mime)
                put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/$ALBUM")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val resolver = contentResolver
            val uri: Uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: throw Exception(getString(R.string.save_failed))
            try {
                resolver.openOutputStream(uri)?.use { it.write(bytes) } ?: throw Exception(getString(R.string.save_failed))
                values.clear()
                values.put(MediaStore.Images.Media.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            } catch (e: Exception) {
                resolver.delete(uri, null, null)
                throw e
            }
        } else {
            @Suppress("DEPRECATION")
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), ALBUM)
            if (!dir.exists() && !dir.mkdirs()) throw Exception(getString(R.string.save_failed))
            val file = File(dir, name)
            FileOutputStream(file).use { it.write(bytes) }
            MediaScannerConnection.scanFile(this, arrayOf(file.absolutePath), arrayOf(mime), null)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_STORAGE) {
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) startDownloads()
            else toast(getString(R.string.permission_denied))
        }
    }

    private fun setBusy(busy: Boolean) {
        downloadButton.isEnabled = !busy
        progress.visibility = if (busy) View.VISIBLE else View.GONE
    }

    private fun shorten(url: String) = if (url.length > 50) url.take(47) + "..." else url

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    override fun onDestroy() {
        executor.shutdownNow()
        super.onDestroy()
    }
}
