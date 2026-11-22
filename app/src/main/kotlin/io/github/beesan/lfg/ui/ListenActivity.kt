// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.beesan.lfg.ui

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import io.github.beesan.lfg.capture.SoundCapture

/**
 * Asks Android to let LFG listen to game sound, over the game that has just
 * come to the front, and is gone again once it has an answer. It shows nothing
 * of its own: only Android's question, which only the player may answer.
 */
class ListenActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val consent = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val data = result.data
            if (result.resultCode == RESULT_OK && data != null) SoundCapture.start(this, result.resultCode, data)
            finish()
        }
        val microphone = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            // Android routes game audio capture through its recording permission.
            if (granted) consent.launch(SoundCapture.consentIntent(this)) else finish()
        }
        // Android puts this activity back by itself with the question still up: it is asked once.
        if (savedInstanceState == null) microphone.launch(Manifest.permission.RECORD_AUDIO)
    }
}
