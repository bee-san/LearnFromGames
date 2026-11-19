// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.beesan.lfg.fakegame

import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.util.Log
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * A stand-in for an ordinary app, for testing reading on demand: a news page
 * of real `TextView`s with the status bar showing, a picture whose text is
 * drawn (so only OCR can read it), a password field, and a "video" whose
 * subtitles change every three seconds. Taps and scrolls are logged
 * (`adb logcat -s FakeApp`), so a test can tell they still reach the app.
 * Started with `--ez secure true`, the window is marked secure.
 */
class AppActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (intent.getBooleanExtra("secure", false)) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        val pad = dp(16)
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            setBackgroundColor(Color.WHITE)
        }
        page.addView(text("ニュース・観光", 12f, Color.GRAY))
        page.addView(text("京都の紅葉が見頃を迎えました", 24f, Color.BLACK, bold = true))
        page.addView(Picture(this), LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(160)))
        page.addView(
            text(
                "今週末は多くの観光客が訪れる見込みです。市は混雑を避けるため、早朝の参拝を呼びかけています。\n" +
                    "紅葉は来週まで楽しめるということです。",
                17f, Color.BLACK,
            ),
        )
        page.addView(Subtitles(this), LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(140)))
        page.addView(text("パスワード", 12f, Color.GRAY))
        page.addView(
            EditText(this).apply {
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                setText("ひみつのことば")
            },
        )
        repeat(6) { page.addView(text("明日の待ち合わせ、駅前でいい？ 了解！遅刻しないでね。", 17f, Color.DKGRAY)) }
        val scroll = object : ScrollView(this) {
            override fun onScrollChanged(l: Int, t: Int, oldl: Int, oldt: Int) {
                super.onScrollChanged(l, t, oldl, oldt)
                Log.i(TAG, "scroll $t")
            }

            override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
                if (ev.actionMasked == MotionEvent.ACTION_UP) Log.i(TAG, "tap ${ev.rawX.toInt()},${ev.rawY.toInt()}")
                return super.dispatchTouchEvent(ev)
            }
        }
        scroll.addView(page)
        setContentView(scroll)
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private fun text(value: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = value
        setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
        setTextColor(color)
        if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
        setPadding(0, dp(6), 0, dp(6))
        setTextIsSelectable(true)
    }

    /** A photo of a sign: its text is pixels, with nothing in the accessibility tree. */
    private class Picture(context: Context) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        override fun onDraw(canvas: Canvas) {
            canvas.drawColor(Color.rgb(200, 90, 50))
            paint.color = Color.rgb(247, 242, 230)
            val size = height * 0.22f
            canvas.drawRect(width * 0.08f, height * 0.3f, width * 0.08f + size * 4.6f, height * 0.3f + size * 1.5f, paint)
            paint.color = Color.rgb(180, 36, 26)
            paint.textSize = size
            canvas.drawText("立入禁止", width * 0.08f + size * 0.3f, height * 0.3f + size * 1.1f, paint)
        }
    }

    /** A video frame with burned-in subtitles that change every three seconds. */
    private class Subtitles(context: Context) : View(context) {
        private val lines = listOf("次の駅で降りよう", "雨が降りそうだね", "傘、持ってきた？")
        private var index = 0
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
        private val tick = object : Runnable {
            override fun run() {
                index = (index + 1) % lines.size
                invalidate()
                handler?.postDelayed(this, 3000)
            }
        }

        override fun onAttachedToWindow() {
            super.onAttachedToWindow()
            Handler(Looper.getMainLooper()).postDelayed(tick, 3000)
        }

        override fun onDraw(canvas: Canvas) {
            canvas.drawColor(Color.rgb(29, 42, 59))
            paint.color = Color.WHITE
            paint.textSize = height * 0.16f
            canvas.drawText(lines[index], width / 2f, height * 0.82f, paint)
        }
    }

    private companion object {
        const val TAG = "FakeApp"
    }
}
