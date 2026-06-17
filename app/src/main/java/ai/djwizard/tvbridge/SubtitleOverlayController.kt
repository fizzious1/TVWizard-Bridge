package ai.djwizard.tvbridge

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.TextView

// SubtitleOverlayController draws our own subtitle line on top of whatever is
// playing, using a TYPE_ACCESSIBILITY_OVERLAY window. That window type is
// available to an AccessibilityService with NO SYSTEM_ALERT_WINDOW permission
// and NO second app, and it renders above fullscreen video — including Netflix,
// whose FLAG_SECURE blocks screen capture, not overlays drawn on top. The
// bridge feeds it cue text sourced from the relay's OpenSubtitles pipeline;
// this controller only shows/clears text. Every WindowManager call runs on the
// main thread (the frame handler runs on an IO thread).
class SubtitleOverlayController(private val service: AccessibilityService) {

    private val main = Handler(Looper.getMainLooper())
    private val windowManager =
        service.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    // Touched only on the main thread.
    private var textView: TextView? = null

    fun show(text: String) {
        main.post {
            try {
                val view = textView ?: createTextView().also {
                    windowManager.addView(it, buildLayoutParams())
                    textView = it
                }
                if (text.isBlank()) {
                    view.visibility = View.GONE
                } else {
                    view.text = text
                    view.visibility = View.VISIBLE
                }
            } catch (t: Throwable) {
                Log.w(TAG, "overlay show failed: ${t.message}")
            }
        }
    }

    fun clear() {
        main.post {
            val view = textView ?: return@post
            try {
                windowManager.removeView(view)
            } catch (t: Throwable) {
                Log.w(TAG, "overlay clear failed: ${t.message}")
            }
            textView = null
        }
    }

    private fun createTextView(): TextView = TextView(service).apply {
        setTextColor(Color.WHITE)
        // Semi-transparent black plate keeps text legible over any frame.
        setBackgroundColor(Color.argb(160, 0, 0, 0))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
        gravity = Gravity.CENTER
        val padH = dp(16)
        val padV = dp(8)
        setPadding(padH, padV, padH, padV)
        maxLines = 3
    }

    private fun buildLayoutParams(): WindowManager.LayoutParams =
        WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            // Lift off the very bottom edge so it sits in the caption-safe zone.
            y = dp(64)
        }

    private fun dp(value: Int): Int =
        (value * service.resources.displayMetrics.density).toInt()

    private companion object {
        const val TAG = "SubtitleOverlay"
    }
}
