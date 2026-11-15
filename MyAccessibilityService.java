package com.mousecorsul;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.annotation.TargetApi;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.view.ContextThemeWrapper;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.widget.Button;
import android.widget.Toast;

/**
 * MouseCorsul backend.
 *
 * Shows a floating cursor plus a small floating touchpad panel and turns touchpad input
 * into real touches with AccessibilityService.dispatchGesture().
 *
 * Nothing is shown and nothing is dispatched until the user presses Start in MainActivity.
 */
public class MyAccessibilityService extends AccessibilityService {

    // ------------------------------------------------------------ constants
    private static final long TAP_MS = 40L;
    private static final long DOUBLE_TAP_GAP_MS = 120L;
    private static final long DRAG_STEP_MS = 40L;
    private static final long SCROLL_MS = 350L;
    private static final float BASE_GAIN = 1.8f;

    // ------------------------------------------------------------ singleton
    private static MyAccessibilityService instance;

    public static MyAccessibilityService getInstance() {
        return instance;
    }

    // ---------------------------------------------------------------- state
    private WindowManager windowManager;
    private Handler handler;
    private float density = 1f;
    private boolean running = false;

    private int screenW;
    private int screenH;

    // cursor (screen coordinates of the arrow tip)
    private CursorView cursorView;
    private WindowManager.LayoutParams cursorParams;
    private float cursorX;
    private float cursorY;

    // difference between real on-screen position and WindowManager x/y
    private int offsetX = 0;
    private int offsetY = 0;

    // floating panel
    private View panelView;
    private WindowManager.LayoutParams panelParams;
    private Button dragButton;

    // pad touch tracking
    private float padLastRawX;
    private float padLastRawY;
    private float padDownRawX;
    private float padDownRawY;
    private long padDownTime;
    private boolean padMoved;
    private boolean padTwoFinger;
    private boolean padLongPressFired;
    private float scrollAccum;
    private float scrollLastY;
    private long scrollReadyAt = 0L;

    // drag state
    private boolean dragMode = false;
    private boolean dragActive = false;
    private boolean dragEndRequested = false;
    private GestureDescription.StrokeDescription dragStroke;
    private float dragLastX;
    private float dragLastY;
    private float dragStartX;
    private float dragStartY;

    private final Runnable longPressRunnable = new Runnable() {
        @Override
        public void run() {
            if (running && !padMoved && !padTwoFinger && !dragMode) {
                padLongPressFired = true;
                rightClick();
            }
        }
    };

    private final AccessibilityService.GestureResultCallback dragCallback =
            new AccessibilityService.GestureResultCallback() {
                @Override
                public void onCompleted(GestureDescription gestureDescription) {
                    if (Build.VERSION.SDK_INT >= 26) {
                        onDragSegmentDone();
                    }
                }

                @Override
                public void onCancelled(GestureDescription gestureDescription) {
                    dragActive = false;
                    dragEndRequested = false;
                    dragStroke = null;
                }
            };

