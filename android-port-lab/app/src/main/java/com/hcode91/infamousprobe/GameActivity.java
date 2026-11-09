package com.hcode91.infamousprobe;

import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.Bundle;
import android.view.Choreographer;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;

/**
 * Game screen: shows the frames the runner publishes through EuBridge and sends
 * controller state back. Input: on-screen controls (multi-touch) and any
 * Bluetooth/USB gamepad. The picture comes from whatever RSX backend the runner
 * uses -- in this build upstream's CPU software preview, not a real renderer.
 */
public final class GameActivity extends Activity implements Choreographer.FrameCallback {
    private GameView view;
    private boolean running;

    // Physical gamepad state, merged with the touch controls.
    private int padButtons;
    private float padLx, padLy, padRx, padRy, padL2, padR2;
    private boolean padSeen;

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_FULLSCREEN);
        view = new GameView(this);
        setContentView(view);
        hideSystemUi();
    }

    private void hideSystemUi() {
        view.setSystemUiVisibility(View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                | View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideSystemUi();
    }

    @Override
    protected void onResume() {
        super.onResume();
        running = true;
        Choreographer.getInstance().postFrameCallback(this);
    }

    @Override
    protected void onPause() {
        super.onPause();
        running = false;
        // Release everything so the game does not see a stuck button.
        view.releaseAll();
        padButtons = 0;
        padLx = padLy = padRx = padRy = padL2 = padR2 = 0;
        pushPad();
    }

    @Override
    public void doFrame(long frameTimeNanos) {
        if (!running) return;
        view.pollFrame();
        Choreographer.getInstance().postFrameCallback(this);
    }

    void pushPad() {
        EuBridge b = EuBridge.current;
        if (b == null) return;
        int buttons = view.touchButtons() | padButtons;
        float lx = view.stick(0, true), ly = view.stick(0, false);
        float rx = view.stick(1, true), ry = view.stick(1, false);
        if (Math.abs(padLx) > Math.abs(lx)) lx = padLx;
        if (Math.abs(padLy) > Math.abs(ly)) ly = padLy;
        if (Math.abs(padRx) > Math.abs(rx)) rx = padRx;
        if (Math.abs(padRy) > Math.abs(ry)) ry = padRy;
        int l2 = Math.max((buttons & EuBridge.L2) != 0 ? 255 : 0, (int) (padL2 * 255));
        int r2 = Math.max((buttons & EuBridge.R2) != 0 ? 255 : 0, (int) (padR2 * 255));
        if (padL2 > 0.5f) buttons |= EuBridge.L2;
        if (padR2 > 0.5f) buttons |= EuBridge.R2;
        b.setPad(true, buttons, axis(lx), axis(ly), axis(rx), axis(ry), l2, r2);
    }

    /** -1..1 (Android: up/left negative) -> PS3 0..255, centre 128 (PS3: up = 0). */
    private static int axis(float v) {
        return Math.round((Math.max(-1f, Math.min(1f, v)) + 1f) * 127.5f);
    }

    // ---------------------------------------------------------------- gamepad
    private static int keyMask(int keyCode) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_BUTTON_A: return EuBridge.CROSS;
            case KeyEvent.KEYCODE_BUTTON_B: return EuBridge.CIRCLE;
            case KeyEvent.KEYCODE_BUTTON_X: return EuBridge.SQUARE;
            case KeyEvent.KEYCODE_BUTTON_Y: return EuBridge.TRIANGLE;
            case KeyEvent.KEYCODE_BUTTON_L1: return EuBridge.L1;
            case KeyEvent.KEYCODE_BUTTON_R1: return EuBridge.R1;
            case KeyEvent.KEYCODE_BUTTON_L2: return EuBridge.L2;
            case KeyEvent.KEYCODE_BUTTON_R2: return EuBridge.R2;
            case KeyEvent.KEYCODE_BUTTON_THUMBL: return EuBridge.L3;
            case KeyEvent.KEYCODE_BUTTON_THUMBR: return EuBridge.R3;
            case KeyEvent.KEYCODE_BUTTON_START: return EuBridge.START;
            case KeyEvent.KEYCODE_BUTTON_SELECT: return EuBridge.SELECT;
            case KeyEvent.KEYCODE_DPAD_UP: return EuBridge.UP;
            case KeyEvent.KEYCODE_DPAD_DOWN: return EuBridge.DOWN;
            case KeyEvent.KEYCODE_DPAD_LEFT: return EuBridge.LEFT;
            case KeyEvent.KEYCODE_DPAD_RIGHT: return EuBridge.RIGHT;
            default: return 0;
        }
    }

    private static boolean fromGamepad(InputDevice d) {
        if (d == null) return false;
        int s = d.getSources();
        return (s & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD
                || (s & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK;
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        int mask = keyMask(e.getKeyCode());
        if (mask != 0 && (fromGamepad(e.getDevice()) || e.getKeyCode() >= KeyEvent.KEYCODE_BUTTON_A)) {
            if (e.getAction() == KeyEvent.ACTION_DOWN) padButtons |= mask;
            else if (e.getAction() == KeyEvent.ACTION_UP) padButtons &= ~mask;
            padSeen = true;
            pushPad();
            view.invalidate();
            return true;
        }
        return super.dispatchKeyEvent(e);
    }

    @Override
    public boolean dispatchGenericMotionEvent(MotionEvent e) {
        if (fromGamepad(e.getDevice()) && e.getAction() == MotionEvent.ACTION_MOVE) {
            padLx = dz(e.getAxisValue(MotionEvent.AXIS_X));
            padLy = dz(e.getAxisValue(MotionEvent.AXIS_Y));
            padRx = dz(e.getAxisValue(MotionEvent.AXIS_Z));
            padRy = dz(e.getAxisValue(MotionEvent.AXIS_RZ));
            padL2 = Math.max(e.getAxisValue(MotionEvent.AXIS_LTRIGGER), e.getAxisValue(MotionEvent.AXIS_BRAKE));
            padR2 = Math.max(e.getAxisValue(MotionEvent.AXIS_RTRIGGER), e.getAxisValue(MotionEvent.AXIS_GAS));
            float hx = e.getAxisValue(MotionEvent.AXIS_HAT_X), hy = e.getAxisValue(MotionEvent.AXIS_HAT_Y);
            padButtons &= ~(EuBridge.UP | EuBridge.DOWN | EuBridge.LEFT | EuBridge.RIGHT);
            if (hx < -0.5f) padButtons |= EuBridge.LEFT;
            if (hx > 0.5f) padButtons |= EuBridge.RIGHT;
            if (hy < -0.5f) padButtons |= EuBridge.UP;
            if (hy > 0.5f) padButtons |= EuBridge.DOWN;
            padSeen = true;
            pushPad();
            return true;
        }
        return super.dispatchGenericMotionEvent(e);
    }

    private static float dz(float v) {
        return Math.abs(v) < 0.12f ? 0f : v;
    }

    // ---------------------------------------------------------------- view
    private final class GameView extends View {
        private static final int BTN = 0, DPAD = 1, STICK = 2, ACTION_LOG = 3, ACTION_HIDE = 4;

        private final class Control {
            final int kind, mask;
            final String label;
            final float fx, fy, fr; // centre as fraction of width/height, radius as fraction of height
            float cx, cy, r;
            int pointer = -1;
            int dpadMask;
            float sx, sy; // stick deflection -1..1

            Control(int kind, int mask, String label, float fx, float fy, float fr) {
                this.kind = kind;
                this.mask = mask;
                this.label = label;
                this.fx = fx;
                this.fy = fy;
                this.fr = fr;
            }
        }

        private final Control[] controls;
        private final Control leftStick, rightStick;
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint blit = new Paint(Paint.FILTER_BITMAP_FLAG);
        private final Rect src = new Rect();
        private final RectF dst = new RectF();
        private Bitmap frame;
        private int lastPublished = -1;
        private int shown;
        private boolean controlsVisible = true;

        GameView(Context c) {
            super(c);
            setBackgroundColor(Color.BLACK);
            setKeepScreenOn(true);
            leftStick = new Control(STICK, 0, "", 0.22f, 0.74f, 0.17f);
            rightStick = new Control(STICK, 0, "", 0.78f, 0.74f, 0.17f);
            float fb = 0.115f; // face-button spacing (fraction of height)
            controls = new Control[]{
                    new Control(BTN, EuBridge.L2, "L2", 0.05f, 0.09f, 0.065f),
                    new Control(BTN, EuBridge.L1, "L1", 0.15f, 0.09f, 0.065f),
                    new Control(BTN, EuBridge.R1, "R1", 0.85f, 0.09f, 0.065f),
                    new Control(BTN, EuBridge.R2, "R2", 0.95f, 0.09f, 0.065f),
                    new Control(DPAD, 0, "", 0.10f, 0.42f, 0.14f),
                    leftStick,
                    rightStick,
                    new Control(BTN, EuBridge.TRIANGLE, "△", 0.90f, 0.42f - fb, 0.06f),
                    new Control(BTN, EuBridge.CROSS, "✕", 0.90f, 0.42f + fb, 0.06f),
                    new Control(BTN, EuBridge.SQUARE, "□", 0.90f - fb * 0.5625f, 0.42f, 0.06f),
                    new Control(BTN, EuBridge.CIRCLE, "○", 0.90f + fb * 0.5625f, 0.42f, 0.06f),
                    new Control(BTN, EuBridge.L3, "L3", 0.36f, 0.92f, 0.045f),
                    new Control(BTN, EuBridge.SELECT, "SELECT", 0.44f, 0.92f, 0.05f),
                    new Control(BTN, EuBridge.START, "START", 0.56f, 0.92f, 0.05f),
                    new Control(BTN, EuBridge.R3, "R3", 0.64f, 0.92f, 0.045f),
                    new Control(ACTION_LOG, 0, "LOG", 0.44f, 0.06f, 0.045f),
                    new Control(ACTION_HIDE, 0, "PAD", 0.56f, 0.06f, 0.045f),
            };
            text.setColor(Color.WHITE);
            text.setTextAlign(Paint.Align.CENTER);
            stroke.setStyle(Paint.Style.STROKE);
            stroke.setColor(Color.argb(150, 255, 255, 255));
        }

        @Override
        protected void onSizeChanged(int w, int h, int ow, int oh) {
            for (Control c : controls) {
                c.cx = c.fx * w;
                c.cy = c.fy * h;
                c.r = c.fr * h;
            }
            stroke.setStrokeWidth(Math.max(2f, h / 300f));
        }

        void pollFrame() {
            EuBridge b = EuBridge.current;
            if (b == null) return;
            int n = b.framesPublished();
            if (n == lastPublished) return;
            int w = b.frameWidth(), h = b.frameHeight();
            if (w <= 0 || h <= 0 || w > EuBridge.MAX_W || h > EuBridge.MAX_H) return;
            if (frame == null || frame.getWidth() != w || frame.getHeight() != h) {
                frame = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            }
            if (b.readFrame(frame)) {
                lastPublished = n;
                shown++;
                invalidate();
            }
        }

        int touchButtons() {
            int m = 0;
            for (Control c : controls) {
                if (c.pointer < 0) continue;
                if (c.kind == BTN) m |= c.mask;
                else if (c.kind == DPAD) m |= c.dpadMask;
            }
            return m;
        }

        float stick(int which, boolean x) {
            Control c = which == 0 ? leftStick : rightStick;
            return x ? c.sx : c.sy;
        }

        void releaseAll() {
            for (Control c : controls) {
                c.pointer = -1;
                c.sx = c.sy = 0;
                c.dpadMask = 0;
            }
        }

        @Override
        protected void onDraw(Canvas canvas) {
            int w = getWidth(), h = getHeight();
            EuBridge b = EuBridge.current;
            if (frame != null && lastPublished > 0) {
                float scale = Math.min((float) w / frame.getWidth(), (float) h / frame.getHeight());
                float dw = frame.getWidth() * scale, dh = frame.getHeight() * scale;
                src.set(0, 0, frame.getWidth(), frame.getHeight());
                dst.set((w - dw) / 2f, (h - dh) / 2f, (w + dw) / 2f, (h + dh) / 2f);
                canvas.drawBitmap(frame, src, dst, blit);
            } else {
                text.setTextSize(h / 22f);
                text.setColor(Color.rgb(200, 210, 230));
                String msg = b == null ? "No boot running. Go back and tap BOOT."
                        : !b.runnerConnected() ? "Waiting for the game runner…"
                        : "Waiting for the game's first frame…";
                canvas.drawText(msg, w / 2f, h / 2f, text);
            }
            text.setTextSize(h / 34f);
            text.setColor(Color.argb(170, 255, 255, 255));
            text.setTextAlign(Paint.Align.LEFT);
            canvas.drawText("Software preview (CPU, no shaders) · frames "
                    + (b == null ? 0 : b.framesPublished())
                    + (padSeen ? " · gamepad" : ""), h / 40f, h - h / 40f, text);
            text.setTextAlign(Paint.Align.CENTER);
            for (Control c : controls) {
                if (!controlsVisible && c.kind != ACTION_HIDE) continue;
                drawControl(canvas, c);
            }
        }

        private void drawControl(Canvas canvas, Control c) {
            boolean down = c.pointer >= 0;
            fill.setColor(Color.argb(down ? 120 : 55, 255, 255, 255));
            canvas.drawCircle(c.cx, c.cy, c.r, fill);
            canvas.drawCircle(c.cx, c.cy, c.r, stroke);
            text.setColor(Color.argb(220, 255, 255, 255));
            if (c.kind == STICK) {
                fill.setColor(Color.argb(150, 255, 255, 255));
                canvas.drawCircle(c.cx + c.sx * c.r * 0.6f, c.cy + c.sy * c.r * 0.6f, c.r * 0.4f, fill);
            } else if (c.kind == DPAD) {
                text.setTextSize(c.r * 0.45f);
                float o = c.r * 0.62f, t = text.getTextSize() * 0.35f;
                canvas.drawText("▲", c.cx, c.cy - o + t, text);
                canvas.drawText("▼", c.cx, c.cy + o + t, text);
                canvas.drawText("◀", c.cx - o, c.cy + t, text);
                canvas.drawText("▶", c.cx + o, c.cy + t, text);
            } else {
                text.setTextSize(c.label.length() > 2 ? c.r * 0.45f : c.r * 0.8f);
                canvas.drawText(c.label, c.cx, c.cy + text.getTextSize() * 0.35f, text);
            }
        }

        private Control hit(float x, float y) {
            Control best = null;
            float bestD = Float.MAX_VALUE;
            for (Control c : controls) {
                if (!controlsVisible && c.kind != ACTION_HIDE) continue;
                if (c.pointer >= 0) continue;
                float dx = x - c.cx, dy = y - c.cy, d = (float) Math.sqrt(dx * dx + dy * dy);
                float reach = c.r * (c.kind == STICK ? 1.4f : 1.3f);
                if (d <= reach && d / c.r < bestD) {
                    best = c;
                    bestD = d / c.r;
                }
            }
            return best;
        }

        private void update(Control c, float x, float y) {
            float dx = (x - c.cx) / c.r, dy = (y - c.cy) / c.r;
            if (c.kind == STICK) {
                float len = (float) Math.sqrt(dx * dx + dy * dy);
                if (len > 1f) {
                    dx /= len;
                    dy /= len;
                }
                c.sx = dx;
                c.sy = dy;
            } else if (c.kind == DPAD) {
                int m = 0;
                if (Math.abs(dx) > 0.25f || Math.abs(dy) > 0.25f) {
                    double a = Math.toDegrees(Math.atan2(dy, dx)); // 0 = right, 90 = down
                    if (a > -67.5 && a < 67.5) m |= EuBridge.RIGHT;
                    if (a > 22.5 && a < 157.5) m |= EuBridge.DOWN;
                    if (a > 112.5 || a < -112.5) m |= EuBridge.LEFT;
                    if (a > -157.5 && a < -22.5) m |= EuBridge.UP;
                }
                c.dpadMask = m;
            }
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            int action = e.getActionMasked();
            int idx = e.getActionIndex();
            boolean changed = false;
            if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_POINTER_DOWN) {
                Control c = hit(e.getX(idx), e.getY(idx));
                if (c != null) {
                    if (c.kind == ACTION_LOG) {
                        finish();
                        return true;
                    }
                    if (c.kind == ACTION_HIDE) {
                        controlsVisible = !controlsVisible;
                        if (!controlsVisible) releaseAll();
                        invalidate();
                        return true;
                    }
                    c.pointer = e.getPointerId(idx);
                    update(c, e.getX(idx), e.getY(idx));
                    changed = true;
                }
            } else if (action == MotionEvent.ACTION_MOVE) {
                for (int i = 0; i < e.getPointerCount(); i++) {
                    int id = e.getPointerId(i);
                    for (Control c : controls) {
                        if (c.pointer == id && (c.kind == STICK || c.kind == DPAD)) {
                            update(c, e.getX(i), e.getY(i));
                            changed = true;
                        }
                    }
                }
            } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_POINTER_UP
                    || action == MotionEvent.ACTION_CANCEL) {
                int id = action == MotionEvent.ACTION_CANCEL ? -2 : e.getPointerId(idx);
                for (Control c : controls) {
                    if (c.pointer >= 0 && (id == -2 || c.pointer == id)) {
                        c.pointer = -1;
                        c.sx = c.sy = 0;
                        c.dpadMask = 0;
                        changed = true;
                    }
                }
            }
            if (changed) {
                pushPad();
                invalidate();
            }
            return true;
        }
    }
}
