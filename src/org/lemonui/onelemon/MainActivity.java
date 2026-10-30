package org.lemonui.onelemon;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {

    private TextView tvRootStatus;
    private TextView tvOsStatus;
    private TextView tvImageStatus;
    private TextView tvHeaderSubtitle;
    private TextView tvConsoleLog;
    private TextView tvLogStatus;
    private TextView tvDownloadDesc;
    private TextView tvDownloadProgress;
    private ProgressBar pbDownload;    

    private Button btnDownloadLineage;
    private Button btnDeleteLineage;
    private Button btnBootLineage;
    private Button btnReturnOneUI;
    private Button btnBackupLauncher;
    private Button btnRestoreLauncher;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private boolean isRootGranted = false;
    private String suBinary = "su";

    private static final String STAGE_DIR = "/data/local/tmp/lineage_stage";
    private static final String SYSTEM_RAW = STAGE_DIR + "/system.raw";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        tvRootStatus = findViewById(R.id.tv_root_status);
        tvOsStatus = findViewById(R.id.tv_os_status);
        tvImageStatus = findViewById(R.id.tv_image_status);
        tvHeaderSubtitle = findViewById(R.id.tv_header_subtitle);
        tvDownloadDesc = findViewById(R.id.tv_download_desc);
        tvDownloadProgress = findViewById(R.id.tv_download_progress);
        pbDownload = findViewById(R.id.pb_download);
        tvConsoleLog = findViewById(R.id.tv_console_log);
        tvLogStatus = findViewById(R.id.tv_log_status);

        btnDownloadLineage = findViewById(R.id.btn_download_lineage);
        btnDeleteLineage = findViewById(R.id.btn_delete_lineage);
        btnBootLineage = findViewById(R.id.btn_boot_lineage);
        btnReturnOneUI = findViewById(R.id.btn_return_oneui);
        btnBackupLauncher = findViewById(R.id.btn_backup_launcher);
        btnRestoreLauncher = findViewById(R.id.btn_restore_launcher);
        tvDownloadDesc.setText("Choose image.");

        extractAssets();
        checkRootAndSystem();

        btnDownloadLineage.setOnClickListener(v -> downloadLineage());
        btnDeleteLineage.setOnClickListener(v -> confirmDeleteLineage());
        btnBootLineage.setOnClickListener(v -> bootLineage());
        btnReturnOneUI.setOnClickListener(v -> returnToOneUI());
        btnBackupLauncher.setOnClickListener(v -> backupLauncher());
        btnRestoreLauncher.setOnClickListener(v -> restoreLauncher());
    }

    @Override
    protected void onResume() {
        super.onResume();
        checkRootAndSystem();
    }

    private void appendLog(final String text) {
        mainHandler.post(() -> {
            if (tvConsoleLog != null) {
                tvConsoleLog.append(text + "\n");
                final int scrollAmount = tvConsoleLog.getLayout() != null ?
                        tvConsoleLog.getLayout().getLineTop(tvConsoleLog.getLineCount()) - tvConsoleLog.getHeight() : 0;
                if (scrollAmount > 0) {
                    tvConsoleLog.scrollTo(0, scrollAmount);
                }
            }
        });
    }

    // Runs a root shell command with stdout+stderr merged and continuously drained.
    // Process.waitFor() deadlocks forever if the child fills its stdout/stderr pipe
    // (~64KB) without anyone reading it - e.g. "rm -rf" spewing a "Device or resource
    // busy" line per file when rootfs still has stale bind/loop mounts underneath it.
    private int runRootCmdDrained(String cmd) {
        try {
            ProcessBuilder pb = new ProcessBuilder(suBinary, "-c", cmd);
            pb.redirectErrorStream(true);
            Process p = pb.start();
            BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()));
            while (reader.readLine() != null) {
                // drain only; callers that need the output use their own reader.
            }
            return p.waitFor();
        } catch (Exception e) {
            return -1;
        }
    }

    private void setLogStatus(final String status) {
        mainHandler.post(() -> {
            if (tvLogStatus != null) {
                tvLogStatus.setText(status);
            }
        });
    }

    private void checkRootAndSystem() {
        executor.execute(() -> {
            // Attempt auto-activation of ksud if present
            try {
                File dfKsud = new File("/data/user_de/0/df.root/ksud");
                if (dfKsud.exists() && dfKsud.canExecute()) {
                    Runtime.getRuntime().exec(new String[]{dfKsud.getAbsolutePath(), "install"}).waitFor();
                }
            } catch (Exception ignored) {}

            String[] candidatePaths = {"/system/bin/su", "/data/adb/ksu/bin/su", "/system/xbin/su", "su"};
            boolean foundRoot = false;

            for (String path : candidatePaths) {
                try {
                    Process p = Runtime.getRuntime().exec(new String[]{path, "-c", "id"});
                    BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()));
                    String line = reader.readLine();
                    int exitCode = p.waitFor();

                    if (exitCode == 0 && line != null && line.contains("uid=0")) {
                        foundRoot = true;
                        suBinary = path;
                        break;
                    }
                } catch (Exception ignored) {}
            }

            isRootGranted = foundRoot;
            if (isRootGranted) {
                extractAssets();
            }
            mainHandler.post(() -> {
                if (isRootGranted) {
                    tvRootStatus.setText("✓ Root: KernelSU Active (uid=0)");
                    tvRootStatus.setTextColor(Color.parseColor("#A5D6A7"));
                } else {
                    tvRootStatus.setText("✗ Root: Access Denied (KernelSU Required)");
                    tvRootStatus.setTextColor(Color.parseColor("#FFB4AB"));
                }
            });

            // Check active userspace
            try {
                Process pOs = Runtime.getRuntime().exec(new String[]{suBinary, "-c", "getprop ro.lineage.version"});
                BufferedReader osReader = new BufferedReader(new InputStreamReader(pOs.getInputStream()));
                String osVer = osReader.readLine();
                pOs.waitFor();

                mainHandler.post(() -> {
                    if (osVer != null && !osVer.trim().isEmpty()) {
                        tvOsStatus.setText("Current Userspace: Lemon UI " + osVer.trim());
                        tvOsStatus.setTextColor(Color.parseColor("#78D9EC"));
                        if (tvHeaderSubtitle != null) {
                            tvHeaderSubtitle.setText("Dual Userspace Switcher • Lemon UI");
                        }
                    } else {
                        tvOsStatus.setText("Current Userspace: Samsung One UI (Stock)");
                        tvOsStatus.setTextColor(Color.parseColor("#FFD54F"));
                        if (tvHeaderSubtitle != null) {
                            tvHeaderSubtitle.setText("Dual Userspace Switcher • One UI");
                        }
                    }
                });
            } catch (Exception e) {
                mainHandler.post(() -> tvOsStatus.setText("Current Userspace: Samsung One UI"));
            }

            checkImageStatus();
        });
    }

    private void checkImageStatus() {
        executor.execute(() -> {
            try {
                Process p = Runtime.getRuntime().exec(new String[]{suBinary, "-c", "[ -f " + SYSTEM_RAW + " ] && ls -lh " + SYSTEM_RAW + " || echo 'MISSING'"});
                BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()));
                String out = reader.readLine();
                p.waitFor();

                mainHandler.post(() -> {
                    if (out != null && !out.contains("MISSING") && !out.trim().isEmpty()) {
                        tvImageStatus.setText("Image: Installed & Ready (" + out.trim().replaceAll("\\s+", " ") + ")");
                        tvImageStatus.setTextColor(Color.parseColor("#A5D6A7"));
                        btnDownloadLineage.setText("Choose another img");
                        btnBootLineage.setEnabled(true);
                        btnBootLineage.setAlpha(1.0f);
                    } else {
                        tvImageStatus.setText("Image: Not Installed (Missing system.raw)");
                        tvImageStatus.setTextColor(Color.parseColor("#FFB4AB"));
                        btnDownloadLineage.setText("Choose ROM image");
                        btnBootLineage.setEnabled(false);
                        btnBootLineage.setAlpha(0.5f);
                    }
                });
            } catch (Exception e) {
                mainHandler.post(() -> tvImageStatus.setText("Image Check Error: " + e.getMessage()));
            }
        });
    }

    private void extractAssets() {
        executor.execute(() -> {
            String[] assets = {
                "switch_to_lineage.sh", "return_to_oneui.sh",
                "master_prepare.sh", "master_inner.sh",
                "zygote_exec", "clean_apex_info_list.xml",
                "services_patched.jar", "framework_patched.jar",
                "7zz", "PermissionController_patched.apk"
            };
            File filesDir = getFilesDir();
            for (String asset : assets) {
                File target = new File(filesDir, asset);
                if (target.exists() && target.length() > 0 && asset.endsWith(".jar")) {
                    continue; // Skip re-extracting large jars if already present
                }
                try (InputStream in = getAssets().open(asset);
                     OutputStream out = new FileOutputStream(target)) {
                    byte[] buffer = new byte[32768];
                    int read;
                    while ((read = in.read(buffer)) != -1) {
                        out.write(buffer, 0, read);
                    }
                    target.setExecutable(true, false);
                    target.setReadable(true, false);
                } catch (Exception e) {
                    appendLog("Error unpacking " + asset + ": " + e.getMessage());
                }
            }
            if (isRootGranted) {
                try {
                    Runtime.getRuntime().exec(new String[]{
                        suBinary, "-c",
                        "mkdir -p " + STAGE_DIR + " && " +
                        "for f in " + filesDir.getAbsolutePath() + "/*; do " +
                        "  base=$(basename \"$f\"); " +
                        "  cp -f \"$f\" \"" + STAGE_DIR + "/$base\"; " +
                        "done && " +
                        "cp -f " + filesDir.getAbsolutePath() + "/7zz /data/local/tmp/7zz 2>/dev/null; " +
                        "chmod 755 " + STAGE_DIR + "/*.sh " + STAGE_DIR + "/zygote_exec " + STAGE_DIR + "/7zz /data/local/tmp/7zz 2>/dev/null"
                    }).waitFor();
                } catch (Exception ignored) {}
            }
            appendLog("OneLemon engine assets unpacked & verified.");
        });
    }

    // Unmounts the container rootfs and detaches any loop device backed by a file
    // under STAGE_DIR. Bounded retry loop (not an unbounded "while mount|grep" loop)
    // so it can never hang forever, and retries losetup -d once since EBUSY is often
    // transient right after pkill -9 (kernel hasn't finished releasing the dying
    // container's private mount namespace yet).
    private String containerUnmountCmd() {
        String rootfs = STAGE_DIR + "/rootfs";
        return "BB=$(which busybox 2>/dev/null || echo /data/adb/ksu/bin/busybox); " +
               "[ -x \"$BB\" ] || BB=$(find /data/adb /system /data -name busybox 2>/dev/null | head -n 1); " +
               "umount -l " + rootfs + "/apex/* 2>/dev/null || true; " +
               "umount -l " + rootfs + "/* 2>/dev/null || true; " +
               "umount -l " + rootfs + " 2>/dev/null || true; " +
               "n=0; while [ $n -lt 50 ] && mount | grep -q '" + rootfs + "'; do " +
               "  umount -l " + rootfs + " 2>/dev/null || true; " +
               "  n=$((n+1)); sleep 0.1; " +
               "done; " +
               "if [ -x \"$BB\" ]; then " +
               "  for l in $($BB losetup -a | grep -F '" + STAGE_DIR + "' | cut -d: -f1); do " +
               "    $BB losetup -d \"$l\" 2>/dev/null || { sleep 0.3; $BB losetup -d \"$l\" 2>/dev/null || true; }; " +
               "  done; " +
               "fi";
    }

    private String wipeUserDataCmd() {
        return containerUnmountCmd() + "; " +
               "rm -rf " + STAGE_DIR + "/data " + STAGE_DIR + "/rootfs; mkdir -p " + STAGE_DIR + "/rootfs";
    }

    private void confirmDeleteLineage() {
        new AlertDialog.Builder(this)
            .setTitle("Delete Lemon UI Files?")
            .setMessage("This will cleanly unmount the container, detach loop devices, and remove system.raw to free up ~3.5GB. You can reinstall anytime.")
            .setPositiveButton("Delete", (dialog, which) -> deleteLineage())
            .setNegativeButton("Cancel", null)
            .show();
    }

    private void deleteLineage() {
        executor.execute(() -> {
            setLogStatus("Cleaning…");
            appendLog("\n[CLEANUP] Stopping running container processes & unmounting...");
            try {
                // Only kill zygote64/surfaceflinger/vold/system_server by name if the
                // LineageOS container's rootfs is actually mounted. If it isn't (e.g.
                // we're sitting on stock One UI with no switch in progress), those
                // pidof lookups resolve to the HOST's own real daemons - killing them
                // crashes/reboots the device instead of cleaning up a container.
                String cleanupCmd =
                    "if mount | grep -q '" + STAGE_DIR + "/rootfs '; then " +
                    "  pkill -9 -f 'ctl.stop zygote' 2>/dev/null || true; " +
                    "  pkill -9 -f 'ui_activator' 2>/dev/null || true; " +
                    "  pkill -9 -f 'zygote_exec' 2>/dev/null || true; " +
                    "  pkill -9 -f 'system_server' 2>/dev/null || true; " +
                    "  pkill -9 -f org.lineageos 2>/dev/null || true; " +
                    "  pkill -9 -x zygote64 2>/dev/null || true; " +
                    "  kill -9 $(pidof surfaceflinger) 2>/dev/null || true; " +
                    "  kill -9 $(pidof bootanimation) 2>/dev/null || true; " +
                    // Matched by the container's distinctive vold launch flags, never
                    // "pidof vold" bare - that would kill the HOST's real vold too if
                    // both happened to be alive, crashing the device (confirmed live).
                    "  pkill -9 -f 'blkid_context=u:r:blkid:s0' 2>/dev/null || true; " +
                    "  sleep 0.3; " +
                    "fi; " +
                    containerUnmountCmd() + "; " +
                    // If a loop device is still stuck busy (namespace teardown lagging),
                    // truncating frees the backing blocks immediately even though rm -f
                    // alone would leave them held open by that loop device.
                    "truncate -s 0 " + SYSTEM_RAW + " 2>/dev/null || true; " +
                    "rm -f " + SYSTEM_RAW + " " + STAGE_DIR + "/*.img " + STAGE_DIR + "/*.7z " + STAGE_DIR + "/*.img.tmp; " +
                    "rm -rf " + STAGE_DIR + "/data " + STAGE_DIR + "/rootfs; " +
                    "mkdir -p " + STAGE_DIR + "/rootfs; " +
                    "if [ -f " + SYSTEM_RAW + " ]; then echo 'WARNING: system.raw still present!'; else echo 'Container unmounted, data wiped, and storage freed.'; fi";

                Process p = new ProcessBuilder(suBinary, "-c", cleanupCmd).redirectErrorStream(true).start();
                BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()));
                String line;
                boolean stillPresent = false;
                while ((line = reader.readLine()) != null) {
                    appendLog("  " + line);
                    if (line.contains("WARNING: system.raw still present")) {
                        stillPresent = true;
                    }
                }
                p.waitFor();
                if (stillPresent) {
                    appendLog("✗ system.raw could not be fully removed (a loop device is still holding it busy). Try again, or reboot and retry.");
                    setLogStatus("Error");
                } else {
                    appendLog("✓ Lemon UI files deleted cleanly. All loop mounts detached!");
                    setLogStatus("Idle");
                }
                checkImageStatus();
            } catch (Exception e) {
                appendLog("Delete failed: " + e.getMessage());
                setLogStatus("Error");
            }
        });
    }

    private void bootLineage() {
        if (!isRootGranted) {
            appendLog("ERROR: Root privilege required to boot Lemon UI!");
            return;
        }
        appendLog("\n[START] Preparing Lemon UI takeover...");
        setLogStatus("Switching…");
        executor.execute(() -> {
            try {
                File filesDir = getFilesDir();
                String syncCmd = "for f in " + filesDir.getAbsolutePath() + "/*; do " +
                                 "  base=$(basename \"$f\"); " +
                                 "  cp -f \"$f\" \"" + STAGE_DIR + "/$base\"; " +
                                 "done && " +
                                 "chmod 755 " + STAGE_DIR + "/*.sh " + STAGE_DIR + "/zygote_exec " + STAGE_DIR + "/7zz";
                Runtime.getRuntime().exec(new String[]{suBinary, "-c", syncCmd}).waitFor();

                String stageScript = STAGE_DIR + "/switch_to_lineage.sh";
                String cmd = "stageScript=" + STAGE_DIR + "/switch_to_lineage.sh; " +
                             "chmod 755 $stageScript; " +
                             "BB=$(which busybox 2>/dev/null || echo /data/adb/ksu/bin/busybox); " +
                             "[ -x \"$BB\" ] || BB=$(find /data/adb /system /data -name busybox 2>/dev/null | head -n 1); " +
                             "if [ -n \"$BB\" ] && [ -x \"$BB\" ]; then " +
                             "  $BB setsid /system/bin/sh $stageScript </dev/null >" + STAGE_DIR + "/takeover_app.log 2>&1 & " +
                             "else " +
                             "  nohup /system/bin/sh $stageScript </dev/null >" + STAGE_DIR + "/takeover_app.log 2>&1 & " +
                             "fi";
                Process p = Runtime.getRuntime().exec(new String[]{suBinary, "-c", cmd});
                p.waitFor();
                appendLog("Takeover daemon spawned successfully. Display switching in progress...");
            } catch (Exception e) {
                appendLog("Launch error: " + e.getMessage());
                setLogStatus("Error");
            }
        });
    }

    private void returnToOneUI() {
        if (!isRootGranted) {
            appendLog("ERROR: Root privilege required to restore One UI!");
            return;
        }
        appendLog("\n[START] Restoring One UI and resetting display...");
        setLogStatus("Restoring…");
        executor.execute(() -> {
            try {
                String stageScript = STAGE_DIR + "/return_to_oneui.sh";
                String cmd = "stageScript=" + STAGE_DIR + "/return_to_oneui.sh; " +
                             "chmod 755 $stageScript; " +
                             "BB=$(which busybox 2>/dev/null || echo /data/adb/ksu/bin/busybox); " +
                             "[ -x \"$BB\" ] || BB=$(find /data/adb /system /data -name busybox 2>/dev/null | head -n 1); " +
                             "if [ -n \"$BB\" ] && [ -x \"$BB\" ]; then " +
                             "  $BB setsid /system/bin/sh $stageScript </dev/null >" + STAGE_DIR + "/restore_app.log 2>&1 & " +
                             "else " +
                             "  nohup /system/bin/sh $stageScript </dev/null >" + STAGE_DIR + "/restore_app.log 2>&1 & " +
                             "fi";
                Process p = Runtime.getRuntime().exec(new String[]{suBinary, "-c", cmd});
                p.waitFor();
                appendLog("One UI restore triggered successfully.");
            } catch (Exception e) {
                appendLog("Restore error: " + e.getMessage());
                setLogStatus("Error");
            }
        });
    }

    private void backupLauncher() {
        executor.execute(() -> {
            setLogStatus("Backing up…");
            appendLog("\nBacking up One UI Home Screen database...");
            try {
                String cmd = "mkdir -p /data/local/tmp/lineage_backups && " +
                             "cp -a /data/user/0/com.sec.android.app.launcher/databases/* /data/local/tmp/lineage_backups/ 2>/dev/null && " +
                             "ls -la /data/local/tmp/lineage_backups/";
                Process p = Runtime.getRuntime().exec(new String[]{suBinary, "-c", cmd});
                BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()));
                String line;
                while ((line = reader.readLine()) != null) {
                    appendLog("  " + line);
                }
                p.waitFor();
                appendLog("✓ Home screen database successfully snapshotted!");
                setLogStatus("Idle");
            } catch (Exception e) {
                appendLog("Backup failed: " + e.getMessage());
                setLogStatus("Error");
            }
        });
    }

    private void restoreLauncher() {
        executor.execute(() -> {
            setLogStatus("Restoring…");
            appendLog("\nRestoring One UI Home Screen layout...");
            try {
                String cmd = "if [ -d /data/local/tmp/lineage_backups ]; then " +
                             "LAUNCHER_DATA=/data/user/0/com.sec.android.app.launcher; " +
                             "L_UID=$(stat -c %u $LAUNCHER_DATA 2>/dev/null || echo 10160); " +
                             "L_GID=$(stat -c %g $LAUNCHER_DATA 2>/dev/null || echo 10160); " +
                             "cp -a /data/local/tmp/lineage_backups/* $LAUNCHER_DATA/databases/ && " +
                             "chown -R ${L_UID}:${L_GID} $LAUNCHER_DATA/databases && " +
                             "chmod 771 $LAUNCHER_DATA/databases && " +
                             "chmod 660 $LAUNCHER_DATA/databases/* && " +
                             "am force-stop com.sec.android.app.launcher && " +
                             "am start -a android.intent.action.MAIN -c android.intent.category.HOME && " +
                             "echo 'Restored launcher successfully with UID '${L_UID}; else echo 'No backup found!'; fi";
                Process p = Runtime.getRuntime().exec(new String[]{suBinary, "-c", cmd});
                BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()));
                String line;
                while ((line = reader.readLine()) != null) {
                    appendLog("  " + line);
                }
                p.waitFor();
                setLogStatus("Idle");
            } catch (Exception e) {
                appendLog("Restore failed: " + e.getMessage());
                setLogStatus("Error");
            }
        });
    }
    private static final int REQ_PICK_IMAGE = 1001;

