package com.punk.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity

/** Entry point used by the tile, notification and the phone's assistant shortcut. */
class VoiceActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startActivity(Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(MainActivity.EXTRA_OPEN_VOICE, true)
        })
        finish()
    }
}
