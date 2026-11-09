package com.hcode91.infamousprobe;

import android.Manifest;
import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.DocumentsContract;
import android.provider.Settings;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;

/**
 * On-device boot test for the PRIVATE recompiled BCES00220 runner (libps3eu.so).
 *
 * What this screen does and does not claim:
 *  - It runs upstream inFAMOUSreborn's real game runner (main.cpp) as a child
 *    process, with the user's verified EBOOT.elf and (optionally) the user's own
 *    game folder as PS3_VFS_ROOT.
 *  - Every milestone is set ONLY from a line the runner itself prints. Process
 *    creation is reported separately and is never treated as a boot.
 *  - This build has no Android RSX renderer: upstream falls back to its headless
 *    "null" backend, so a guest display flip can be detected but nothing is drawn.
 */
public final class EuBootActivity extends Activity {
    static final String EBOOT_SHA256 =
            "095d851432f3b55179cd725b46838ffedb3e5eb9f73889e52b6f11f3d3f494fd";
    private static final long EBOOT_SIZE = 8039416L;
    private static final int REQ_ELF = 201;
    private static final int REQ_TREE = 202;
    private static final int REQ_EXPORT = 203;
    private static final int REQ_LEGACY_PERMS = 204;
    private static final long LOG_FILE_CAP = 64L * 1024 * 1024;
    private static final int TAIL_LINES = 400;

    // Milestones, in boot order. Each is set only from runner output.
    private static final String[] M_NAMES = {
            "Native runner process created (not a boot by itself)",
            "Runner main() reached (dynamic linking OK)",
            "PS3 guest address space reserved (4 GB)",
            "Game ELF loaded into guest memory",
            "Game filesystem root set (VFS)",
            "Recompiled PPU functions, HLE and syscalls registered; guest entry dispatched",
            "RSX backend initialised (CPU software preview, not a GPU renderer)",
            "Game created guest threads",
            "Game opened a file from its data folder",
            "Guest submitted a display flip",
            "First guest frame sent to the Android screen (software preview)",
            "Android controller connected to the game (port 0)",
    };
    private static final int M_PROC = 0, M_MAIN = 1, M_MEM = 2, M_ELF = 3, M_VFS = 4,
            M_ENTRY = 5, M_RSX = 6, M_THREADS = 7, M_FILES = 8, M_FLIP = 9, M_SCREEN = 10, M_PAD = 11;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Object lock = new Object();
    private final ArrayDeque<String> tail = new ArrayDeque<>();
    private final String[] evidence = new String[M_NAMES.length];
    private final int[] state = new int[M_NAMES.length]; // 0 pending, 1 done, -1 failed
    private int threads, filesOk, filesFail, unresolvedNids, unregistered, flips;
    private String fatal;
    private long logBytes;
    private boolean dirty, logTruncated;

    private SharedPreferences prefs;
    private TextView buildInfo, elfStatus, dataStatus, milestones, logView, runState;
    private EditText pathEdit;
    private CheckBox verbose, upstreamEnv, lean, lowCpu, openScreen;
    private Button bootButton, stopButton;
    private volatile Process child;
    private File logFile;
    private long bootStartMs;

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        prefs = getSharedPreferences("eu_boot", MODE_PRIVATE);
        getWindow().setStatusBarColor(Color.rgb(9, 12, 23));
        getWindow().setNavigationBarColor(Color.rgb(9, 12, 23));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(28, 34, 28, 28);
        root.setBackgroundColor(Color.rgb(12, 17, 29));

        root.addView(text("inFAMOUS (BCES00220) — real game boot test", 21, Color.WHITE, true));
        root.addView(text("Runs the recompiled game runner on this phone and reports only what "
                + "the runner itself prints. Not playable: there is no Android renderer yet.",
                14, Color.rgb(191, 204, 224), false));
        buildInfo = text("", 13, Color.rgb(150, 170, 200), false);
        buildInfo.setTypeface(Typeface.MONOSPACE);
        root.addView(buildInfo);

        root.addView(heading("1 · Game executable (decrypted EBOOT.elf)"));
        elfStatus = text("", 14, Color.rgb(220, 226, 240), false);
        root.addView(elfStatus);
        Button pickElf = button("Select my EBOOT.elf");
        pickElf.setOnClickListener(v -> {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.setType("*/*");
            i.addCategory(Intent.CATEGORY_OPENABLE);
            startActivityForResult(i, REQ_ELF);
        });
        root.addView(pickElf);

