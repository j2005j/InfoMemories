package dev.camsysinfo.camhwinfo;

import android.app.Activity;
import android.os.Build;
import android.os.Bundle;
import android.os.StatFs;
import android.util.DisplayMetrics;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Cam HW Info — a deliberately conservative hardware reader for Sony cameras
 * running the PlayMemories Android subsystem (A6300 and friends).
 *
 * CPU-Z and AIDA64 hard-reboot this camera. The restart (not merely an app
 * crash) points at native/driver level faults, most plausibly from:
 *   - creating GL contexts to query the GPU (GPU belongs to the imaging pipe)
 *   - enumerating binder services / sensors whose HALs don't exist or are
 *     unsafe to poke while the camera is running
 *   - aggressive sysfs polling (cpufreq, thermal) that can block or fault
 *     on Sony's kernel
 *
 * This app therefore touches ONLY static, read-only procfs/sysfs files and
 * StatFs, always from a worker thread, always under a hard timeout. If a
 * node doesn't answer within the budget it is reported as timed out and
 * abandoned — no retries, no fallbacks into riskier APIs.
 */
public class MainActivity extends Activity {

    /** Per-read budget. Any sysfs/proc node that blocks longer is abandoned. */
    private static final long READ_TIMEOUT_MS = 1500;
    /** Whole-section budget (sections may do several reads). */
    private static final long SECTION_TIMEOUT_MS = 5000;

