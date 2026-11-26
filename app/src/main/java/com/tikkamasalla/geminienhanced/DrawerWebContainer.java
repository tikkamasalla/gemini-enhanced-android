package com.tikkamasalla.geminienhanced;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.util.AttributeSet;
import android.util.Log;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.animation.Interpolator;
import android.view.inputmethod.InputMethodManager;
import android.webkit.WebView;
import android.widget.FrameLayout;

// Native sidebar. Holds the process-lifetime chat WebView and the native sidebar
// (NativeSidebar) above it. Android alone decides every sidebar swipe on the UI thread:
// the page is never asked and never waited for. The only page input is the list of
// places where a sideways swipe belongs to the page (text fields, sideways scrollers
// that can still scroll right), sent ahead of time by sidebar.js.
//  - closed: a right swipe anywhere (outside those places) opens; the panel follows the
//    finger 1:1 from touch slop, the chat is pushed and dimmed.
//  - open: a left swipe anywhere closes; a tap on the dimmed chat closes.
//  - release: settles open or closed with the finger's velocity.
public class DrawerWebContainer extends FrameLayout {
    private static final String TAG = "GeminiEnhanced";

    private static final float DIR_RATIO = 1.3f;     // sideways = |dx| > 1.3 |dy| at slop
    private static final float OPEN_FRACTION = 0.5f;  // released past half: stays/settles open
    private static final float FLING_DP_S = 400f;     // a flick faster than this decides by direction
    private static final long MIN_MS = 140, MAX_MS = 300, FULL_MS = 260;

    private NativeSidebar sidebar = null;
    private WebView webView = null;

    private final int touchSlop;
    private final int longPressMs;
    private final float density;
    private float pos = 0f;          // 0 closed .. 1 open
    private int panelW = 0;

    // touch state
    private static final int T_NONE = 0, T_WATCH = 1, T_DRAG = 2, T_IGNORE = 3, T_SCRIM = 4;
    private int touch = T_NONE;
    private float downX, downY, originX, startPos;
    private long downTime;
    private VelocityTracker velocity = null;
    private ValueAnimator anim = null;
    private boolean targetOpen = false;
    private boolean pageBlocksSwipe = false;  // set at touch-down from the page's regions

    // Page facts (from sidebar.js, any thread)
    private volatile float dpr = 0f;
    private volatile float[] blockAll = new float[0];    // rects (css px) where no sidebar swipe starts
    private volatile float[] blockRight = new float[0];  // rects where a right swipe scrolls the page

    public DrawerWebContainer(Context context) {
        this(context, null);
    }

    public DrawerWebContainer(Context context, AttributeSet attrs) {
        super(context, attrs);
        ViewConfiguration vc = ViewConfiguration.get(context);
        touchSlop = vc.getScaledTouchSlop();
        longPressMs = ViewConfiguration.getLongPressTimeout();
        density = context.getResources().getDisplayMetrics().density;
    }

    // ---- wiring ----