private void downloadLineage() {
    if (!isRootGranted) {
        appendLog("ERROR: Root privileges required to set up Lemon UI!");
        return;
    }
    Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
    i.addCategory(Intent.CATEGORY_OPENABLE);
    i.setType("*/*");
    startActivityForResult(i, REQ_PICK_IMAGE);
}

@Override
protected void onActivityResult(int requestCode, int resultCode, Intent data) {
    super.onActivityResult(requestCode, resultCode, data);
    if (requestCode == REQ_PICK_IMAGE && resultCode == RESULT_OK
            && data != null && data.getData() != null) {
        importImage(data.getData());
    }
}

private String queryName(Uri uri) {
    try (Cursor c = getContentResolver().query(uri, null, null, null, null)) {
        if (c != null && c.moveToFirst()) {
            int idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
            if (idx >= 0) return c.getString(idx);
        }
    } catch (Exception ignored) {}
    return "arquivo";
}

private long querySize(Uri uri) {
    try (Cursor c = getContentResolver().query(uri, null, null, null, null)) {
        if (c != null && c.moveToFirst()) {
            int idx = c.getColumnIndex(OpenableColumns.SIZE);
            if (idx >= 0 && !c.isNull(idx)) return c.getLong(idx);
        }
    } catch (Exception ignored) {}
    return -1;
}

