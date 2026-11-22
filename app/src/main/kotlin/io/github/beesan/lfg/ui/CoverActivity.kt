// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.beesan.lfg.ui

import android.app.Activity
import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import io.github.beesan.lfg.capture.LfgAccessibilityService

/**
 * A see-through screen over the game while a word is looked up with the
 * controller. Android gives every tap, button and stick to the app on top, so
 * while this is up none of them can reach the game: a thumb that slips does
 * not move the story on. The game is only told it has been covered, as by any
 * dialog, and the ones that pause for that pause. It shows nothing of its own
 * and goes the moment lookup ends, which gives the game its screen back as it
 * was.
 */
class CoverActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())

    /** Lookup can end without this hearing of it (the service was switched off): it never outstays lookup. */
    private val watch = object : Runnable {
        override fun run() {
            if (!wanted || LfgAccessibilityService.instance?.lookupOn != true) leave() else handler.postDelayed(this, WATCH_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        overridePendingTransition(0, 0)
        // By this the service knows the window for its own, and goes on reading the game under it.
        title = TITLE
        window.setTitle(TITLE)
        window.attributes = window.attributes.apply {
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS)
        setContentView(View(this))
        hideBars()
        if (!wanted) {
            // Lookup ended while Android was still bringing this up.
            leave()
            return
        }
        current = this
        handler.postDelayed(watch, WATCH_MS)
        Log.i(TAG, "over the game on screen ${display?.displayId}")
    }

    // A game hides the system bars; covering it must not bring them back and shift its picture.
    private fun hideBars() {
        window.insetsController?.let {
            it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            it.hide(WindowInsets.Type.systemBars())
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideBars()
    }

    // The lookup window holds the controller. If a button or a stick comes here instead, it is the same hand.
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (!KeyEvent.isGamepadButton(event.keyCode) && event.keyCode !in DPAD) return super.dispatchKeyEvent(event)
        LfgAccessibilityService.instance?.coverKey(event)
        return true
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        if (event.source and InputDevice.SOURCE_CLASS_JOYSTICK == 0) return true
        LfgAccessibilityService.instance?.coverMotion(event)
        return true
    }

    // A tap that missed the words and the entry lands here, not on the game.
    override fun dispatchTouchEvent(event: MotionEvent): Boolean = true

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() = gone()

    // Home, or another app over this one: lookup is over, and nothing of ours is left on the game.
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        gone()
    }

    override fun onStop() {
        super.onStop()
        if (!isFinishing) gone()
    }

    override fun onDestroy() {
        handler.removeCallbacks(watch)
        if (current === this) current = null
        super.onDestroy()
    }

    /** Android took this away, or the player backed out of it: lookup ends with it. */
    private fun gone() {
        LfgAccessibilityService.instance?.leaveLookup()
        leave()
    }

    private fun leave() {
        handler.removeCallbacks(watch)
        if (current === this) current = null
        if (isFinishing) return
        finishAndRemoveTask()
        overridePendingTransition(0, 0)
    }

    companion object {
        private const val TAG = "LfgCover"

        /** The window's title, which the service knows it by. */
        const val TITLE = "LFG lookup cover"

        private const val WATCH_MS = 500L

        private val DPAD = setOf(
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_DPAD_CENTER,
        )

        @Volatile
        private var wanted = false

        /** The screen the cover is on or coming to, or null when lookup is not covering a game. */
        @Volatile
        var displayId: Int? = null
            private set

        /** Main thread only. */
        private var current: CoverActivity? = null

        /** Covers the game on [display]. Main thread only. */
        fun show(context: Context, display: Int) {
            if (wanted && displayId == display) return
            wanted = true
            displayId = display
            val intent = Intent(context, CoverActivity::class.java).addFlags(
                // Not the player leaving the game: a game that shrinks to a small window when left stays as it is.
                Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION or Intent.FLAG_ACTIVITY_NO_ANIMATION,
            )
            try {
                context.startActivity(intent, ActivityOptions.makeCustomAnimation(context, 0, 0).setLaunchDisplayId(display).toBundle())
            } catch (e: Exception) {
                Log.w(TAG, "could not cover the game", e)
                wanted = false
                displayId = null
            }
        }

        /** Takes the cover away. Main thread only. */
        fun hide() {
            wanted = false
            displayId = null
            current?.leave()
        }
    }
}
