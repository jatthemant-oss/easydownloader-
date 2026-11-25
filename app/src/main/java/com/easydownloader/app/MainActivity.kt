package com.easydownloader.app

import android.Manifest
import android.content.ClipboardManager
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.LayoutInflater
import android.view.View
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

private const val FOLDER = "EasyDownloader"

class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding
    private val io = Executors.newSingleThreadExecutor()
    private val ui = Handler(Looper.getMainLooper())

    private var audioMode = true
    private var kbps = 192
    private var height = 720
    private var busy = false
    private var clipLink = ""

    private val askWrite =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)

        if (Build.VERSION.SDK_INT <= 28 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
            != PackageManager.PERMISSION_GRANTED
        ) {
            askWrite.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }

        b.btnPaste.setOnClickListener { paste() }
        b.btnClear.setOnClickListener {
            b.linkInput.setText("")
            b.clipBox.visibility = View.GONE
            detect()
        }
        b.clipUse.setOnClickListener {
            if (clipLink.isNotEmpty()) b.linkInput.setText(clipLink)
            b.clipBox.visibility = View.GONE
            detect()
        }
        b.modeAudio.setOnClickListener { setMode(true) }
        b.modeVideo.setOnClickListener { setMode(false) }
        b.chip1.setOnClickListener { pick(0) }
        b.chip2.setOnClickListener { pick(1) }
        b.chip3.setOnClickListener { pick(2) }
        b.btnDownload.setOnClickListener { start() }

        b.btnRescan.setOnClickListener { rescanAll() }
        b.btnUpdate.setOnClickListener {
            b.engineInfo.text = "yt-dlp update ho raha hai…"
            io.execute {
                Engine.update(applicationContext, true)
                ui.post { b.engineInfo.text = Engine.note }
            }
        }
        ui.postDelayed({ b.engineInfo.text = Engine.note }, 1500)
        b.linkInput.setOnFocusChangeListener { _, _ -> detect() }
        setMode(true)
        pick(1)
        refreshList()
    }

    override fun onResume() {
        super.onResume()
        refreshList()
        checkClip()
    }

    private fun checkClip() {
        try {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val t = cm.primaryClip?.getItemAt(0)?.text?.toString()?.trim().orEmpty()
            val cur = b.linkInput.text.toString().trim()
            if (t.length > 12 && (t.startsWith("http://") || t.startsWith("https://")) && t != cur) {
                clipLink = t
                b.clipLbl.text = "COPIED LINK READY \u00B7 " + platformName(t).uppercase()
                b.clipBox.visibility = View.VISIBLE
            } else {
                b.clipBox.visibility = View.GONE
            }
        } catch (e: Throwable) {
            b.clipBox.visibility = View.GONE
        }
    }

    private fun platformName(u: String): String = when {
        u.contains("youtu") -> "YouTube"
        u.contains("instagram") -> "Instagram"
        u.contains("facebook") || u.contains("fb.watch") -> "Facebook"
        else -> "Link"
    }

    private fun paste() {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val txt = cm.primaryClip?.getItemAt(0)?.text?.toString()?.trim().orEmpty()
        if (txt.isEmpty()) {
            toast("Clipboard khaali hai")
            return
        }
        b.linkInput.setText(txt)
        b.clipBox.visibility = View.GONE
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
                b.hint.text = "Try karte hain \u2014 support ho sakta hai"
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

    /** yt-dlp yahan likhta hai (app ka apna folder — bina permission ke chalta hai). */
    private fun tempDir(): File {
        val d = File(getExternalFilesDir(null), "EasyDownloader")
        if (!d.exists()) d.mkdirs()
        return d
    }

    private fun isVideoFile(f: File): Boolean =
        f.extension.lowercase() in listOf("mp4", "webm", "mkv", "3gp", "m4v", "mov")

    private fun mimeOf(f: File): String = when (f.extension.lowercase()) {
        "mp3" -> "audio/mpeg"
        "m4a" -> "audio/mp4"
        "aac" -> "audio/aac"
        "opus", "ogg" -> "audio/ogg"
        "mp4" -> "video/mp4"
        "webm" -> "video/webm"
        "mkv" -> "video/x-matroska"
        else -> "application/octet-stream"
    }

    private fun start() {
        if (busy) return
        val url = b.linkInput.text.toString().trim()
        if (url.isEmpty()) {
            toast("Pehle link paste karo")
            return
        }
        if (!Engine.ready) {
            toast(Engine.error ?: "Engine taiyar ho raha hai, 2 second\u2026")
            return
        }

        val dir = tempDir()
        val req = YoutubeDLRequest(url)
        req.addOption("-o", File(dir, "%(title).80s.%(ext)s").absolutePath)
        req.addOption("--no-mtime")
        req.addOption("--no-playlist")
        if (audioMode) {
            req.addOption("-x")
            req.addOption("--audio-format", "mp3")
            req.addOption("--audio-quality", "${kbps}K")
            req.addOption("--embed-thumbnail")
            req.addOption("--convert-thumbnails", "jpg")
            req.addOption("--embed-metadata")
        } else {
            req.addOption("-f", "bv*[height<=$height]+ba/b[height<=$height]/b")
            req.addOption("--merge-output-format", "mp4")
        }

        val startedAt = System.currentTimeMillis()
        busy = true
        b.btnDownload.text = "Downloading\u2026"
        b.btnDownload.setBackgroundResource(R.drawable.bg_btn_off)
        b.progressBox.visibility = View.VISIBLE
        b.progressBar.progress = 0
        b.status.text = "Shuru kar rahe hain\u2026"

        val cb: (Float, Long, String) -> Unit = { p, _eta, line ->
            ui.post {
                if (p >= 0f) b.progressBar.progress = p.toInt().coerceIn(0, 100)
                val t = line.trim()
                if (t.isNotEmpty()) b.status.text = t.take(90)
            }
        }

        io.execute {
            // YouTube kabhi kabhi bot-check laga deta hai; alag player clients se try karte hain
            val plan = listOf(
                Triple<String?, Boolean, String>(null, true, "normal"),
                Triple<String?, Boolean, String>(null, false, "bina thumbnail"),
                Triple<String?, Boolean, String>("tv", false, "tv client"),
                Triple<String?, Boolean, String>("web_safari", false, "web_safari"),
                Triple<String?, Boolean, String>("android", false, "android client"),
                Triple<String?, Boolean, String>("ios", false, "ios client"),
                Triple<String?, Boolean, String>("mweb", false, "mweb client")
            )
            var lastErr: Throwable? = null
            for ((i, a) in plan.withIndex()) {
                val (client, embeds, label) = a
                if (i > 0) ui.post { b.status.text = "Retry $i: $label\u2026" }
                try {
                    val rq = buildRequest(url, dir, audioMode, embeds, client)
                    YoutubeDL.getInstance().execute(rq, "e$i-" + System.currentTimeMillis(), cb)
                    lastErr = null
                    break
                } catch (e: Throwable) {
                    lastErr = e
                }
            }

            val msg = lastErr?.message
            ui.post {
                if (msg == null) {
                    val (moved, saveErr) = export(startedAt)
                    finish(null, moved, saveErr)
                } else {
                    finish(friendly(msg), 0, null)
                }
            }
        }
    }

    /** Error ko aam aadmi ki bhasha me badalta hai. */
    private fun friendly(m: String): String = when {
        m.contains("Sign in to confirm", true) ->
            "YouTube ne is network par bot-check laga diya hai. VPN on karke try karo, ya thodi der baad. (Baaki sites chalti rahengi.)"
        m.contains("Video unavailable", true) || m.contains("Private video", true) ->
            "Ye video private ya unavailable hai."
        m.contains("age", true) && m.contains("restrict", true) ->
            "Ye video age-restricted hai, download nahi hoga."
        else -> m
    }

    private fun buildRequest(
        url: String,
        dir: File,
        audio: Boolean,
        embeds: Boolean,
        client: String? = null
    ): YoutubeDLRequest {
        val req = YoutubeDLRequest(url)
        if (client != null) req.addOption("--extractor-args", "youtube:player_client=$client")
        req.addOption("-o", File(dir, "%(title).80s.%(ext)s").absolutePath)
        req.addOption("--no-mtime")
        req.addOption("--no-playlist")
        if (audio) {
            req.addOption("-x")
            req.addOption("--audio-format", "mp3")
            req.addOption("--audio-quality", "${kbps}K")
            if (embeds) {
                req.addOption("--embed-thumbnail")
                req.addOption("--convert-thumbnails", "jpg")
                req.addOption("--embed-metadata")
            }
        } else {
            req.addOption("-f", "bv*[height<=$height]+ba/b[height<=$height]/b")
            req.addOption("--merge-output-format", "mp4")
        }
        return req
    }

    /**
     * Naye files ko phone ki gallery/music library (MediaStore) me daalta hai.
     * Pehle storage root me /EasyDownloader, phir Movies/Music/Download me fallback.
     */
    private fun export(since: Long): Pair<Int, String?> {
        val fresh = tempDir().listFiles()?.filter {
            it.isFile && it.lastModified() >= since - 5000
        } ?: emptyList()
        var n = 0
        var lastErr: String? = null
        for (f in fresh) {
            val e = exportOne(f)
            if (e == null) n++ else lastErr = e
        }
        return Pair(n, lastErr)
    }

    /** null = save ho gaya, warna error message. */
    private fun exportOne(f: File): String? {
        val video = isVideoFile(f)
        val mime = mimeOf(f)
        val now = System.currentTimeMillis()

        if (Build.VERSION.SDK_INT >= 29) {
            val collection =
                if (video) MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                else MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
            val places =
                if (video) listOf(FOLDER, "Movies/$FOLDER", "Download/$FOLDER", "DCIM/$FOLDER")
                else listOf(FOLDER, "Music/$FOLDER", "Download/$FOLDER")
            var err: String? = null
            for (rel in places) {
                var uri: Uri? = null
                try {
                    val values = ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, f.name)
                        put(MediaStore.MediaColumns.MIME_TYPE, mime)
                        put(MediaStore.MediaColumns.RELATIVE_PATH, rel)
                        put(MediaStore.MediaColumns.DATE_ADDED, now / 1000L)
                        put(MediaStore.MediaColumns.DATE_MODIFIED, now / 1000L)
                        put(MediaStore.MediaColumns.DATE_TAKEN, now)
                        put(MediaStore.MediaColumns.IS_PENDING, 1)
                    }
                    uri = contentResolver.insert(collection, values)
                    if (uri == null) {
                        err = "insert null ($rel)"
                        continue
                    }
                    contentResolver.openOutputStream(uri)?.use { out ->
                        f.inputStream().use { it.copyTo(out, 64 * 1024) }
                    } ?: run {
                        err = "stream null ($rel)"
                        contentResolver.delete(uri, null, null)
                        continue
                    }
                    val done = ContentValues()
                    done.put(MediaStore.MediaColumns.IS_PENDING, 0)
                    contentResolver.update(uri, done, null, null)

                    val shown = File(
                        Environment.getExternalStorageDirectory(),
                        rel + "/" + f.name
                    )
                    MediaScannerConnection.scanFile(
                        this, arrayOf(shown.absolutePath), arrayOf(mime), null
                    )
                    f.delete()
                    return null
                } catch (e: Throwable) {
                    err = (e.message ?: e.javaClass.simpleName) + " ($rel)"
                    try {
                        uri?.let { contentResolver.delete(it, null, null) }
                    } catch (ignored: Throwable) {
                    }
                }
            }
            return err ?: "save fail"
        }

        // Android 9 aur neeche: seedha file likh ke gallery ko batao
        return try {
            val dest = File(Environment.getExternalStorageDirectory(), FOLDER)
            if (!dest.exists()) dest.mkdirs()
            val out = File(dest, f.name)
            f.copyTo(out, overwrite = true)
            f.delete()
            MediaScannerConnection.scanFile(this, arrayOf(out.absolutePath), arrayOf(mime), null)
            null
        } catch (e: Throwable) {
            e.message ?: "save fail"
        }
    }

    private fun finish(err: String?, moved: Int, saveErr: String? = null) {
        busy = false
        b.btnDownload.text = "Download"
        b.btnDownload.setBackgroundResource(R.drawable.bg_btn)
        if (err == null) {
            b.progressBar.progress = 100
            b.status.text = when {
                moved > 0 -> "Ho gaya \u2014 gallery/music me save hai"
                saveErr != null -> "Save fail: " + saveErr.take(200)
                else -> "Ho gaya, par file save nahi ho payi"
            }
            toast(if (moved > 0) "Download complete" else "File save nahi hui")
            ui.postDelayed({ b.progressBox.visibility = View.GONE }, 2500)
        } else {
            b.progressBar.progress = 0
            b.status.text = "Fail: " + err.take(400)
            toast("Download fail hua")
        }
        refreshList()
    }

    /** Gallery ko dobara scan karwata hai (Google Photos folder na dikhe to). */
    private fun rescanAll() {
        try {
            var count = 0
            if (Build.VERSION.SDK_INT >= 29) {
                val collections = listOf(
                    MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                    MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
                )
                for (collection in collections) {
                    val sel = "(" + MediaStore.MediaColumns.RELATIVE_PATH + " LIKE ? OR " +
                        MediaStore.MediaColumns.RELATIVE_PATH + " LIKE ?)"
                    val args = arrayOf(FOLDER + "%", "%/" + FOLDER + "%")
                    contentResolver.query(
                        collection,
                        arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.MIME_TYPE),
                        sel, args, null
                    )?.use { c ->
                        val iName = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                        val iMime = c.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)
                        while (c.moveToNext()) {
                            val name = c.getString(iName) ?: continue
                            val p = File(File(Environment.getExternalStorageDirectory(), FOLDER), name)
                            MediaScannerConnection.scanFile(
                                this, arrayOf(p.absolutePath), arrayOf(c.getString(iMime) ?: "*/*"), null
                            )
                            count++
                        }
                    }
                }
            }
            toast(if (count > 0) "$count files rescan ho gaye" else "Kuch nahi mila")
            refreshList()
        } catch (e: Throwable) {
            toast("Rescan fail: " + (e.message ?: ""))
        }
    }

    // ---------------- downloads list ----------------

    private class Item(val name: String, val size: Long, val uri: Uri, val mime: String, val added: Long)

    private fun loadItems(): List<Item> {
        val out = mutableListOf<Item>()
        if (Build.VERSION.SDK_INT >= 29) {
            out += query(MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
            out += query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI)
        } else {
            val dirs = listOf(
                File(Environment.getExternalStorageDirectory(), FOLDER),
                File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM), FOLDER),
                File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC), FOLDER)
            )
            for (d in dirs) {
                d.listFiles()?.filter { it.isFile }?.forEach {
                    out.add(
                        Item(
                            it.name, it.length(),
                            FileProvider.getUriForFile(this, packageName + ".fileprovider", it),
                            mimeOf(it), it.lastModified()
                        )
                    )
                }
            }
        }
        return out.sortedByDescending { it.added }
    }

    private fun query(collection: Uri): List<Item> {
        val res = mutableListOf<Item>()
        try {
            val proj = arrayOf(
                MediaStore.MediaColumns._ID,
                MediaStore.MediaColumns.DISPLAY_NAME,
                MediaStore.MediaColumns.SIZE,
                MediaStore.MediaColumns.MIME_TYPE,
                MediaStore.MediaColumns.DATE_ADDED,
                MediaStore.MediaColumns.RELATIVE_PATH
            )
            val sel = "(" + MediaStore.MediaColumns.RELATIVE_PATH + " LIKE ? OR " +
                MediaStore.MediaColumns.RELATIVE_PATH + " LIKE ? OR " +
                MediaStore.MediaColumns.RELATIVE_PATH + " LIKE ? OR " +
                MediaStore.MediaColumns.RELATIVE_PATH + " LIKE ?)"
            val args = arrayOf(FOLDER + "%", "DCIM/" + FOLDER + "%", "Music/" + FOLDER + "%", "Movies/" + FOLDER + "%")
            contentResolver.query(collection, proj, sel, args, MediaStore.MediaColumns.DATE_ADDED + " DESC")?.use { c ->
                val iId = c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                val iName = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                val iSize = c.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
                val iMime = c.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)
                val iDate = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED)
                while (c.moveToNext()) {
                    val id = c.getLong(iId)
                    res.add(
                        Item(
                            c.getString(iName) ?: "file",
                            c.getLong(iSize),
                            ContentUris.withAppendedId(collection, id),
                            c.getString(iMime) ?: "*/*",
                            c.getLong(iDate) * 1000L
                        )
                    )
                }
            }
        } catch (e: Throwable) {
            // ignore
        }
        return res
    }

    private fun refreshList() {
        val items = loadItems()
        b.listBox.removeAllViews()
        b.emptyText.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        val inf = LayoutInflater.from(this)
        for (item in items.take(25)) {
            val row: ItemDownloadBinding = ItemDownloadBinding.inflate(inf, b.listBox, false)
            row.rowName.text = item.name
            row.rowMeta.text = size(item.size) + " \u00B7 " + item.name.substringAfterLast('.', "").uppercase()
            row.rowOpen.setOnClickListener { openUri(item) }
            row.rowShare.setOnClickListener { shareUri(item) }
            b.listBox.addView(row.root)
        }
    }

    private fun size(n: Long): String = when {
        n >= 1024L * 1024L -> String.format("%.1f MB", n / 1024.0 / 1024.0)
        n >= 1024L -> String.format("%.0f KB", n / 1024.0)
        else -> "$n B"
    }

    private fun openUri(i: Item) {
        try {
            val intent = Intent(Intent.ACTION_VIEW)
            intent.setDataAndType(i.uri, i.mime)
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            startActivity(intent)
        } catch (e: Exception) {
            toast("Koi app nahi mila is file ke liye")
        }
    }

    private fun shareUri(i: Item) {
        try {
            val intent = Intent(Intent.ACTION_SEND)
            intent.type = i.mime
            intent.putExtra(Intent.EXTRA_STREAM, i.uri)
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            startActivity(Intent.createChooser(intent, "Share"))
        } catch (e: Exception) {
            toast("Share nahi ho paya")
        }
    }

    private fun toast(m: String) {
        Toast.makeText(this, m, Toast.LENGTH_SHORT).show()
    }
}
