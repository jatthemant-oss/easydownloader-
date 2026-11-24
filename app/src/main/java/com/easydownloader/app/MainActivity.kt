package com.easydownloader.app

import android.Manifest
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.easydownloader.app.databinding.ActivityMainBinding
import com.easydownloader.app.databinding.ItemDownloadBinding
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import java.io.File
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding
    private val io = Executors.newSingleThreadExecutor()
    private val ui = Handler(Looper.getMainLooper())

    private var audioMode = true
    private var kbps = 192
    private var height = 720
    private var busy = false

    private val askWrite =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)

        if (Build.VERSION.SDK_INT >= 23 && Build.VERSION.SDK_INT <= 29 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
            != PackageManager.PERMISSION_GRANTED
        ) {
            askWrite.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }

        b.btnPaste.setOnClickListener { paste() }
        b.modeAudio.setOnClickListener { setMode(true) }
        b.modeVideo.setOnClickListener { setMode(false) }
        b.chip1.setOnClickListener { pick(0) }
        b.chip2.setOnClickListener { pick(1) }
        b.chip3.setOnClickListener { pick(2) }
        b.btnDownload.setOnClickListener { start() }

        b.linkInput.setOnFocusChangeListener { _, _ -> detect() }
        setMode(true)
        pick(1)
        refreshList()
    }

    override fun onResume() {
        super.onResume()
        refreshList()
    }

    private fun paste() {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val txt = cm.primaryClip?.getItemAt(0)?.text?.toString()?.trim().orEmpty()
        if (txt.isEmpty()) {
            toast("Clipboard khaali hai")
            return
        }
        b.linkInput.setText(txt)
        detect()
    }

    private fun detect() {
        val u = b.linkInput.text.toString().trim()
        when {
            u.isEmpty() -> {
                b.tagPlatform.text = "WAITING FOR LINK"
                b.hint.text = "YouTube, Instagram ya koi bhi link"
            }
            u.contains("youtu") -> {
                b.tagPlatform.text = "YOUTUBE"
                b.hint.text = if (audioMode) "MP3 me save hoga" else "MP4 me save hoga"
            }
            u.contains("instagram") -> {
                b.tagPlatform.text = "INSTAGRAM"
                b.hint.text = "Public reel/post hi chalega"
            }
            else -> {
                b.tagPlatform.text = "OTHER"
                b.hint.text = "Try karte hain — support ho sakta hai"
            }
        }
    }

    private fun setMode(audio: Boolean) {
        audioMode = audio
        b.modeAudio.isSelected = audio
        b.modeVideo.isSelected = !audio
        if (audio) {
            b.chip1.text = "128 kbps"
            b.chip2.text = "192 kbps"
            b.chip3.text = "320 kbps"
            pick(if (kbps == 128) 0 else if (kbps == 192) 1 else 2)
        } else {
            b.chip1.text = "360p"
            b.chip2.text = "720p"
            b.chip3.text = "1080p"
            pick(if (height == 360) 0 else if (height == 720) 1 else 2)
        }
        detect()
    }

    private fun pick(i: Int) {
        b.chip1.isSelected = i == 0
        b.chip2.isSelected = i == 1
        b.chip3.isSelected = i == 2
        if (audioMode) {
            kbps = when (i) { 0 -> 128; 2 -> 320; else -> 192 }
        } else {
            height = when (i) { 0 -> 360; 2 -> 1080; else -> 720 }
        }
    }

    private fun outDir(): File {
        val pub = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "EasyDownloader"
        )
        if (!pub.exists()) pub.mkdirs()
        if (pub.canWrite()) return pub
        val priv = File(getExternalFilesDir(null), "EasyDownloader")
        if (!priv.exists()) priv.mkdirs()
        return priv
    }

    private fun start() {
        if (busy) return
        val url = b.linkInput.text.toString().trim()
        if (url.isEmpty()) {
            toast("Pehle link paste karo")
            return
        }
        if (!Engine.ready) {
            toast(Engine.error ?: "Engine taiyar ho raha hai, 2 second…")
            return
        }

        val dir = outDir()
        val req = YoutubeDLRequest(url)
        req.addOption("-o", File(dir, "%(title).80s.%(ext)s").absolutePath)
        req.addOption("--no-mtime")
        req.addOption("--no-playlist")
        if (audioMode) {
            req.addOption("-x")
            req.addOption("--audio-format", "mp3")
            req.addOption("--audio-quality", "${kbps}K")
        } else {
            req.addOption("-f", "bv*[height<=$height]+ba/b[height<=$height]/b")
            req.addOption("--merge-output-format", "mp4")
        }

        busy = true
        b.btnDownload.text = "Downloading…"
        b.btnDownload.setBackgroundResource(R.drawable.bg_btn_off)
        b.progressBox.visibility = View.VISIBLE
        b.progressBar.progress = 0
        b.status.text = "Shuru kar rahe hain…"

        val cb: (Float, Long, String) -> Unit = { p, _eta, line ->
            ui.post {
                if (p >= 0f) b.progressBar.progress = p.toInt().coerceIn(0, 100)
                val t = line.trim()
                if (t.isNotEmpty()) b.status.text = t.take(90)
            }
        }

        io.execute {
            var err: String? = null
            try {
                YoutubeDL.getInstance().execute(req, "easy-" + System.currentTimeMillis(), cb)
            } catch (e: Throwable) {
                err = e.message ?: "download failed"
            }
            ui.post { finish(err) }
        }
    }

    private fun finish(err: String?) {
        busy = false
        b.btnDownload.text = "Download"
        b.btnDownload.setBackgroundResource(R.drawable.bg_btn)
        if (err == null) {
            b.progressBar.progress = 100
            b.status.text = "Ho gaya — Downloads/EasyDownloader me save hai"
            toast("Download complete")
            ui.postDelayed({ b.progressBox.visibility = View.GONE }, 2500)
        } else {
            b.progressBar.progress = 0
            b.status.text = "Fail: " + err.take(120)
            toast("Download fail hua")
        }
        refreshList()
    }

    private fun refreshList() {
        val dir = outDir()
        val files = dir.listFiles()?.filter { it.isFile }?.sortedByDescending { it.lastModified() } ?: emptyList()
        b.listBox.removeAllViews()
        b.emptyText.visibility = if (files.isEmpty()) View.VISIBLE else View.GONE
        val inf = LayoutInflater.from(this)
        for (f in files.take(25)) {
            val row: ItemDownloadBinding = ItemDownloadBinding.inflate(inf, b.listBox, false)
            row.rowName.text = f.name
            row.rowMeta.text = size(f.length()) + " · " + f.extension.uppercase()
            row.rowOpen.setOnClickListener { open(f) }
            row.rowShare.setOnClickListener { share(f) }
            b.listBox.addView(row.root)
        }
    }

    private fun size(n: Long): String = when {
        n >= 1024L * 1024L -> String.format("%.1f MB", n / 1024.0 / 1024.0)
        n >= 1024L -> String.format("%.0f KB", n / 1024.0)
        else -> "$n B"
    }

    private fun uriOf(f: File): Uri =
        FileProvider.getUriForFile(this, packageName + ".fileprovider", f)

    private fun mimeOf(f: File): String = when (f.extension.lowercase()) {
        "mp3" -> "audio/mpeg"
        "m4a" -> "audio/mp4"
        "mp4" -> "video/mp4"
        "webm" -> "video/webm"
        else -> "*/*"
    }

    private fun open(f: File) {
        try {
            val i = Intent(Intent.ACTION_VIEW)
            i.setDataAndType(uriOf(f), mimeOf(f))
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            startActivity(i)
        } catch (e: Exception) {
            toast("Koi app nahi mila is file ke liye")
        }
    }

    private fun share(f: File) {
        try {
            val i = Intent(Intent.ACTION_SEND)
            i.type = mimeOf(f)
            i.putExtra(Intent.EXTRA_STREAM, uriOf(f))
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            startActivity(Intent.createChooser(i, "Share"))
        } catch (e: Exception) {
            toast("Share nahi ho paya")
        }
    }

    private fun toast(m: String) {
        Toast.makeText(this, m, Toast.LENGTH_SHORT).show()
    }
}
