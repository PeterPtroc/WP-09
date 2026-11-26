package org.thermal.wp09;

import android.content.Context;
import android.view.MotionEvent;
import android.view.ViewConfiguration;

import androidx.appcompat.widget.AppCompatSeekBar;
import androidx.core.view.ViewCompat;

/** Drags use the standard SeekBar. A single tap waits for the double-tap interval. */
final class ContrastSlider extends AppCompatSeekBar {
    private static final int CENTER = (ColorScale.NORMAL - ColorScale.MIN_CONTRAST) / 5;
    private final Runnable commitTap = this::commitPendingTap;
    private final int dragSlop, doubleTapSlop;
    private MotionEvent down, pendingTap;
    private boolean dragging, secondTap;

    ContrastSlider(Context context) {
        super(context);
        ViewConfiguration config = ViewConfiguration.get(context);
        dragSlop = config.getScaledTouchSlop();
        doubleTapSlop = config.getScaledDoubleTapSlop();
        ViewCompat.addAccessibilityAction(this, "恢复 100%", (view, args) -> {
            setProgress(CENTER);
            return true;
        });
    }

    void commitPendingTap() {
        if (pendingTap == null) return;
        if (isEnabled()) {
            pendingTap.setAction(MotionEvent.ACTION_DOWN);
            super.onTouchEvent(pendingTap);
            pendingTap.setAction(MotionEvent.ACTION_UP);
            super.onTouchEvent(pendingTap);
            performClick();
        }
        clearPendingTap();
    }

    private void clearPendingTap() {
        removeCallbacks(commitTap);
        if (pendingTap != null) { pendingTap.recycle(); pendingTap = null; }
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        if (!isEnabled()) return super.onTouchEvent(event);
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            releaseDown();
            down = MotionEvent.obtain(event);
            dragging = false;
            secondTap = pendingTap != null
                    && event.getEventTime() - pendingTap.getEventTime() <= ViewConfiguration.getDoubleTapTimeout()
                    && distance(event, pendingTap) <= doubleTapSlop;
            if (secondTap) removeCallbacks(commitTap);
            else commitPendingTap();
            return true;
        }
        if (down == null) return false;
        if (!dragging && (action == MotionEvent.ACTION_POINTER_DOWN
                || action == MotionEvent.ACTION_MOVE && distance(event, down) > dragSlop)) {
            clearPendingTap();
            dragging = true;
            super.onTouchEvent(down);
        }
        if (dragging) super.onTouchEvent(event);
        if (action == MotionEvent.ACTION_UP && !dragging) {
            if (secondTap) {
                clearPendingTap();
                setProgress(CENTER);
                performClick();
            } else {
                pendingTap = MotionEvent.obtain(event);
                postDelayed(commitTap, ViewConfiguration.getDoubleTapTimeout());
            }
        } else if (action == MotionEvent.ACTION_CANCEL && secondTap) {
            commitPendingTap();
        }
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) releaseDown();
        return true;
    }

    private static double distance(MotionEvent a, MotionEvent b) {
        return Math.hypot(a.getX() - b.getX(), a.getY() - b.getY());
    }

    private void releaseDown() {
        if (down != null) { down.recycle(); down = null; }
    }

    @Override public boolean performClick() { return super.performClick(); }

    @Override protected void onDetachedFromWindow() {
        clearPendingTap();
        releaseDown();
        super.onDetachedFromWindow();
    }
}