        root.addView(heading("2 · Game data folder (contains PS3_GAME)"));
        dataStatus = text("", 14, Color.rgb(220, 226, 240), false);
        root.addView(dataStatus);
        Button grant = button("Allow file access (needed to read the game folder)");
        grant.setOnClickListener(v -> requestFileAccess());
        root.addView(grant);
        Button pickTree = button("Choose game folder");
        pickTree.setOnClickListener(v -> {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
            startActivityForResult(i, REQ_TREE);
        });
        root.addView(pickTree);
        pathEdit = new EditText(this);
        pathEdit.setHint("or type a path, e.g. /storage/emulated/0/PS3/inFAMOUS");
        pathEdit.setTextColor(Color.WHITE);
        pathEdit.setHintTextColor(Color.rgb(120, 130, 150));
        pathEdit.setSingleLine(true);
        root.addView(pathEdit);
        Button usePath = button("Use typed path");
        usePath.setOnClickListener(v -> setGameRoot(pathEdit.getText().toString().trim()));
        root.addView(usePath);
        Button clear = button("Boot without game data (ELF only)");
        clear.setOnClickListener(v -> {
            prefs.edit().remove("vfs_root").apply();
            refreshStatus();
        });
        root.addView(clear);

        root.addView(heading("3 · Boot"));
        verbose = new CheckBox(this);
        verbose.setText("Verbose runtime logging (PS3_VERBOSE=1)");
        verbose.setTextColor(Color.WHITE);
        root.addView(verbose);
        upstreamEnv = new CheckBox(this);
        upstreamEnv.setText("Use upstream's launch settings (recommended)");
        upstreamEnv.setTextColor(Color.WHITE);
        upstreamEnv.setChecked(true);
        root.addView(upstreamEnv);
        lean = new CheckBox(this);
        lean.setText("Phone CPU scheduling (upstream lean mode, recommended)");
        lean.setTextColor(Color.WHITE);
        lean.setChecked(true);
        root.addView(lean);
        lowCpu = new CheckBox(this);
        lowCpu.setText("Low-CPU preset (upstream LOW_CPU: lower animation/draw detail)");
        lowCpu.setTextColor(Color.WHITE);
        root.addView(lowCpu);
        openScreen = new CheckBox(this);
        openScreen.setText("Open the game screen when booting");
        openScreen.setTextColor(Color.WHITE);
        openScreen.setChecked(true);
        root.addView(openScreen);
        bootButton = button("BOOT inFAMOUS");
        bootButton.setOnClickListener(v -> startBoot());
        root.addView(bootButton);
        stopButton = button("Stop");
        stopButton.setEnabled(false);
        stopButton.setOnClickListener(v -> {
            Process p = child;
            if (p != null) p.destroyForcibly();
        });
        root.addView(stopButton);
        Button screen = button("Show game screen + controls");
        screen.setOnClickListener(v -> startActivity(new Intent(this, GameActivity.class)));
        root.addView(screen);
        runState = text("", 15, Color.rgb(255, 214, 120), true);
        root.addView(runState);

        root.addView(heading("Boot milestones"));
        milestones = text("", 14, Color.rgb(200, 240, 210), false);
        milestones.setTypeface(Typeface.MONOSPACE);
        root.addView(milestones);

        LinearLayout logButtons = new LinearLayout(this);
        logButtons.setOrientation(LinearLayout.HORIZONTAL);
        Button copy = button("Copy log");
        copy.setOnClickListener(v -> copyLog());
        Button export = button("Save log…");
        export.setOnClickListener(v -> exportLog());
        Button share = button("Share log");
        share.setOnClickListener(v -> shareLog());
        logButtons.addView(copy);
        logButtons.addView(export);
        logButtons.addView(share);
        root.addView(logButtons);

        root.addView(heading("Runner output (last " + TAIL_LINES + " lines; full log is saved)"));
        logView = text("", 11, Color.rgb(180, 239, 204), false);
        logView.setTypeface(Typeface.MONOSPACE);
        logView.setTextIsSelectable(true);
        root.addView(logView);

        ScrollView scroller = new ScrollView(this);
        scroller.setFillViewport(true);
        scroller.addView(root);
        setContentView(scroller);