private void copyUriToFile(Uri uri, File target, long totalBytes) throws Exception {
    mainHandler.post(() -> {
        pbDownload.setMax(100);
        pbDownload.setProgress(0);
    });
    long start = System.currentTimeMillis();
    long last = 0, cur = 0;
    try (InputStream in = getContentResolver().openInputStream(uri);
         OutputStream out = new FileOutputStream(target)) {
        if (in == null) throw new Exception("Failed to open the file");
        byte[] buf = new byte[65536];
        int r;
        while ((r = in.read(buf)) != -1) {
            out.write(buf, 0, r);
            cur += r;
            long now = System.currentTimeMillis();
            if (now - last > 250) {
                last = now;
                final int pct = totalBytes > 0 ? (int) Math.min(100, cur * 100 / totalBytes) : 0;
                final double mbs = (cur / 1048576.0) / Math.max(1, (now - start) / 1000);
                final String s = String.format("Copying: %d MB%s • %.1f MB/s",
                        cur / 1048576,
                        totalBytes > 0 ? " / " + (totalBytes / 1048576) + " MB (" + pct + "%)" : "",
                        mbs);
                mainHandler.post(() -> {
                    pbDownload.setProgress(pct);
                    tvDownloadProgress.setText(s);
                });
            }
        }
        out.flush();
    }
}

