package com.momir.android

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper

class SplashActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private val launchMainRunnable = Runnable {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_splash)
        handler.postDelayed(launchMainRunnable, 1400L)
    }

    override fun onDestroy() {
        handler.removeCallbacks(launchMainRunnable)
        super.onDestroy()
    }
}