        loadBuildInfo();
        String last = prefs.getString("last_log", null);
        if (last != null && new File(last).isFile()) logFile = new File(last);
        resetMilestones();
        refreshStatus();
        render();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshStatus(); // the user may be returning from the file-access settings page
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        Process p = child;
        if (p != null && !isChangingConfigurations()) p.destroyForcibly();
    }

    // ------------------------------------------------------------------ UI helpers
    private TextView text(String s, int sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        t.setPadding(0, 6, 0, 6);
        return t;
    }

    private TextView heading(String s) {
        TextView t = text(s, 17, Color.WHITE, true);
        t.setPadding(0, 28, 0, 6);
        return t;
    }

    private Button button(String s) {
        Button b = new Button(this);
        b.setText(s);
        b.setAllCaps(false);
        return b;
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    private File workDir() {
        File d = new File(getFilesDir(), "eu");
        //noinspection ResultOfMethodCallIgnored
        d.mkdirs();
        return d;
    }

    private File runner() {
        return new File(getApplicationInfo().nativeLibraryDir, "libps3eu.so");
    }

    private void loadBuildInfo() {
        StringBuilder sb = new StringBuilder();
        File r = runner();
        sb.append("Runner: ").append(r.isFile()
                ? "libps3eu.so " + (r.length() / (1024 * 1024)) + " MB"
                : "NOT PACKAGED (public build — private CI adds it)").append('\n');
        try (InputStream in = getAssets().open("eu_build_info.json")) {
            JSONObject o = new JSONObject(new String(readAll(in, 1 << 20), StandardCharsets.UTF_8));
            Iterator<String> keys = o.keys();
            while (keys.hasNext()) {
                String k = keys.next();
                sb.append(k).append(": ").append(o.opt(k)).append('\n');
            }
        } catch (Exception ignored) {
            sb.append("Build info: not bundled\n");
        }
        buildInfo.setText(sb.toString().trim());
    }

    // ------------------------------------------------------------------ status
    private File verifiedElf() {
        File copy = new File(workDir(), "EBOOT.elf");
        if (!copy.isFile() || copy.length() != EBOOT_SIZE) return null;
        String key = copy.length() + ":" + copy.lastModified();
        if (key.equals(prefs.getString("elf_ok", ""))) return copy;
        try {
            if (EBOOT_SHA256.equals(sha256(copy))) {
                prefs.edit().putString("elf_ok", key).apply();
                return copy;
            }
        } catch (IOException ignored) {
            // fall through
        }
        return null;
    }

    private boolean hasFileAccess() {
        if (Build.VERSION.SDK_INT >= 30) return Environment.isExternalStorageManager();
        return checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void requestFileAccess() {
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                startActivity(new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        Uri.parse("package:" + getPackageName())));
            } catch (Exception e) {
                startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
            }
        } else {
            requestPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQ_LEGACY_PERMS);
        }
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        super.onRequestPermissionsResult(code, perms, results);
        refreshStatus();
    }

    /** Folder check result, or null when the folder is usable. */
    private String checkGameRoot(File root) {
        if (!hasFileAccess()) return "file access not granted yet";
        if (!root.isDirectory()) return "folder not found or not readable: " + root;
        File usrdir = new File(root, "PS3_GAME/USRDIR");
        if (!usrdir.isDirectory()) return "no PS3_GAME/USRDIR inside " + root;
        String[] names = usrdir.list();
        if (names == null) return "USRDIR exists but cannot be listed (access denied)";
        if (names.length == 0) return "USRDIR is empty";
        return null;
    }

    private void refreshStatus() {
        File elf = verifiedElf();
        File inFolder = gameRootElf();
        if (elf != null) {
            elfStatus.setText("✔ Verified BCES00220 EBOOT.elf (SHA-256 match)");
        } else if (inFolder != null) {
            elfStatus.setText("✔ Using verified EBOOT.elf found in the game folder");
        } else {
            elfStatus.setText("Not selected yet. Pick your decrypted EBOOT.elf (8,039,416 bytes).");
        }
        String rootPath = prefs.getString("vfs_root", null);
        if (rootPath == null) {
            dataStatus.setText((hasFileAccess() ? "" : "File access: not granted.\n")
                    + "No game folder set. The game can still start, but it will stop at its "
                    + "first data-file read.");
        } else {
            File root = new File(rootPath);
            String problem = checkGameRoot(root);
            if (problem == null) {
                String[] names = new File(root, "PS3_GAME/USRDIR").list();
                dataStatus.setText("✔ " + root + "\nPS3_GAME/USRDIR entries: "
                        + (names == null ? 0 : names.length));
            } else {
                dataStatus.setText("✖ " + root + "\n" + problem);
            }
        }
        bootButton.setEnabled(child == null && runner().isFile()
                && (elf != null || inFolder != null));
    }

    private File gameRootElf() {
        String rootPath = prefs.getString("vfs_root", null);
        if (rootPath == null || !hasFileAccess()) return null;
        File f = new File(rootPath, "PS3_GAME/USRDIR/EBOOT.elf");
        if (!f.isFile() || f.length() != EBOOT_SIZE) return null;
        String key = f.getAbsolutePath() + ":" + f.lastModified();
        if (key.equals(prefs.getString("folder_elf_ok", ""))) return f;
        try {
            if (EBOOT_SHA256.equals(sha256(f))) {
                prefs.edit().putString("folder_elf_ok", key).apply();
                return f;
            }
        } catch (IOException ignored) {
            // not usable
        }
        return null;
    }

    private void setGameRoot(String path) {
        if (path == null || path.isEmpty()) return;
        File f = new File(path);
        // Accept the JB folder itself, PS3_GAME, or USRDIR.
        if (f.getName().equalsIgnoreCase("USRDIR") && f.getParentFile() != null
                && f.getParentFile().getParentFile() != null) {
            f = f.getParentFile().getParentFile();
        } else if (f.getName().equalsIgnoreCase("PS3_GAME") && f.getParentFile() != null) {
            f = f.getParentFile();
        }
        prefs.edit().putString("vfs_root", f.getAbsolutePath()).apply();
        pathEdit.setText(f.getAbsolutePath());
        refreshStatus();
    }

    /** primary:Games/inFAMOUS -> /storage/emulated/0/Games/inFAMOUS (needs all-files access to read). */
    private static String treeUriToPath(Uri tree) {
        String id = DocumentsContract.getTreeDocumentId(tree);
        int colon = id.indexOf(':');
        String volume = colon < 0 ? id : id.substring(0, colon);
        String rel = colon < 0 ? "" : id.substring(colon + 1);
        String base;
        if ("primary".equalsIgnoreCase(volume)) {
            base = Environment.getExternalStorageDirectory().getAbsolutePath();
        } else if ("home".equalsIgnoreCase(volume)) {
            base = Environment.getExternalStorageDirectory().getAbsolutePath() + "/Documents";
        } else if (id.startsWith("raw:")) {
            return id.substring(4);
        } else {
            base = "/storage/" + volume;
        }
        return rel.isEmpty() ? base : base + "/" + rel;
    }

    // ------------------------------------------------------------------ results
    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (res != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        if (req == REQ_ELF) {
            importElf(uri);
        } else if (req == REQ_TREE) {
            try {
                setGameRoot(treeUriToPath(uri));
                if (!hasFileAccess()) toast("Now tap 'Allow file access' so the game can read it");
            } catch (Exception e) {
                toast("Could not resolve that folder: " + e.getMessage());
            }
        } else if (req == REQ_EXPORT) {
            writeLogTo(uri);
        }
    }

    private void importElf(Uri uri) {
        elfStatus.setText("Verifying…");
        new Thread(() -> {
            String msg;
            File dst = new File(workDir(), "EBOOT.elf");
            File tmp = new File(workDir(), "EBOOT.elf.part");
            try (InputStream in = getContentResolver().openInputStream(uri);
                 FileOutputStream out = new FileOutputStream(tmp, false)) {
                if (in == null) throw new IOException("cannot open the selected file");
                MessageDigest md = MessageDigest.getInstance("SHA-256");
                byte[] buf = new byte[1 << 16];
                long total = 0;
                for (int n; (n = in.read(buf)) != -1; ) {
                    total += n;
                    if (total > 64L * 1024 * 1024) throw new IOException("file is far too large");
                    md.update(buf, 0, n);
                    out.write(buf, 0, n);
                }
                String hex = hex(md.digest());
                if (!EBOOT_SHA256.equals(hex)) {
                    throw new IOException("not the supported European EBOOT.elf (SHA-256 " + hex
                            + "). An encrypted EBOOT.BIN must be decrypted first.");
                }
                out.getFD().sync();
                if (!tmp.renameTo(dst)) throw new IOException("could not store the verified copy");
                prefs.edit().putString("elf_ok", dst.length() + ":" + dst.lastModified()).apply();
                msg = null;
            } catch (Exception e) {
                //noinspection ResultOfMethodCallIgnored
                tmp.delete();
                msg = e.getMessage();
            }
            final String m = msg;
            ui.post(() -> {
                refreshStatus();
                if (m != null) elfStatus.setText("✖ " + m);
            });
        }, "eu-elf-import").start();
    }

    // ------------------------------------------------------------------ boot
    private void resetMilestones() {
        synchronized (lock) {
            for (int i = 0; i < state.length; i++) {
                state[i] = 0;
                evidence[i] = null;
            }
            threads = filesOk = filesFail = unresolvedNids = unregistered = flips = 0;
            fatal = null;
            tail.clear();
            logBytes = 0;
            logTruncated = false;
            dirty = true;
        }
    }

    private void startBoot() {
        if (child != null) return;
        File elf = verifiedElf();
        if (elf == null) elf = gameRootElf();
        File exe = runner();
        if (elf == null || !exe.isFile()) {
            refreshStatus();
            return;
        }
        if (!exe.canExecute()) {
            runState.setText("✖ libps3eu.so was extracted without execute permission");
            return;
        }
        String vfs = prefs.getString("vfs_root", null);
        if (vfs != null && checkGameRoot(new File(vfs)) != null) {
            runState.setText("✖ Game folder not usable: " + checkGameRoot(new File(vfs))
                    + "\nFix it, or choose 'Boot without game data'.");
            return;
        }
        resetMilestones();
        String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date());
        logFile = new File(workDir(), "boot-" + stamp + ".log");
        prefs.edit().putString("last_log", logFile.getAbsolutePath()).apply();
        pruneOldLogs();

        ProcessBuilder pb = new ProcessBuilder(exe.getAbsolutePath(), elf.getAbsolutePath());
        pb.directory(workDir());
        pb.redirectErrorStream(true);
        Map<String, String> env = pb.environment();
        StringBuilder header = new StringBuilder();
        header.append("# inFAMOUS Android boot test ").append(stamp).append('\n');
        header.append("# device: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
                .append(" Android ").append(Build.VERSION.RELEASE)
                .append(" (API ").append(Build.VERSION.SDK_INT).append(")\n");
        header.append("# runner: ").append(exe.getAbsolutePath()).append('\n');
        header.append("# elf: ").append(elf.getAbsolutePath()).append('\n');
        if (upstreamEnv.isChecked()) {
            int n = applyUpstreamEnv(env);
            header.append("# upstream launch env: ").append(n).append(" values\n");
        }
        // Overrides that make the boot observable from Android.
        env.put("PS3_STDOUT_UNBUFFERED", "1"); // stdout is a pipe: default is a 256 KB buffer
        env.put("GCM_FLIPCOUNT", "1");          // prints "[flip] N at T ms" for each guest flip
        env.put("PS3_VERBOSE", verbose.isChecked() ? "1" : "0");
        env.put("LD_LIBRARY_PATH", getApplicationInfo().nativeLibraryDir);
        // Upstream enables lean SPU scheduling automatically only on Windows PCs
        // with < 17 CPU threads; off Windows its auto mode is always off.
        if (lean.isChecked()) env.put("V241A_LEAN", "1");
        if (lowCpu.isChecked()) env.put("LOW_CPU", "1");
        // inFamous' immediate-mode movie quad carries its UVs in attribute 1
        // (upstream rsx_null_backend.c note) -- for the software preview.
        env.put("NULLSW_UVATTR", "1");
        header.append("# lean=").append(lean.isChecked()).append(" low_cpu=")
                .append(lowCpu.isChecked()).append('\n');
        try {
            EuBridge.current = EuBridge.create(new File(workDir(), "bridge.bin"));
            env.put("PS3_ANDROID_BRIDGE", EuBridge.current.file.getAbsolutePath());
        } catch (IOException e) {
            EuBridge.current = null;
            header.append("# screen/controller bridge unavailable: ").append(e.getMessage()).append('\n');
        }
        env.put("HOME", workDir().getAbsolutePath());
        env.put("TMPDIR", getCacheDir().getAbsolutePath());
        if (vfs != null) {
            env.put("PS3_VFS_ROOT", vfs);
            header.append("# PS3_VFS_ROOT=").append(vfs).append('\n');
        } else {
            env.remove("PS3_VFS_ROOT");
            header.append("# no game data folder (ELF-only boot)\n");
        }
        header.append('\n');

        final Process p;
        try {
            p = pb.start();
        } catch (IOException e) {
            synchronized (lock) {
                state[M_PROC] = -1;
                evidence[M_PROC] = e.getMessage();
                fatal = "Could not start the runner: " + e.getMessage()
                        + (String.valueOf(e.getMessage()).contains("Permission denied")
                        ? " (Android refused to execute it — SELinux/noexec)" : "");
                dirty = true;
            }
            render();
            return;
        }
        child = p;
        bootStartMs = System.currentTimeMillis();
        synchronized (lock) {
            state[M_PROC] = 1;
            evidence[M_PROC] = "pid started";
            dirty = true;
        }
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        bootButton.setEnabled(false);
        stopButton.setEnabled(true);
        runState.setText("Running…");
        final String hdr = header.toString();
        new Thread(() -> pump(p, hdr), "eu-boot-log").start();
        ui.postDelayed(ticker, 300);
        if (openScreen.isChecked()) startActivity(new Intent(this, GameActivity.class));
    }

    private int applyUpstreamEnv(Map<String, String> env) {
        try (InputStream in = getAssets().open("eu_launch_env.json")) {
            JSONObject o = new JSONObject(new String(readAll(in, 1 << 20), StandardCharsets.UTF_8));
            Iterator<String> keys = o.keys();
            int n = 0;
            while (keys.hasNext()) {
                String k = keys.next();
                env.put(k, o.optString(k));
                n++;
            }
            return n;
        } catch (Exception e) {
            return 0;
        }
    }

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            render();
            if (child != null) ui.postDelayed(this, 300);
        }
    };

    private void pump(Process p, String header) {
        try (FileOutputStream log = new FileOutputStream(logFile, false);
             BufferedReader r = new BufferedReader(
                     new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8), 1 << 16)) {
            writeLog(log, header);
            String line;
            while ((line = r.readLine()) != null) {
                writeLog(log, line + "\n");
                synchronized (lock) {
                    parse(line);
                    tail.addLast(line);
                    while (tail.size() > TAIL_LINES) tail.removeFirst();
                    dirty = true;
                }
            }
            int code = p.waitFor();
            String verdict = exitVerdict(code);
            writeLog(log, "\n# runner exited: " + verdict + " after "
                    + (System.currentTimeMillis() - bootStartMs) / 1000 + " s\n");
            synchronized (lock) {
                if (fatal == null) fatal = verdict;
                dirty = true;
            }
        } catch (Exception e) {
            synchronized (lock) {
                fatal = "Log capture failed: " + e;
                dirty = true;
            }
        } finally {
            child = null;
            ui.post(() -> {
                getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                stopButton.setEnabled(false);
                refreshStatus();
                render();
            });
        }
    }

    private void writeLog(FileOutputStream log, String s) throws IOException {
        byte[] b = s.getBytes(StandardCharsets.UTF_8);
        synchronized (lock) {
            if (logBytes + b.length > LOG_FILE_CAP) {
                if (!logTruncated) {
                    log.write("\n# --- log file cap reached; later lines are not saved ---\n"
                            .getBytes(StandardCharsets.UTF_8));
                    logTruncated = true;
                }
                return;
            }
            logBytes += b.length;
        }
        log.write(b);
    }

    private static String exitVerdict(int code) {
        if (code == 0) return "exit code 0";
        if (code > 128 && code < 160) {
            int sig = code - 128;
            String name;
            switch (sig) {
                case 4: name = "SIGILL (illegal instruction)"; break;
                case 6: name = "SIGABRT (abort)"; break;
                case 7: name = "SIGBUS (bus error)"; break;
                case 9: name = "SIGKILL (killed: Stop button, or Android low-memory killer)"; break;
                case 11: name = "SIGSEGV (segmentation fault)"; break;
                case 15: name = "SIGTERM"; break;
                default: name = "signal " + sig;
            }
            return "killed by " + name;
        }
        return "exit code " + code;
    }

    /** Milestones come only from lines printed by upstream main.cpp / the runtime. */
    private void parse(String l) {
        if (l.startsWith("CANNOT LINK EXECUTABLE") || l.contains("library \"") && l.contains("not found")) {
            fatal = "Missing native dependency: " + l;
            mark(M_MAIN, -1, l);
            return;
        }
        if (l.startsWith("=== ps3recomp game runner ===")) mark(M_MAIN, 1, l);
        else if (l.startsWith("ERROR: could not allocate the guest address space")) {
            mark(M_MEM, -1, l);
            fatal = "Guest memory reservation failed (needs 8 GB of virtual address space)";
        } else if (l.startsWith("[ppu] loaded ") && l.contains("PT_LOAD")) {
            mark(M_MEM, 1, "implied: the loader writes into the reserved space");
            mark(M_ELF, 1, l);
        } else if (l.startsWith("ERROR: could not load ")) {
            mark(M_MEM, 1, "implied: reservation succeeded before the load");
            mark(M_ELF, -1, l);
        } else if (l.startsWith("[boot] VFS root:")) mark(M_VFS, 1, l);
        else if (l.startsWith("[boot] dispatching entry OPD")) mark(M_ENTRY, 1, l);
        else if (l.startsWith("[rsx] ") && l.contains("backend init")) {
            mark(M_RSX, l.contains("FAILED") ? -1 : 1, l);
        } else if (l.startsWith("[SYS] sys_ppu_thread_create")) {
            threads++;
            mark(M_THREADS, 1, threads + " thread(s); latest: " + l);
        } else if (l.startsWith("[fs] open FAIL") || l.startsWith("[fs] SdataOpen FAIL")) {
            filesFail++;
            if (state[M_FILES] == 0) evidence[M_FILES] = "only failures so far; latest: " + l;
        } else if (l.startsWith("[fs] open '") || l.startsWith("[fs] SdataOpen '")) {
            if (l.contains("-> fd")) {
                filesOk++;
                mark(M_FILES, 1, filesOk + " file(s) opened; latest: " + l);
            }
        } else if (l.startsWith("[EU bridge] first frame published")) {
            mark(M_SCREEN, 1, l);
        } else if (l.startsWith("[EU bridge] Android controller connected")) {
            mark(M_PAD, 1, l);
        } else if (l.startsWith("[EU bridge] cannot open") || l.startsWith("[EU bridge] bad header")
                || l.startsWith("[EU bridge] mmap failed") || l.contains("[EU bridge] ") && l.contains("too small")) {
            mark(M_SCREEN, -1, l);
        } else if (l.startsWith("[flip] ")) {
            flips++;
            mark(M_FLIP, 1, flips + " flip(s); latest: " + l);
        } else if (l.contains("UNRESOLVED NID") || l.startsWith("[hle] unresolved NID")) {
            unresolvedNids++;
        } else if (l.contains("not registered")) {
            unregistered++;
        } else if (l.startsWith("[boot] ppu_run returned")) {
            if (fatal == null) fatal = "Guest entry function returned: " + l;
        }
    }

    private void mark(int m, int s, String why) {
        if (state[m] == 1 && s == 1 && m != M_THREADS && m != M_FILES && m != M_FLIP) return;
        state[m] = s;
        evidence[m] = why.length() > 220 ? why.substring(0, 220) + "…" : why;
    }

    private void render() {
        String ms, tailText, status;
        synchronized (lock) {
            dirty = false;
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < M_NAMES.length; i++) {
                String mark = state[i] == 1 ? "✔" : state[i] < 0 ? "✖" : "·";
                sb.append(mark).append(' ').append(M_NAMES[i]).append('\n');
                String ev = evidence[i];
                if (ev != null) sb.append("     ").append(ev).append('\n');
            }
            sb.append("\nCounters: guest threads ").append(threads)
                    .append(" · files opened ").append(filesOk)
                    .append(" · file open failures ").append(filesFail)
                    .append(" · unresolved firmware NIDs ").append(unresolvedNids)
                    .append(" · unregistered call targets ").append(unregistered)
                    .append(" · flips ").append(flips);
            if (fatal != null) sb.append("\n\nResult: ").append(fatal);
            ms = sb.toString();
            StringBuilder t = new StringBuilder();
            for (String s : tail) t.append(s).append('\n');
            tailText = t.toString();
            status = child != null
                    ? "Running… " + (System.currentTimeMillis() - bootStartMs) / 1000 + " s · log "
                    + (logBytes / 1024) + " KB"
                    : (fatal != null ? "Finished: " + fatal : "");
        }
        milestones.setText(ms);
        logView.setText(tailText.isEmpty() ? "(no output yet)" : tailText);
        runState.setText(status);
    }

    // ------------------------------------------------------------------ logs
    private String summary() {
        synchronized (lock) {
            return milestones.getText().toString();
        }
    }

    private void copyLog() {
        StringBuilder sb = new StringBuilder(summary()).append("\n\n");
        if (logFile != null && logFile.isFile()) {
            sb.append(readTail(logFile, 256 * 1024));
        }
        ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (cm != null) {
            cm.setPrimaryClip(ClipData.newPlainText("inFAMOUS boot log", sb.toString()));
            toast("Summary + last 256 KB of the log copied");
        }
    }

    private void exportLog() {
        if (logFile == null || !logFile.isFile()) {
            toast("No log yet");
            return;
        }
        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("text/plain");
        i.putExtra(Intent.EXTRA_TITLE, logFile.getName());
        startActivityForResult(i, REQ_EXPORT);
    }

    private void writeLogTo(Uri uri) {
        new Thread(() -> {
            String msg;
            try (OutputStream out = getContentResolver().openOutputStream(uri);
                 FileInputStream in = new FileInputStream(logFile)) {
                if (out == null) throw new IOException("cannot write there");
                out.write((summary() + "\n\n").getBytes(StandardCharsets.UTF_8));
                byte[] buf = new byte[1 << 16];
                for (int n; (n = in.read(buf)) != -1; ) out.write(buf, 0, n);
                msg = "Full log saved";
            } catch (Exception e) {
                msg = "Save failed: " + e.getMessage();
            }
            final String m = msg;
            ui.post(() -> toast(m));
        }, "eu-log-export").start();
    }

    private void shareLog() {
        if (logFile == null || !logFile.isFile()) {
            toast("No log yet");
            return;
        }
        Intent i = new Intent(Intent.ACTION_SEND);
        i.setType("text/plain");
        i.putExtra(Intent.EXTRA_SUBJECT, "inFAMOUS Android boot log");
        i.putExtra(Intent.EXTRA_TEXT, summary() + "\n\n" + readTail(logFile, 192 * 1024));
        startActivity(Intent.createChooser(i, "Share boot log"));
    }

    private void pruneOldLogs() {
        File[] logs = workDir().listFiles((d, n) -> n.startsWith("boot-") && n.endsWith(".log"));
        if (logs == null || logs.length <= 10) return;
        java.util.Arrays.sort(logs, (a, b) -> Long.compare(a.lastModified(), b.lastModified()));
        for (int i = 0; i < logs.length - 10; i++) {
            //noinspection ResultOfMethodCallIgnored
            logs[i].delete();
        }
    }

    private static String readTail(File f, int max) {
        try (java.io.RandomAccessFile raf = new java.io.RandomAccessFile(f, "r")) {
            long len = raf.length();
            long start = Math.max(0, len - max);
            raf.seek(start);
            byte[] b = new byte[(int) (len - start)];
            raf.readFully(b);
            String s = new String(b, StandardCharsets.UTF_8);
            return start > 0 ? "…(earlier output omitted; use Save log for the full file)\n" + s : s;
        } catch (IOException e) {
            return "(could not read log: " + e.getMessage() + ")";
        }
    }

    // ------------------------------------------------------------------ util
    private static byte[] readAll(InputStream in, int cap) throws IOException {
        java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
        byte[] b = new byte[8192];
        for (int n; (n = in.read(b)) != -1; ) {
            if (bo.size() + n > cap) throw new IOException("too large");
            bo.write(b, 0, n);
        }
        return bo.toByteArray();
    }

    private static String sha256(File f) throws IOException {
        try (InputStream in = new FileInputStream(f)) {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] b = new byte[1 << 16];
            for (int n; (n = in.read(b)) != -1; ) md.update(b, 0, n);
            return hex(md.digest());
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IOException(e);
        }
    }

    private static String hex(byte[] d) {
        StringBuilder sb = new StringBuilder();
        for (byte x : d) sb.append(String.format(Locale.US, "%02x", x & 255));
        return sb.toString();
    }
}