    // ------------------------------------------------------ service lifecycle

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        handler = new Handler(Looper.getMainLooper());
        windowManager = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        density = getResources().getDisplayMetrics().density;
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // Not used: this service only performs gestures.
    }

    @Override
    public void onInterrupt() {
        // Nothing to interrupt.
    }

    @Override
    public boolean onUnbind(android.content.Intent intent) {
        stopMouse();
        if (instance == this) {
            instance = null;
        }
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        stopMouse();
        if (instance == this) {
            instance = null;
        }
        super.onDestroy();
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        if (!running || windowManager == null) {
            return;
        }
        updateScreenSize();
        cursorX = clamp(cursorX, 0f, screenW - 1);
        cursorY = clamp(cursorY, 0f, screenH - 1);
        updateCursor();
        panelParams.width = Math.min(dp(320), screenW - dp(16));
        movePanelTo(panelParams.x, panelParams.y);
        ensureCursorNotUnderPanel();
    }

    // ---------------------------------------------------------- public control

    public boolean isRunning() {
        return running;
    }

    /** Shows the cursor and the touchpad panel. Returns true on success. */
    public boolean startMouse() {
        if (running) {
            return true;
        }
        if (windowManager == null || handler == null) {
            return false;
        }
        updateScreenSize();
        cursorX = screenW / 2f;
        cursorY = screenH / 2f;
        offsetX = 0;
        offsetY = 0;
        dragMode = false;
        dragActive = false;
        dragEndRequested = false;
        dragStroke = null;

        try {
            createCursor();
            createPanel();
        } catch (RuntimeException e) {
            removeOverlays();
            Toast.makeText(this, R.string.msg_start_failed, Toast.LENGTH_LONG).show();
            return false;
        }
        running = true;

        // After the windows are laid out: calibrate coordinates and keep cursor clear of the panel.
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (running) {
                    calibrate();
                    ensureCursorNotUnderPanel();
                }
            }
        }, 350L);
        return true;
    }

    /** Removes the cursor and the touchpad panel. */
    public void stopMouse() {
        if (dragActive) {
            // let the in-flight drag finish with a touch-up
            dragEndRequested = true;
        }
        dragMode = false;
        running = false;
        if (handler != null) {
            handler.removeCallbacks(longPressRunnable);
        }
        removeOverlays();
    }

    // ------------------------------------------------------------ overlays

    private int overlayType() {
        if (Build.VERSION.SDK_INT >= 26 && Settings.canDrawOverlays(this)) {
            return 2038; // WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        }
        return WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY;
    }

    private void createCursor() {
        cursorView = new CursorView(this);
        int size = dp(28);
        cursorParams = new WindowManager.LayoutParams(
                size, size, overlayType(),
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        cursorParams.gravity = Gravity.TOP | Gravity.LEFT;
        cursorParams.x = Math.round(cursorX);
        cursorParams.y = Math.round(cursorY);
        windowManager.addView(cursorView, cursorParams);
    }

    private void createPanel() {
        Context themed = new ContextThemeWrapper(this, android.R.style.Theme_DeviceDefault);
        panelView = LayoutInflater.from(themed).inflate(R.layout.touchpad_panel, null);

        View handle = panelView.findViewById(R.id.panel_handle);
        View pad = panelView.findViewById(R.id.pad);
        Button btnClick = (Button) panelView.findViewById(R.id.btn_click);
        Button btnDouble = (Button) panelView.findViewById(R.id.btn_double);
        Button btnRight = (Button) panelView.findViewById(R.id.btn_right);
        Button btnScrollUp = (Button) panelView.findViewById(R.id.btn_scroll_up);
        Button btnScrollDown = (Button) panelView.findViewById(R.id.btn_scroll_down);
        Button btnStop = (Button) panelView.findViewById(R.id.btn_stop);
        dragButton = (Button) panelView.findViewById(R.id.btn_drag);

        // move the panel by dragging the title bar
        handle.setOnTouchListener(new View.OnTouchListener() {
            private float downRawX;
            private float downRawY;
            private int startX;
            private int startY;

            @Override
            public boolean onTouch(View v, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        downRawX = e.getRawX();
                        downRawY = e.getRawY();
                        startX = panelParams.x;
                        startY = panelParams.y;
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        movePanelTo(startX + Math.round(e.getRawX() - downRawX),
                                startY + Math.round(e.getRawY() - downRawY));
                        return true;
                    default:
                        return true;
                }
            }
        });

        // touchpad area
        pad.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent e) {
                return padTouch(e);
            }
        });

        btnClick.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                click();
            }
        });

        btnDouble.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                doubleClick();
            }
        });

        btnRight.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                rightClick();
            }
        });

        dragButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                setDragMode(!dragMode);
            }
        });

        bindScrollButton(btnScrollUp, true);
        bindScrollButton(btnScrollDown, false);

        btnStop.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        stopMouse();
                    }
                });
            }
        });

        int width = Math.min(dp(320), screenW - dp(16));
        panelParams = new WindowManager.LayoutParams(
                width, WindowManager.LayoutParams.WRAP_CONTENT, overlayType(),
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        panelParams.gravity = Gravity.TOP | Gravity.LEFT;
        panelParams.x = Math.max(0, (screenW - width) / 2);
        panelParams.y = Math.max(0, screenH - dp(300) - dp(48));
        windowManager.addView(panelView, panelParams);
    }

    private void removeOverlays() {
        if (windowManager != null) {
            if (cursorView != null) {
                try {
                    windowManager.removeView(cursorView);
                } catch (RuntimeException e) {
                    // already removed
                }
            }
            if (panelView != null) {
                try {
                    windowManager.removeView(panelView);
                } catch (RuntimeException e) {
                    // already removed
                }
            }
        }
        cursorView = null;
        panelView = null;
        dragButton = null;
    }

    private void bindScrollButton(Button button, final boolean up) {
        final Runnable repeat = new Runnable() {
            @Override
            public void run() {
                if (!running) {
                    return;
                }
                scroll(up);
                handler.postDelayed(this, 450L);
            }
        };
        button.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent e) {
                int action = e.getActionMasked();
                if (action == MotionEvent.ACTION_DOWN) {
                    handler.removeCallbacks(repeat);
                    handler.post(repeat);
                } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                    handler.removeCallbacks(repeat);
                }
                return false; // keep the normal pressed look
            }
        });
    }

    private void setDragMode(boolean on) {
        if (!on && dragActive) {
            endDrag();
        }
        dragMode = on;
        if (dragButton != null) {
            dragButton.setText(on ? R.string.panel_drag_on : R.string.panel_drag_off);
        }
        if (cursorView != null) {
            cursorView.setDragging(on);
        }
    }

    // ------------------------------------------------------- geometry helpers

    private void updateScreenSize() {
        DisplayMetrics dm = new DisplayMetrics();
        windowManager.getDefaultDisplay().getRealMetrics(dm);
        screenW = dm.widthPixels;
        screenH = dm.heightPixels;
        density = dm.density;
    }

    private int dp(int value) {
        return Math.round(value * density);
    }

    private static float clamp(float v, float min, float max) {
        if (v < min) {
            return min;
        }
        if (v > max) {
            return max;
        }
        return v;
    }

    private void calibrate() {
        if (cursorView == null) {
            return;
        }
        int[] loc = new int[2];
        cursorView.getLocationOnScreen(loc);
        int ox = loc[0] - cursorParams.x;
        int oy = loc[1] - cursorParams.y;
        if (Math.abs(ox) <= dp(120) && Math.abs(oy) <= dp(120)) {
            offsetX = ox;
            offsetY = oy;
        }
        updateCursor();
    }

    private void updateCursor() {
        if (cursorView == null || cursorParams == null) {
            return;
        }
        cursorParams.x = Math.round(cursorX) - offsetX;
        cursorParams.y = Math.round(cursorY) - offsetY;
        try {
            windowManager.updateViewLayout(cursorView, cursorParams);
        } catch (RuntimeException e) {
            // view not attached
        }
    }

    private void moveCursor(float dx, float dy) {
        cursorX = clamp(cursorX + dx, 0f, screenW - 1);
        cursorY = clamp(cursorY + dy, 0f, screenH - 1);
        updateCursor();
        ensureCursorNotUnderPanel();
    }

    private int panelWidthPx() {
        if (panelView != null && panelView.getWidth() > 0) {
            return panelView.getWidth();
        }
        return panelParams != null ? panelParams.width : dp(300);
    }

    private int panelHeightPx() {
        if (panelView != null && panelView.getHeight() > 0) {
            return panelView.getHeight();
        }
        return dp(300);
    }

    /** Moves the panel; x and y are WindowManager coordinates. */
    private void movePanelTo(int x, int y) {
        if (panelView == null || panelParams == null) {
            return;
        }
        int w = panelWidthPx();
        int h = panelHeightPx();
        int sx = Math.round(clamp(x + offsetX, 0f, Math.max(0, screenW - w)));
        int sy = Math.round(clamp(y + offsetY, 0f, Math.max(0, screenH - h)));
        panelParams.x = sx - offsetX;
        panelParams.y = sy - offsetY;
        try {
            windowManager.updateViewLayout(panelView, panelParams);
        } catch (RuntimeException e) {
            // view not attached
        }
    }

    private boolean cursorOverlaps(int sx, int sy, int w, int h, int margin) {
        return cursorX >= sx - margin && cursorX <= sx + w + margin
                && cursorY >= sy - margin && cursorY <= sy + h + margin;
    }

    /**
     * Injected touches would hit our own panel if the cursor were above it.
     * So when the cursor gets close to the panel, the panel jumps to a free corner.
     * Returns true if the panel was moved.
     */
    private boolean ensureCursorNotUnderPanel() {
        if (panelView == null || panelParams == null) {
            return false;
        }
        int w = panelWidthPx();
        int h = panelHeightPx();
        int margin = dp(24);
        int curX = panelParams.x + offsetX;
        int curY = panelParams.y + offsetY;
        if (!cursorOverlaps(curX, curY, w, h, margin)) {
            return false;
        }

        int left = margin;
        int right = Math.max(0, screenW - w - margin);
        int top = margin;
        int bottom = Math.max(0, screenH - h - margin);
        int flipY = (curY + h / 2 > screenH / 2) ? top : bottom;

        int[] xs = {curX, left, right, left, right};
        int[] ys = {flipY, top, top, bottom, bottom};
        for (int i = 0; i < xs.length; i++) {
            if (!cursorOverlaps(xs[i], ys[i], w, h, margin)) {
                panelParams.x = xs[i] - offsetX;
                panelParams.y = ys[i] - offsetY;
                try {
                    windowManager.updateViewLayout(panelView, panelParams);
                } catch (RuntimeException e) {
                    // view not attached
                }
                return true;
            }
        }
        return false;
    }

    // --------------------------------------------------------- pad touch input

    private float averageY(MotionEvent e) {
        float sum = 0f;
        int n = e.getPointerCount();
        for (int i = 0; i < n; i++) {
            sum += e.getY(i);
        }
        return n > 0 ? sum / n : 0f;
    }

    private boolean padTouch(MotionEvent e) {
        switch (e.getActionMasked()) {

            case MotionEvent.ACTION_DOWN: {
                padLastRawX = e.getRawX();
                padLastRawY = e.getRawY();
                padDownRawX = padLastRawX;
                padDownRawY = padLastRawY;
                padDownTime = e.getEventTime();
                padMoved = false;
                padTwoFinger = false;
                padLongPressFired = false;
                if (dragMode) {
                    padMoved = true; // follow the finger immediately while dragging
                    if (!dragActive) {
                        beginDrag();
                    }
                } else {
                    handler.removeCallbacks(longPressRunnable);
                    handler.postDelayed(longPressRunnable, ViewConfiguration.getLongPressTimeout());
                }
                return true;
            }

            case MotionEvent.ACTION_POINTER_DOWN: {
                if (!dragMode && !padTwoFinger) {
                    padTwoFinger = true;
                    handler.removeCallbacks(longPressRunnable);
                    scrollAccum = 0f;
                    scrollLastY = averageY(e);
                }
                return true;
            }

            case MotionEvent.ACTION_MOVE: {
                if (padTwoFinger) {
                    if (e.getPointerCount() >= 2) {
                        float y = averageY(e);
                        scrollAccum += y - scrollLastY;
                        scrollLastY = y;
                        float step = dp(36);
                        if (scrollAccum > step) {
                            scroll(true);
                            scrollAccum = 0f;
                        } else if (scrollAccum < -step) {
                            scroll(false);
                            scrollAccum = 0f;
                        }
                    }
                    return true;
                }

                float rx = e.getRawX();
                float ry = e.getRawY();
                float dx = rx - padLastRawX;
                float dy = ry - padLastRawY;
                padLastRawX = rx;
                padLastRawY = ry;

                if (!padMoved) {
                    float slop = dp(5);
                    if (Math.abs(rx - padDownRawX) > slop || Math.abs(ry - padDownRawY) > slop) {
                        padMoved = true;
                        handler.removeCallbacks(longPressRunnable);
                    }
                }
                if (padMoved) {
                    float dist = (float) Math.sqrt(dx * dx + dy * dy);
                    float gain = BASE_GAIN + Math.min(2.2f, dist / 10f);
                    moveCursor(dx * gain, dy * gain);
                }
                return true;
            }

            case MotionEvent.ACTION_UP: {
                handler.removeCallbacks(longPressRunnable);
                if (dragMode) {
                    endDrag();
                } else if (!padTwoFinger && !padMoved && !padLongPressFired
                        && (e.getEventTime() - padDownTime) < 350L) {
                    click();
                }
                padTwoFinger = false;
                return true;
            }

            case MotionEvent.ACTION_CANCEL: {
                handler.removeCallbacks(longPressRunnable);
                if (dragMode) {
                    endDrag();
                }
                padTwoFinger = false;
                return true;
            }

            default:
                return true;
        }
    }

    // ----------------------------------------------------- gesture: click etc.

    /** Runs the action now, or shortly after if the panel first had to step aside. */
    private void runAtCursor(Runnable action) {
        if (!running) {
            return;
        }
        if (ensureCursorNotUnderPanel()) {
            handler.postDelayed(action, 150L);
        } else {
            action.run();
        }
    }

    private void click() {
        if (dragActive) {
            return;
        }
        runAtCursor(new Runnable() {
            @Override
            public void run() {
                doTap(1);
            }
        });
    }

    private void doubleClick() {
        if (dragActive) {
            return;
        }
        runAtCursor(new Runnable() {
            @Override
            public void run() {
                doTap(2);
            }
        });
    }

    /** Android has no right mouse button; the closest equivalent is a long press. */
    private void rightClick() {
        if (dragActive) {
            return;
        }
        runAtCursor(new Runnable() {
            @Override
            public void run() {
                doLongPress();
            }
        });
    }

    private void scroll(final boolean up) {
        if (dragActive) {
            return;
        }
        runAtCursor(new Runnable() {
            @Override
            public void run() {
                doScroll(up);
            }
        });
    }

    private void doTap(int count) {
        float x = cursorX;
        float y = cursorY;
        GestureDescription.Builder builder = new GestureDescription.Builder();
        for (int i = 0; i < count; i++) {
            Path p = new Path();
            p.moveTo(x, y);
            builder.addStroke(new GestureDescription.StrokeDescription(
                    p, i * (TAP_MS + DOUBLE_TAP_GAP_MS), TAP_MS));
        }
        safeDispatch(builder.build());
    }

    private void doLongPress() {
        Path p = new Path();
        p.moveTo(cursorX, cursorY);
        long duration = ViewConfiguration.getLongPressTimeout() + 250L;
        safeDispatch(singleStroke(p, duration));
    }

    /**
     * up == true  : show content above  (finger swipes downwards)
     * up == false : show content below  (finger swipes upwards)
     */
    private void doScroll(boolean up) {
        long now = SystemClock.uptimeMillis();
        if (now < scrollReadyAt) {
            return;
        }
        scrollReadyAt = now + SCROLL_MS + 80L;

        float dist = screenH * 0.25f;
        float edge = dp(40);
        float x = clamp(cursorX, dp(24), screenW - dp(24));
        float top = clamp(cursorY - dist / 2f, edge, Math.max(edge, screenH - edge - dist));
        float bottom = top + dist;

        Path p = new Path();
        if (up) {
            p.moveTo(x, top);
            p.lineTo(x, bottom);
        } else {
            p.moveTo(x, bottom);
            p.lineTo(x, top);
        }
        safeDispatch(singleStroke(p, SCROLL_MS));
    }

    private GestureDescription singleStroke(Path path, long duration) {
        GestureDescription.Builder builder = new GestureDescription.Builder();
        builder.addStroke(new GestureDescription.StrokeDescription(path, 0L, duration));
        return builder.build();
    }

    private void safeDispatch(GestureDescription gesture) {
        try {
            dispatchGesture(gesture, null, null);
        } catch (RuntimeException e) {
            // invalid gesture or service not ready: ignore
        }
    }

    // ------------------------------------------------------ gesture: drag & drop

    /**
     * Drag & drop.
     * API 26+: one long stroke that is continued in small steps, so the drag follows the finger
     *          (a short hold first, so launchers and lists pick the item up).
     * API 24-25: the whole drag is replayed as one swipe from start to end when the finger lifts.
     */
    private void beginDrag() {
        dragActive = true;
        dragEndRequested = false;
        dragStartX = cursorX;
        dragStartY = cursorY;
        dragLastX = cursorX;
        dragLastY = cursorY;
        if (Build.VERSION.SDK_INT >= 26) {
            startContinuedDrag();
        }
    }

    @TargetApi(26)
    private void startContinuedDrag() {
        Path p = new Path();
        p.moveTo(dragLastX, dragLastY);
        long hold = ViewConfiguration.getLongPressTimeout() + 150L;
        dragStroke = new GestureDescription.StrokeDescription(p, 0L, hold, true);
        dispatchDragStroke(dragStroke, true);
    }

    private void endDrag() {
        if (!dragActive) {
            return;
        }
        if (Build.VERSION.SDK_INT >= 26) {
            // the final segment is sent as soon as the current segment has finished
            dragEndRequested = true;
        } else {
            dragActive = false;
            endDragLegacy();
        }
    }

    private void endDragLegacy() {
        Path p = new Path();
        p.moveTo(dragStartX, dragStartY);
        float dx = cursorX - dragStartX;
        float dy = cursorY - dragStartY;
        float dist = (float) Math.sqrt(dx * dx + dy * dy);
        if (dist > 1f) {
            p.lineTo(cursorX, cursorY);
        }
        long duration = Math.max(500L, Math.min(1500L, (long) (dist * 1.5f)));
        safeDispatch(singleStroke(p, duration));
    }

    @TargetApi(26)
    private void onDragSegmentDone() {
        if (!dragActive || dragStroke == null) {
            return;
        }
        boolean last = dragEndRequested;
        float tx = cursorX;
        float ty = cursorY;

        Path p = new Path();
        p.moveTo(dragLastX, dragLastY);
        if (tx != dragLastX || ty != dragLastY) {
            p.lineTo(tx, ty);
        }

        GestureDescription.StrokeDescription next;
        try {
            next = dragStroke.continueStroke(p, 0L, DRAG_STEP_MS, !last);
        } catch (RuntimeException e) {
            dragActive = false;
            dragEndRequested = false;
            dragStroke = null;
            return;
        }
        dragLastX = tx;
        dragLastY = ty;

        if (last) {
            dragActive = false;
            dragEndRequested = false;
            dragStroke = null;
        } else {
            dragStroke = next;
        }
        dispatchDragStroke(next, !last);
    }

    private void dispatchDragStroke(GestureDescription.StrokeDescription stroke, boolean more) {
        boolean ok = false;
        try {
            GestureDescription.Builder builder = new GestureDescription.Builder();
            builder.addStroke(stroke);
            ok = dispatchGesture(builder.build(), more ? dragCallback : null, handler);
        } catch (RuntimeException e) {
            ok = false;
        }
        if (!ok) {
            dragActive = false;
            dragEndRequested = false;
            dragStroke = null;
        }
    }

    // ------------------------------------------------------------ cursor view

    private static class CursorView extends View {
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint outline = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path arrow = new Path();

        CursorView(Context context) {
            super(context);
            fill.setColor(Color.WHITE);
            fill.setStyle(Paint.Style.FILL);
            outline.setColor(Color.BLACK);
            outline.setStyle(Paint.Style.STROKE);
            outline.setStrokeWidth(3f);
        }

        void setDragging(boolean dragging) {
            fill.setColor(dragging ? Color.rgb(255, 160, 0) : Color.WHITE);
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float s = getWidth();
            arrow.reset();
            arrow.moveTo(2f, 2f);
            arrow.lineTo(2f, s * 0.85f);
            arrow.lineTo(s * 0.30f, s * 0.62f);
            arrow.lineTo(s * 0.52f, s * 0.95f);
            arrow.lineTo(s * 0.66f, s * 0.88f);
            arrow.lineTo(s * 0.45f, s * 0.56f);
            arrow.lineTo(s * 0.80f, s * 0.56f);
            arrow.close();
            canvas.drawPath(arrow, fill);
            canvas.drawPath(arrow, outline);
        }
    }
}