    private TextView statusView;
    private ExecutorService pool;
    private volatile boolean running = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        pool = Executors.newCachedThreadPool();

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF101418);

        statusView = new TextView(this);
        statusView.setTextColor(0xFFD7DEE5);
        statusView.setTextSize(12f);
        statusView.setPadding(24, 16, 24, 24);
        statusView.setText("Reading hardware info…\n\n"
                + "MENU = exit");

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(0xFF101418);
        scroll.setFillViewport(true);
        scroll.addView(statusView);

        root.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(root);

        // Auto-read on launch — no button to tap.
        startRead();
    }

    /** Camera MENU key exits the app. */
    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_MENU) {
            finish();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (pool != null) {
            pool.shutdownNow();
        }
    }

    private void startRead() {
        if (running) {
            return;
        }
        running = true;
        statusView.setText("Reading hardware info…");
        pool.execute(new Runnable() {
            @Override
            public void run() {
                final String report = buildReport();
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (isFinishing()) {
                            return;
                        }
                        statusView.setText(report);
                        running = false;
                    }
                });
            }
        });
    }

    // ---------------------------------------------------------------------
    // Report assembly
    // ---------------------------------------------------------------------

    private String buildReport() {
        StringBuilder sb = new StringBuilder();
        sb.append("=== InfoMemories 1.0 ===\n");
        sb.append("captured: ").append(new Date().toString()).append("\n");
        sb.append("mode: conservative read-only probe (MENU = exit)\n\n");

        addSection(sb, "System", new Callable<String>() {
            @Override
            public String call() {
                return sectionSystem();
            }
        });
        addSection(sb, "SoC / Board", new Callable<String>() {
            @Override
            public String call() {
                return sectionSocBoard();
            }
        });
        addSection(sb, "CPU", new Callable<String>() {
            @Override
            public String call() {
                return sectionCpu();
            }
        });
        addSection(sb, "Memory", new Callable<String>() {
            @Override
            public String call() {
                return sectionMemory();
            }
        });
        addSection(sb, "Storage", new Callable<String>() {
            @Override
            public String call() {
                return sectionStorage();
            }
        });
        addSection(sb, "Display", new Callable<String>() {
            @Override
            public String call() {
                return sectionDisplay();
            }
        });

        return sb.toString();
    }

    /** Runs one section on the pool under a hard timeout; a stuck section is
     *  reported as timed out instead of hanging the whole report. */
    private void addSection(StringBuilder sb, String title, Callable<String> section) {
        sb.append("--- ").append(title).append(" ---\n");
        Future<String> f = pool.submit(section);
        try {
            sb.append(f.get(SECTION_TIMEOUT_MS, TimeUnit.MILLISECONDS));
        } catch (TimeoutException te) {
            f.cancel(true);
            sb.append("(section timed out after ").append(SECTION_TIMEOUT_MS)
              .append(" ms — skipped for safety)");
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            sb.append("(interrupted)");
        } catch (Exception e) {
            sb.append("(error: ").append(e.toString()).append(")");
        }
        sb.append("\n\n");
    }

    // ---------------------------------------------------------------------
    // Sections
    // ---------------------------------------------------------------------

    private String sectionSystem() {
        StringBuilder sb = new StringBuilder();
        line(sb, "manufacturer", Build.MANUFACTURER);
        line(sb, "model", Build.MODEL);
        line(sb, "device", Build.DEVICE);
        line(sb, "product", Build.PRODUCT);
        line(sb, "board", Build.BOARD);
        line(sb, "hardware", Build.HARDWARE);
        line(sb, "bootloader", Build.BOOTLOADER);
        line(sb, "android", Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")");
        line(sb, "build id", Build.ID);
        line(sb, "display", Build.DISPLAY);
        line(sb, "fingerprint", Build.FINGERPRINT);
        if (Build.VERSION.SDK_INT >= 21) {
            line(sb, "cpu abi", Build.CPU_ABI);
            if (Build.VERSION.SDK_INT >= 21) {
                try {
                    Field f = Build.class.getField("SUPPORTED_ABIS");
                    String[] abis = (String[]) f.get(null);
                    if (abis != null && abis.length > 0) {
                        StringBuilder a = new StringBuilder();
                        for (int i = 0; i < abis.length; i++) {
                            if (i > 0) a.append(", ");
                            a.append(abis[i]);
                        }
                        line(sb, "supported abis", a.toString());
                    }
                } catch (Throwable ignored) {
                    // field absent on this platform — fine
                }
            }
        }
        line(sb, "kernel", trim(readTimed("/proc/version", 512)));
        line(sb, "uptime", trim(readTimed("/proc/uptime", 128)));
        return sb.toString();
    }

    private String sectionSocBoard() {
        StringBuilder sb = new StringBuilder();
        // Device-tree: the most likely place to find the real SoC identity.
        String dtModel = firstNonEmpty(
                trim(readDeviceTree("/proc/device-tree/model")),
                trim(readDeviceTree("/sys/firmware/devicetree/base/model")));
        String dtCompat = firstNonEmpty(
                trim(readDeviceTree("/proc/device-tree/compatible")),
                trim(readDeviceTree("/sys/firmware/devicetree/base/compatible")));
        line(sb, "dt model", dtModel);
        line(sb, "dt compatible", dtCompat);
        if (dtModel.isEmpty() && dtCompat.isEmpty()) {
            sb.append("dt model       (no device-tree found)\n");
        }

        // build.prop candidates (plain file reads, no getprop exec)
        String[] props = propPaths();
        Map<String, String> map = new LinkedHashMap<String, String>();
        String[] keys = {"ro.board.platform", "ro.hardware", "ro.boot.hardware",
                "ro.product.board", "ro.soc.model", "ro.soc.manufacturer",
                "ro.chipname", "ro.product.cpu.abi"};
        for (String p : props) {
            String content = readTimed(p, 16384);
            if (content == null || content.isEmpty()) continue;
            for (String key : keys) {
                if (map.containsKey(key)) continue;
                String v = findProp(content, key);
                if (v != null) map.put(key, v);
            }
        }
        if (map.isEmpty()) {
            sb.append("build.prop     (no known keys found)\n");
        } else {
            for (Map.Entry<String, String> e : map.entrySet()) {
                line(sb, e.getKey(), e.getValue());
            }
        }

        // Qualcomm SoC id, if this node exists (harmless static read)
        String socId = trim(readTimed("/sys/devices/soc0/soc_id", 64));
        if (!socId.isEmpty()) line(sb, "soc id", socId);
        return sb.toString();
    }

    private String sectionCpu() {
        StringBuilder sb = new StringBuilder();
        String online = trim(readTimed("/sys/devices/system/cpu/online", 64));
        if (online.isEmpty()) online = "(unavailable)";
        line(sb, "cores online", online);

        String cpuinfo = readTimed("/proc/cpuinfo", 8192);
        if (cpuinfo == null || cpuinfo.isEmpty()) {
            sb.append("cpuinfo        (unreadable)\n");
        } else {
            // Pull out the interesting keys instead of dumping the whole file.
            String[] keys = {"processor", "model name", "Processor", "Features",
                    "CPU architecture", "CPU implementer", "CPU part", "Hardware"};
            for (String k : keys) {
                String v = findCpuinfoValue(cpuinfo, k);
                if (v != null) line(sb, k, v);
            }
        }

        // Per-core cpufreq: read only cpu0 (and cpu1..cpu3 if present);
        // read-only scalars, one shot each, no polling loop.
        int coresShown = 0;
        for (int i = 0; i < 4 && coresShown < 4; i++) {
            String base = "/sys/devices/system/cpu/cpu" + i + "/cpufreq/";
            String cur = trim(readTimed(base + "scaling_cur_freq", 32));
            if (cur.isEmpty()) continue;
            String max = trim(readTimed(base + "cpuinfo_max_freq", 32));
            String gov = trim(readTimed(base + "scaling_governor", 32));
            StringBuilder line = new StringBuilder("cpu").append(i).append(" ");
            line.append(khzToMhz(cur));
            if (!max.isEmpty()) line.append(" (max ").append(khzToMhz(max)).append(")");
            if (!gov.isEmpty()) line.append(" gov=").append(gov);
            sb.append(line.append("\n"));
            coresShown++;
        }
        if (coresShown == 0) {
            sb.append("cpufreq        (no cpufreq sysfs exposed)\n");
        }
        return sb.toString();
    }

    private String sectionMemory() {
        StringBuilder sb = new StringBuilder();
        String meminfo = readTimed("/proc/meminfo", 4096);
        if (meminfo == null || meminfo.isEmpty()) {
            return "meminfo        (unreadable)\n";
        }
        String[] keys = {"MemTotal", "MemFree", "MemAvailable", "Buffers", "Cached",
                "SwapTotal", "SwapFree", "Active", "Inactive"};
        for (String k : keys) {
            String v = findMeminfoValue(meminfo, k);
            if (v != null) line(sb, k, v);
        }
        return sb.toString();
    }

    private String sectionStorage() {
        StringBuilder sb = new StringBuilder();
        appendStatFs(sb, "/", "root");
        appendStatFs(sb, "/data", "data");
        appendStatFs(sb, "/cache", "cache");
        return sb.toString();
    }

    private String sectionDisplay() {
        StringBuilder sb = new StringBuilder();
        DisplayMetrics dm = getResources().getDisplayMetrics();
        line(sb, "resolution", dm.widthPixels + " x " + dm.heightPixels);
        line(sb, "density", dm.densityDpi + " dpi (scale " + dm.density + ")");
        line(sb, "xdpi/ydpi", String.format(Locale.US, "%.1f / %.1f", dm.xdpi, dm.ydpi));
        return sb.toString();
    }

    private void appendStatFs(StringBuilder sb, String path, String label) {
        try {
            File f = new File(path);
            if (!f.exists()) return;
            // Int-based getters exist since API 1 (the *Long variants need
            // API 18); cast before multiplying to avoid 32-bit overflow.
            StatFs s = new StatFs(path);
            long block = s.getBlockSize();
            long total = (long) s.getBlockCount() * block;
            long free = (long) s.getAvailableBlocks() * block;
            sb.append(String.format(Locale.US, "%-8s total %s, free %s%n",
                    label + ":", humanBytes(total), humanBytes(free)));
        } catch (Throwable t) {
            sb.append(label).append(": (").append(t.getClass().getSimpleName()).append(")\n");
        }
    }

    // ---------------------------------------------------------------------
    // Read primitives — every one is bounded and guarded
    // ---------------------------------------------------------------------

    /** Reads a proc/sys file on the pool with a hard timeout.
     *  Returns "" for missing/permission-denied, "(timeout)" if abandoned. */
    private String readTimed(final String path, final int maxBytes) {
        Future<String> f = pool.submit(new Callable<String>() {
            @Override
            public String call() {
                return readFileBounded(path, maxBytes);
            }
        });
        try {
            String r = f.get(READ_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            return r == null ? "" : r;
        } catch (TimeoutException te) {
            f.cancel(true);
            return "(timeout)";
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return "";
        } catch (Exception e) {
            return "";
        }
    }

    /** Direct bounded read; never reads more than maxBytes. */
    private static String readFileBounded(String path, int maxBytes) {
        InputStream in = null;
        try {
            in = new FileInputStream(path);
            ByteArrayOutputStream out = new ByteArrayOutputStream(Math.min(maxBytes, 4096));
            byte[] buf = new byte[1024];
            int total = 0;
            int n;
            while (total < maxBytes && (n = in.read(buf, 0, Math.min(buf.length, maxBytes - total))) > 0) {
                out.write(buf, 0, n);
                total += n;
            }
            return new String(out.toByteArray(), "UTF-8");
        } catch (IOException e) {
            return "";
        } finally {
            if (in != null) {
                try { in.close(); } catch (IOException ignored) {}
            }
        }
    }

    /** Device-tree values are NUL-separated strings; normalise them. */
    private String readDeviceTree(String path) {
        if (!new File(path).exists()) return "";
        String raw = readTimed(path, 512);
        if (raw == null) return "";
        return raw.replace('\0', ' ').trim();
    }

    // ---------------------------------------------------------------------
    // Small parsing helpers
    // ---------------------------------------------------------------------

    private static void line(StringBuilder sb, String key, String value) {
        sb.append(String.format(Locale.US, "%-16s %s%n", key + ":",
                (value == null || value.isEmpty()) ? "(n/a)" : value));
    }

    private static String trim(String s) {
        return s == null ? "" : s.trim();
    }

    private static String firstNonEmpty(String a, String b) {
        return (a == null || a.isEmpty()) ? (b == null ? "" : b) : a;
    }

    private static String khzToMhz(String khz) {
        try {
            long v = Long.parseLong(khz.trim());
            return (v / 1000) + " MHz";
        } catch (NumberFormatException e) {
            return khz;
        }
    }

    private static String humanBytes(long b) {
        if (b >= 1L << 30) return String.format(Locale.US, "%.2f GiB", b / (double) (1L << 30));
        if (b >= 1L << 20) return String.format(Locale.US, "%.1f MiB", b / (double) (1L << 20));
        return (b / 1024) + " KiB";
    }

    private static String[] propPaths() {
        return new String[]{"/system/build.prop", "/vendor/build.prop",
                "/product/build.prop", "/default.prop"};
    }

    /** Finds "key=value" in build.prop content. */
    private static String findProp(String content, String key) {
        String prefix = key + "=";
        int i = content.indexOf(prefix);
        while (i >= 0) {
            // ensure it's at a line start (or start of file)
            if (i == 0 || content.charAt(i - 1) == '\n') {
                int end = content.indexOf('\n', i);
                if (end < 0) end = content.length();
                return content.substring(i + prefix.length(), end).trim();
            }
            i = content.indexOf(prefix, i + 1);
        }
        return null;
    }

    /** Finds the first "key\t: value" line in /proc/cpuinfo. */
    private static String findCpuinfoValue(String content, String key) {
        String[] lines = content.split("\n");
        for (String l : lines) {
            int colon = l.indexOf(':');
            if (colon < 0) continue;
            if (l.substring(0, colon).trim().equals(key)) {
                return l.substring(colon + 1).trim();
            }
        }
        return null;
    }

    /** Finds "Key: value" in /proc/meminfo. */
    private static String findMeminfoValue(String content, String key) {
        return findCpuinfoValue(content, key);
    }
}