// 0 = 7z, 1 = Android sparse, 2 = raw/outro
private int sniffFormat(File f) throws Exception {
    byte[] h = new byte[6];
    try (InputStream in = new java.io.FileInputStream(f)) {
        if (in.read(h) < 6) return 2;
    }
    if ((h[0] & 0xFF) == '7' && h[1] == 'z' && (h[2] & 0xFF) == 0xBC
            && (h[3] & 0xFF) == 0xAF && h[4] == 0x27 && h[5] == 0x1C) return 0;
    if ((h[0] & 0xFF) == 0x3A && (h[1] & 0xFF) == 0xFF
            && (h[2] & 0xFF) == 0x26 && (h[3] & 0xFF) == 0xED) return 1;
    return 2;
}

private void importImage(final Uri uri) {
    executor.execute(() -> {
        final File cached = new File(getCacheDir(), "lineage_import.bin");
        setLogStatus("Importing…");
        mainHandler.post(() -> {
            pbDownload.setVisibility(View.VISIBLE);
            pbDownload.setIndeterminate(false);
            pbDownload.setProgress(0);
            tvDownloadProgress.setVisibility(View.VISIBLE);
            tvDownloadProgress.setText("Preparando…");
            btnDownloadLineage.setEnabled(false);
        });
        try {
            String name = queryName(uri);
            long size = querySize(uri);
            appendLog("\n[SETUP] Chosen file: " + name
                    + (size > 0 ? " (" + (size / 1048576) + " MB)" : ""));

            runRootCmdDrained("mkdir -p " + STAGE_DIR + " && chmod 777 " + STAGE_DIR);
            appendLog("[SETUP] Wiping existing userdata for a fresh OOBE...");
            runRootCmdDrained(wipeUserDataCmd());

            copyUriToFile(uri, cached, size);
            appendLog("✓ Finished copying (" + (cached.length() / 1048576) + " MB)");

            int fmt = sniffFormat(cached);
            if (fmt == 1) {
                throw new Exception("Sparse image detected, convert it to raw Ext4/EROFS before installing.");
            }

            setLogStatus("Unpacking…");
            String cmd;
            if (fmt == 0) {
                mainHandler.post(() -> {
                    pbDownload.setIndeterminate(true);
                    tvDownloadProgress.setText("Decompressing image with ARM64 7-Zip…");
                });
                appendLog("Compressed file detected, decompressing via 7zz...");
                cmd = "rm -f " + STAGE_DIR + "/*.img " + SYSTEM_RAW + "; " +
                      STAGE_DIR + "/7zz x -y -o" + STAGE_DIR + " " + cached.getAbsolutePath() + " && " +
                      "mv -f " + STAGE_DIR + "/*.img " + SYSTEM_RAW + " && " +
                      "rm -f " + cached.getAbsolutePath() + " && chmod 644 " + SYSTEM_RAW;
            } else {
                mainHandler.post(() -> {
                    pbDownload.setIndeterminate(true);
                    tvDownloadProgress.setText("Moving image to /data/local/tmp/lineage_stage…");
                });
                appendLog("Raw image detected, moving to system.raw...");
                cmd = "rm -f " + SYSTEM_RAW + "; " +
                      "mv -f " + cached.getAbsolutePath() + " " + SYSTEM_RAW + " && chmod 644 " + SYSTEM_RAW;
            }
            int ret = runRootCmdDrained(cmd);
            if (ret != 0 || !new File(SYSTEM_RAW).exists()) {
                throw new Exception("Failed to prepare system.raw (exit " + ret + ")");
            }

            appendLog("✓ system.raw done!");
            mainHandler.post(() -> {
                pbDownload.setVisibility(View.GONE);
                tvDownloadProgress.setText("The ROM was installed succesfully!");
                btnDownloadLineage.setEnabled(true);
            });
            setLogStatus("Ready");
            checkImageStatus();
        } catch (Exception e) {
            cached.delete();
            appendLog("Import failed: " + e.getMessage());
            setLogStatus("Error");
            mainHandler.post(() -> {
                pbDownload.setVisibility(View.GONE);
                tvDownloadProgress.setText("Something went wrong: " + e.getMessage());
                btnDownloadLineage.setEnabled(true);
            });
        }
    });
}
}
