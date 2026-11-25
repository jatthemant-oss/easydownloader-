package com.easydownloader.app

import android.app.Application
import android.content.Context
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL

/**
 * yt-dlp + ffmpeg ko ek hi baar load karta hai, aur yt-dlp ko update rakhta hai.
 */
object Engine {
    @Volatile
    var ready = false
        private set

    @Volatile
    var error: String? = null
        private set

    @Volatile
    var note: String = "Engine shuru ho raha hai…"

    @Synchronized
    fun init(ctx: Context) {
        if (ready) return
        try {
            YoutubeDL.getInstance().init(ctx)
            FFmpeg.getInstance().init(ctx)
            ready = true
            error = null
            note = "Engine ready"
        } catch (e: Throwable) {
            error = e.message ?: "engine init failed"
            note = "Engine fail: " + (e.message ?: "").take(80)
            return
        }
        update(ctx, false)
    }

    /** yt-dlp ko naya version laata hai. force = true par roz ki limit ignore hoti hai. */
    @Synchronized
    fun update(ctx: Context, force: Boolean) {
        try {
            val prefs = ctx.getSharedPreferences("easydl", Context.MODE_PRIVATE)
            val last = prefs.getLong("ytdlp_update", 0L)
            val day = 24L * 60L * 60L * 1000L
            if (!force && System.currentTimeMillis() - last < day) {
                note = "Engine ready"
                return
            }
            note = "yt-dlp update ho raha hai…"
            YoutubeDL.getInstance().updateYoutubeDL(ctx, YoutubeDL.UpdateChannel._STABLE)
            prefs.edit().putLong("ytdlp_update", System.currentTimeMillis()).apply()
            note = "yt-dlp updated"
        } catch (e: Throwable) {
            note = "yt-dlp update fail: " + (e.message ?: "").take(70)
        }
    }
}

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        val app = applicationContext
        Thread { Engine.init(app) }.start()
    }
}