    public void setSidebar(NativeSidebar s) {
        if (sidebar != null && sidebar.getParent() == this) removeView(sidebar);
        sidebar = s;
        if (s != null) {
            if (s.getParent() instanceof android.view.ViewGroup) ((android.view.ViewGroup) s.getParent()).removeView(s);
            addView(s, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
            s.setContainer(this);
            applyPos();
        }
    }

    public NativeSidebar getSidebar() {
        return sidebar;
    }

    @Override
    public void onViewAdded(View child) {
        super.onViewAdded(child);
        if (child instanceof WebView) {
            webView = (WebView) child;
            // the sidebar always stays above the (re-attached) WebView
            if (sidebar != null && sidebar.getParent() == this) sidebar.bringToFront();
            applyPos();
        }
    }

    @Override
    public void onViewRemoved(View child) {
        super.onViewRemoved(child);
        if (child == webView) {
            // the WebView may be hosted off-screen next: never take our push along
            child.setTranslationX(0f);
            webView = null;
        }
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        panelW = NativeSidebar.panelWidth(w, density);
        if (sidebar != null) sidebar.setPanelWidth(panelW);
        applyPos();
    }

    // ---- page facts ----

    public void setPageRegions(float dpr, float[] all, float[] right) {
        this.dpr = dpr;
        this.blockAll = all != null ? all : new float[0];
        this.blockRight = right != null ? right : new float[0];
    }

    private static boolean inRects(float[] r, float x, float y) {
        for (int i = 0; i + 3 < r.length; i += 4) {
            if (x >= r[i] && y >= r[i + 1] && x <= r[i + 2] && y <= r[i + 3]) return true;
        }
        return false;
    }

    // ---- public control ----

    public boolean isOpen() {
        return pos > 0f || (anim != null && targetOpen);
    }

    public void open(boolean animate) {
        hideKeyboard();
        if (sidebar != null) sidebar.onOpening();
        settle(true, 0f, animate);
    }

    public void close(boolean animate) {
        settle(false, 0f, animate);
    }

    // ---- touches ----

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        try {
            if (sidebar == null || panelW <= 0 || !sidebar.isEnabledForSwipe()) {
                return sidebarFallback(ev);
            }
            int a = ev.getActionMasked();
            if (a == MotionEvent.ACTION_DOWN) onDown(ev);
            else if (velocity != null) velocity.addMovement(ev);

            switch (touch) {
                case T_DRAG:
                    onDragEvent(ev);
                    return true;
                case T_SCRIM:
                    onScrimEvent(ev);
                    return true;
                case T_WATCH:
                    if (a == MotionEvent.ACTION_POINTER_DOWN) {
                        touch = T_IGNORE;   // pinch or two fingers: the page's
                    } else if (a == MotionEvent.ACTION_MOVE && maybeStartDrag(ev)) {
                        onDragEvent(ev);
                        return true;
                    }
                    break;
                default:
                    break;
            }
            boolean r = super.dispatchTouchEvent(ev);
            if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) endTouch();
            return r;
        } catch (Exception e) {
            Log.w(TAG, "sidebar touch failed", e);
            touch = T_NONE;
            try {
                return super.dispatchTouchEvent(ev);
            } catch (Exception x) {
                return false;
            }
        }
    }

    // The native list is not usable (no data after the page is ready): a right swipe
    // opens the page's own sidebar the old way, so the app never has no sidebar.
    private boolean fbWatch = false;
    private float fbX, fbY;

    private boolean sidebarFallback(MotionEvent ev) {
        int a = ev.getActionMasked();
        if (a == MotionEvent.ACTION_DOWN) {
            fbWatch = true;
            fbX = ev.getX();
            fbY = ev.getY();
            pageBlocksSwipe = isPageRegion(ev.getX(), ev.getY(), true);
        } else if (a == MotionEvent.ACTION_MOVE && fbWatch) {
            float dx = ev.getX() - fbX, dy = ev.getY() - fbY;
            if (Math.max(Math.abs(dx), Math.abs(dy)) > touchSlop) {
                fbWatch = false;
                if (dx > 0 && Math.abs(dx) > DIR_RATIO * Math.abs(dy) && !pageBlocksSwipe && sidebar != null) {
                    sidebar.openPageSidebar();
                }
            }
        } else if (a == MotionEvent.ACTION_POINTER_DOWN) {
            fbWatch = false;
        }
        return super.dispatchTouchEvent(ev);
    }

    private void onDown(MotionEvent ev) {
        downX = ev.getX();
        downY = ev.getY();
        downTime = ev.getEventTime();
        if (velocity != null) velocity.recycle();
        velocity = VelocityTracker.obtain();
        velocity.addMovement(ev);
        if (anim != null && (targetOpen ? pos > 0.9f : pos < 0.1f)) {
            // nearly there: finish now and let this touch work normally (a row tap right
            // after an open lands on the row, a tap right after a close on the chat)
            cancelAnim();
            setPos(targetOpen ? 1f : 0f);
            onSettled(targetOpen);
        }
        if (anim != null) {
            // catch the panel mid-flight: it follows the finger from where it is
            cancelAnim();
            startDrag(ev.getX());
            touch = T_DRAG;
            return;
        }
        if (pos >= 1f && downX > panelW) {
            touch = T_SCRIM;                     // the dimmed chat: ours only
            return;
        }
        touch = T_WATCH;
        pageBlocksSwipe = pos <= 0f && isPageRegion(downX, downY, true);
    }

    private boolean isPageRegion(float x, float y, boolean rightSwipe) {
        float d = dpr;
        if (d <= 0f || webView == null) return false;
        float cx = (x - webView.getLeft() - webView.getTranslationX()) / d;
        float cy = (y - webView.getTop()) / d;
        return inRects(blockAll, cx, cy) || (rightSwipe && inRects(blockRight, cx, cy));
    }

    private boolean maybeStartDrag(MotionEvent ev) {
        float dx = ev.getX() - downX, dy = ev.getY() - downY;
        if (Math.max(Math.abs(dx), Math.abs(dy)) <= touchSlop) return false;
        boolean sideways = Math.abs(dx) > DIR_RATIO * Math.abs(dy);
        boolean longHeld = ev.getEventTime() - downTime > longPressMs;   // text selection drag
        boolean wanted = pos <= 0f ? dx > 0 : dx < 0;
        if (!sideways || !wanted || longHeld || (pos <= 0f && pageBlocksSwipe)) {
            touch = T_IGNORE;
            return false;
        }
        // Take the gesture: the page (or the list) gets a cancel and nothing more.
        MotionEvent c = MotionEvent.obtain(ev);
        c.setAction(MotionEvent.ACTION_CANCEL);
        super.dispatchTouchEvent(c);
        c.recycle();
        if (pos <= 0f) {
            hideKeyboard();
            if (sidebar != null) sidebar.onOpening();
        }
        // follow from the slop point so the panel never jumps
        float sign = dx > 0 ? 1f : -1f;
        originX = downX + sign * touchSlop;
        startPos = pos;
        touch = T_DRAG;
        return true;
    }

    private void startDrag(float x) {
        originX = x;
        startPos = pos;
    }

    private void onDragEvent(MotionEvent ev) {
        int a = ev.getActionMasked();
        if (a == MotionEvent.ACTION_MOVE || a == MotionEvent.ACTION_DOWN) {
            setPos(startPos + (ev.getX() - originX) / panelW);
        } else if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) {
            setPos(startPos + (ev.getX() - originX) / panelW);
            float v = 0f;
            if (velocity != null && a == MotionEvent.ACTION_UP) {
                velocity.computeCurrentVelocity(1000);
                v = velocity.getXVelocity();
            }
            boolean open;
            float flick = FLING_DP_S * density;
            if (a == MotionEvent.ACTION_CANCEL) open = pos >= OPEN_FRACTION;
            else if (v > flick) open = true;
            else if (v < -flick) open = false;
            else open = pos >= OPEN_FRACTION;
            settle(open, v, true);
            endTouch();
        }
    }

    private void onScrimEvent(MotionEvent ev) {
        int a = ev.getActionMasked();
        if (a == MotionEvent.ACTION_MOVE) {
            float dx = ev.getX() - downX, dy = ev.getY() - downY;
            if (Math.max(Math.abs(dx), Math.abs(dy)) > touchSlop && dx < 0 && Math.abs(dx) > DIR_RATIO * Math.abs(dy)) {
                originX = downX - touchSlop;
                startPos = pos;
                touch = T_DRAG;
                onDragEvent(ev);
            }
        } else if (a == MotionEvent.ACTION_UP) {
            float dx = ev.getX() - downX, dy = ev.getY() - downY;
            if (Math.max(Math.abs(dx), Math.abs(dy)) <= touchSlop) {
                settle(false, 0f, true);
            }
            endTouch();
        } else if (a == MotionEvent.ACTION_CANCEL) {
            endTouch();
        }
    }

    private void endTouch() {
        touch = T_NONE;
        if (velocity != null) {
            velocity.recycle();
            velocity = null;
        }
    }

    // ---- position ----

    private void setPos(float p) {
        p = Math.max(0f, Math.min(1f, p));
        if (p == pos) return;
        boolean wasClosed = pos <= 0f;
        pos = p;
        applyPos();
        if (wasClosed && p > 0f && sidebar != null) sidebar.onShown();
        if (p <= 0f && sidebar != null) sidebar.onHidden();
    }

    private void applyPos() {
        if (sidebar != null) sidebar.setProgress(pos);
        if (webView != null && webView.getParent() == this) {
            float push = NativeSidebar.PUSH ? pos * panelW : 0f;
            if (webView.getTranslationX() != push) webView.setTranslationX(push);
        }
    }

    private void settle(final boolean open, float velocityPx, boolean animate) {
        cancelAnim();
        targetOpen = open;
        final float target = open ? 1f : 0f;
        if (!animate || panelW <= 0 || pos == target) {
            setPos(target);
            onSettled(open);
            return;
        }
        float distPx = Math.abs(target - pos) * panelW;
        // Velocity-aware: keep the finger's speed at the start of the curve (the curve's
        // starting slope is 2.2 x its mean speed), never slower than a full slide in FULL_MS.
        float speed = Math.abs(velocityPx);
        boolean towards = open ? velocityPx > 0 : velocityPx < 0;
        long dur;
        if (towards && speed > 1f) dur = Math.round(2.2f * distPx / speed * 1000f);
        else dur = Math.round(FULL_MS * Math.sqrt(distPx / Math.max(1f, panelW)));
        dur = Math.max(MIN_MS, Math.min(MAX_MS, dur));
        final float from = pos;
        anim = ValueAnimator.ofFloat(0f, 1f);
        anim.setDuration(dur);
        anim.setInterpolator(EASE_OUT);
        anim.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator a) {
                float f = (float) a.getAnimatedValue();
                setPos(from + (target - from) * f);
            }
        });
        anim.addListener(new AnimatorListenerAdapter() {
            private boolean cancelled = false;

            @Override
            public void onAnimationCancel(Animator a) {
                cancelled = true;
            }

            @Override
            public void onAnimationEnd(Animator a) {
                if (anim == a) anim = null;
                if (!cancelled) onSettled(open);
            }
        });
        anim.start();
    }

    private void onSettled(boolean open) {
        if (sidebar != null) sidebar.onSettled(open);
    }

    // Ease-out whose starting slope is 2.2 (a velocity-matched start), ending at rest.
    private static final Interpolator EASE_OUT = new Interpolator() {
        @Override
        public float getInterpolation(float t) {
            // 1 - (1 - t)^2.2
            return (float) (1.0 - Math.pow(1.0 - t, 2.2));
        }
    };

    private void cancelAnim() {
        if (anim != null) {
            ValueAnimator a = anim;
            anim = null;
            a.cancel();
        }
    }

    private void hideKeyboard() {
        try {
            InputMethodManager imm = (InputMethodManager) getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) imm.hideSoftInputFromWindow(getWindowToken(), 0);
        } catch (Exception e) {
            Log.w(TAG, "sidebar keyboard hide failed", e);
        }
    }

}
