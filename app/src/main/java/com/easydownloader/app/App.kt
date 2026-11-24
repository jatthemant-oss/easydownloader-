package com.easydownloader.app

import android.app.Application
import android.content.Context
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
