package com.hcode91.infamousprobe;

import android.app.Activity;
import android.os.Bundle;
import android.graphics.Color;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.view.Gravity;
import java.util.concurrent.atomic.AtomicBoolean;

public final class VulkanActivity extends Activity implements SurfaceHolder.Callback {
    private SurfaceView display;
    private TextView status;
    private Thread rendererThread;
    private final AtomicBoolean running = new AtomicBoolean(false);

    private static native String renderVulkan(Surface surface, int frames);
    private static native void cancelVulkan();

    @Override public void onCreate(Bundle savedState) {
        super.onCreate(savedState);
        getWindow().getDecorView().setSystemUiVisibility(
            android.view.View.SYSTEM_UI_FLAG_FULLSCREEN
            | android.view.View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        display = new SurfaceView(this);
        root.addView(display, new FrameLayout.LayoutParams(-1, -1));
        status = new TextView(this);
        status.setText("Starting native Vulkan...\nThis is a GPU test, not PS3 RSX rendering.");
        status.setTextSize(15);
        status.setTextColor(Color.WHITE);
        status.setShadowLayer(4, 1, 1, Color.BLACK);
        status.setPadding(22, 28, 22, 22);
        FrameLayout.LayoutParams labelParams = new FrameLayout.LayoutParams(-1, -2, Gravity.TOP);
        root.addView(status, labelParams);
        setContentView(root);
        display.getHolder().addCallback(this);
    }

    @Override public void surfaceCreated(SurfaceHolder holder) {
        if (running.getAndSet(true)) return;
        rendererThread = new Thread(() -> {
            String outcome;
            try {
                outcome = renderVulkan(holder.getSurface(), 300);
            } catch (Throwable error) {
                outcome = "Vulkan renderer failed: " + error.toString();
            }
            final String finalOutcome = outcome;
            runOnUiThread(() -> status.setText(finalOutcome + "\nTap Back to return."));
            running.set(false);
        }, "native-vulkan-present");
        rendererThread.start();
    }
    @Override public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {}
    @Override public void surfaceDestroyed(SurfaceHolder holder) {
        cancelVulkan();
        if (rendererThread != null && rendererThread.isAlive()) {
            try { rendererThread.join(1500); }
            catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
        }
    }
    @Override protected void onDestroy() {
        cancelVulkan();
        super.onDestroy();
    }
    static { System.loadLibrary("infamousprobe"); }
}
