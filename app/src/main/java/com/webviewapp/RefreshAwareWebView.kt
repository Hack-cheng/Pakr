package com.webviewapp

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.webkit.WebView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout

/**
 * A WebView subclass that synchronously controls its parent SwipeRefreshLayout
 * based on touch gesture direction and the page's current scroll position.
 *
 * Problem with the default SwipeRefreshLayout + WebView combination:
 *   SwipeRefreshLayout.canChildScrollUp() calls WebView.canScrollVertically(-1),
 *   which only reflects the *native View* scroll offset (scrollY). When a web
 *   page scrolls internally via JavaScript / CSS (i.e. the page body scrolls
 *   but the WebView View itself stays at scrollY == 0), canScrollVertically
 *   always returns false, so every downward finger movement triggers a refresh.
 *
 * Fix strategy (fully synchronous, no JS bridge latency):
 *   On ACTION_DOWN  – record the touch start Y.
 *   On ACTION_MOVE  – compute deltaY each frame.
 *     • If the user is dragging DOWN (deltaY > 0) AND we are not at the very
 *       top of the page (scrollY > 0 OR the JS-cached pageScrollY > 0),
 *       disable SwipeRefreshLayout so the WebView can scroll up normally.
 *     • If the user is dragging DOWN and we ARE at the very top, enable
 *       SwipeRefreshLayout so the pull-to-refresh circle appears.
 *     • If the user is dragging UP, always disable SwipeRefreshLayout.
 *   On ACTION_UP / CANCEL – re-enable SwipeRefreshLayout so the next
 *     down-drag is evaluated fresh.
 *
 * pageScrollY is updated by MainActivity via the ScrollBridge JS interface
 * (injected on every page load). It reflects window.scrollY of the web page,
 * which canScrollVertically(-1) cannot see.
 */
class RefreshAwareWebView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : WebView(context, attrs, defStyleAttr) {

    /** Set by MainActivity; points to the surrounding SwipeRefreshLayout. */
    var swipeRefreshLayout: SwipeRefreshLayout? = null

    /**
     * Mirrors window.scrollY of the loaded web page.
     * Updated by MainActivity's ScrollBridge JS interface on every scroll event.
     * Declared @Volatile so the UI-thread read here is always fresh.
     */
    @Volatile var pageScrollY: Int = 0

    private var touchStartY = 0f
    private var touchStartX = 0f

    // Minimum vertical movement (px) before we make a direction decision.
    // Keeps diagonal / near-horizontal swipes from incorrectly toggling refresh.
    private val slopPx: Int by lazy {
        android.view.ViewConfiguration.get(context).scaledTouchSlop
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val srl = swipeRefreshLayout ?: return super.onTouchEvent(event)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                touchStartY = event.rawY
                touchStartX = event.rawX
                // Don't change srl.isEnabled here – wait until we know direction.
            }

            MotionEvent.ACTION_MOVE -> {
                val deltaY = event.rawY - touchStartY
                val deltaX = event.rawX - touchStartX

                // Only act once we've moved beyond slop, and the gesture is
                // more vertical than horizontal (avoids fighting horizontal scrollers).
                if (Math.abs(deltaY) > slopPx && Math.abs(deltaY) > Math.abs(deltaX)) {
                    val atPageTop = scrollY <= 0 && pageScrollY <= 0
                    if (deltaY > 0) {
                        // Finger moving DOWN → pull-to-refresh territory only if at top
                        srl.isEnabled = atPageTop
                    } else {
                        // Finger moving UP → always let WebView scroll; disable refresh
                        srl.isEnabled = false
                    }
                }
            }

            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL,
            MotionEvent.ACTION_POINTER_UP -> {
                // Restore so the next gesture is evaluated from scratch
                srl.isEnabled = true
            }
        }

        return super.onTouchEvent(event)
    }
}
