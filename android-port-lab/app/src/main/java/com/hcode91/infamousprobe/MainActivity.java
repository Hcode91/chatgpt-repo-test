package com.hcode91.infamousprobe;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;

public final class MainActivity extends Activity {
    private static final int PICK_EXECUTABLE = 101;
    private TextView output;
    private String startup;
    private int testRunCount = 0;
    private static native String nativeProbe();

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(Color.rgb(9, 12, 23));
        getWindow().setNavigationBarColor(Color.rgb(9, 12, 23));
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(28, 34, 28, 28);
        root.setBackgroundColor(Color.rgb(12, 17, 29));

        TextView heading = new TextView(this);
        heading.setText("inFAMOUS | Android Port Lab");
        heading.setTextColor(Color.WHITE);
        heading.setTextSize(23);
        heading.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        root.addView(heading);

        TextView note = new TextView(this);
        note.setText("\nARM64 + Vulkan + ps3recomp runtime probe\nNOT a playable game. No PS3 code or assets are bundled.\n");
        note.setTextColor(Color.rgb(191, 204, 224));
        note.setTextSize(15);
        root.addView(note);

        Button run = new Button(this);
        run.setText("Run native runtime tests");
        root.addView(run);
        run.setOnClickListener(v -> showProbe());

        Button pick = new Button(this);
        pick.setText("Inspect my EBOOT.elf");
        root.addView(pick);
        pick.setOnClickListener(v -> {
            Intent choose = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            choose.setType("*/*");
            choose.addCategory(Intent.CATEGORY_OPENABLE);
            startActivityForResult(choose, PICK_EXECUTABLE);
        });

        output = new TextView(this);
        output.setTextColor(Color.rgb(180, 239, 204));
        output.setTextSize(14);
        output.setTypeface(Typeface.MONOSPACE);
        output.setPadding(0, 20, 0, 12);
        root.addView(output);

        ScrollView scroller = new ScrollView(this);
        scroller.setFillViewport(true);
        scroller.addView(root);
        setContentView(scroller);
        output.setText("Ready. Tap RUN NATIVE RUNTIME TESTS to check your device.\n");
    }

    private void showProbe() {
        testRunCount++;
        String completedAt = new java.text.SimpleDateFormat("HH:mm:ss", Locale.getDefault())
                .format(new java.util.Date());
        try {
            startup = nativeProbe();
        } catch (Throwable e) {
            startup = "Native library ERROR: " + e.getClass().getSimpleName() + ": " + e.getMessage();
        }
        output.setText("TEST RUN #" + testRunCount + " FINISHED AT " + completedAt
                + "\n\n" + startup
                + "\n\nNext: port the real PPU boot scaffold and RSX renderer. This app does not execute inFAMOUS.\n");
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != PICK_EXECUTABLE || resultCode != RESULT_OK || data == null) return;
        Uri uri = data.getData();
        if (uri == null) return;
        byte[] header = new byte[64];
        int got = 0;
        try (InputStream in = getContentResolver().openInputStream(uri)) {
            if (in == null) throw new IOException("Cannot open selected file");
            while (got < header.length) {
                int n = in.read(header, got, header.length - got);
                if (n < 0) break;
                got += n;
            }
            boolean elf = got >= 20 && header[0] == 0x7f && header[1] == 'E'
                    && header[2] == 'L' && header[3] == 'F';
            boolean is64 = elf && header[4] == 2;
            boolean bigEndian = elf && header[5] == 2;
            int machine = got >= 20 ? (((header[18] & 255) << 8) | (header[19] & 255)) : -1;
            boolean ppu = is64 && bigEndian && machine == 21;
            String report = "Selected: " + uri.getLastPathSegment()
                  + "\nELF header: " + (elf ? "yes" : "no")
                  + "\nELF64: " + is64
                  + "\nBig endian: " + bigEndian
                  + "\ne_machine: " + (machine >= 0 ? String.format(Locale.US, "0x%04X", machine) : "unknown")
                  + "\nPowerPC64 PS3-format candidate: " + ppu
                  + "\n\nInspection only. The file stays on your device and is not uploaded or executed.";
            output.setText(startup + "\n\n" + report);
        } catch (IOException e) {
            output.setText(startup + "\n\nFile error: " + e.getMessage());
        }
    }

    static {
        System.loadLibrary("infamousprobe");
    }
}
