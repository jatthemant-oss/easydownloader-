package com.easydownloader.app

import android.app.Application
import android.content.Context
import android.content.Context.MODE_PRIVATE
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL

/**
 * yt-dlp + ffmpeg ko ek hi baar load karta hai.
 * Download button tab tak dabata hai jab tak ye ready na ho.
 */
object Engine {
    @Volatile
    var ready = false
        private set

    @Volatile
    var error: String? = null
        private set

    @Synchronized
    fun init(ctx: Context) {
        if (ready) return
        try {
            YoutubeDL.getInstance().init(ctx)
            FFmpeg.getInstance().init(ctx)
            ready = true
            error = null
        } catch (e: Throwable) {
            error = e.message ?: "engine init failed"
            return
        }
        // yt-dlp ko din me ek baar update karte hain, taaki YouTube badalne par app chalti rahe
        try {
            val prefs = ctx.getSharedPreferences("easydl", MODE_PRIVATE)
            val last = prefs.getLong("ytdlp_update", 0L)
            if (System.currentTimeMillis() - last > 24L * 60L * 60L * 1000L) {
                YoutubeDL.getInstance().updateYoutubeDL(ctx, YoutubeDL.UpdateChannel._STABLE)
                prefs.edit().putLong("ytdlp_update", System.currentTimeMillis()).apply()
            }
        } catch (e: Throwable) {
            // internet nahi hai to purani yt-dlp se kaam chalega
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
