package neiextend.profiler;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * A tiny sampling profiler: periodically takes the stack trace of the loading thread and counts which methods are on
 * top of the stack (self time) and anywhere on the stack (total time). This shows what exactly is slow inside a mod's
 * preInit / init / postInit, texture stitching, recipe registration etc.
 */
public final class StackSampler implements Runnable {

    private static final int MAX_DEPTH = 256;

    private static volatile Thread target;
    private static volatile boolean running;
    private static Thread samplerThread;
    private static int intervalMs;

    private static final Map<String, int[]> selfRaw = new HashMap<>();
    private static final Map<String, int[]> selfCode = new HashMap<>();
    private static final Map<String, int[]> inclusive = new HashMap<>();
    private static final Map<String, int[]> packages = new HashMap<>();
    private static final Map<Thread.State, int[]> states = new EnumMap<>(Thread.State.class);
    private static int samples;

    private StackSampler() {}

    public static synchronized void start(Thread thread, int interval) {
        if (running || interval <= 0) return;
        target = thread;
        intervalMs = interval;
        running = true;
        samplerThread = new Thread(new StackSampler(), "NEIExtend-LoadProfiler-Sampler");
        samplerThread.setDaemon(true);
        samplerThread.setPriority(Thread.MAX_PRIORITY);
        samplerThread.start();
        LoadProfiler.LOG.info("Stack sampler started for thread '" + thread.getName() + "' every " + interval + " ms");
    }

    /** The dedicated server loads mods on the "Server thread", so the sampled thread can be switched. */
    public static void setTarget(Thread thread) {
        if (running && target != thread) {
            target = thread;
            LoadProfiler.LOG.info("Stack sampler now follows thread '" + thread.getName() + "'");
        }
    }

    public static void stop() {
        Thread t;
        synchronized (StackSampler.class) {
            if (!running) return;
            running = false;
            t = samplerThread;
        }
        if (t != null) {
            t.interrupt();
            try {
                t.join(2000);
            } catch (InterruptedException ignored) {
                Thread.currentThread()
                    .interrupt();
            }
        }
    }

    @Override
    public void run() {
        while (running) {
            try {
                Thread.sleep(intervalMs);
            } catch (InterruptedException e) {
                break;
            }
            Thread t = target;
            if (t == null || !t.isAlive()) continue;
            Thread.State state = t.getState();
            StackTraceElement[] stack = t.getStackTrace();
            if (stack.length == 0) continue;
            synchronized (StackSampler.class) {
                record(state, stack);
            }
        }
    }

    private static void record(Thread.State state, StackTraceElement[] stack) {
        samples++;
        inc(states, state);
        inc(selfRaw, frame(stack[0]));
        String code = null;
        for (StackTraceElement e : stack) {
            if (!isSystemFrame(e.getClassName())) {
                code = frame(e);
                inc(packages, owner(e.getClassName()));
                break;
            }
        }
        if (code != null) inc(selfCode, code);
        Set<String> seen = new HashSet<>();
        int depth = Math.min(stack.length, MAX_DEPTH);
        for (int i = 0; i < depth; i++) {
            String f = frame(stack[i]);
            if (seen.add(f)) inc(inclusive, f);
        }
    }

    private static String frame(StackTraceElement e) {
        return e.getClassName() + "." + e.getMethodName();
    }

    private static boolean isSystemFrame(String cls) {
        return cls.startsWith("java.") || cls.startsWith("javax.")
            || cls.startsWith("sun.")
            || cls.startsWith("jdk.")
            || cls.startsWith("com.sun.")
            || cls.startsWith("com.google.common.")
            || cls.startsWith("org.apache.")
            || cls.startsWith("neiextend.profiler.");
    }

    /** First three package segments, e.g. "gregtech.common.items" or "net.minecraft.client". */
    private static String owner(String cls) {
        int end = -1;
        for (int i = 0; i < 3; i++) {
            int next = cls.indexOf('.', end + 1);
            if (next < 0) break;
            end = next;
        }
        return end < 0 ? cls : cls.substring(0, end);
    }

    private static <K> void inc(Map<K, int[]> map, K key) {
        int[] c = map.get(key);
        if (c == null) map.put(key, new int[] { 1 });
        else c[0]++;
    }

    static synchronized void writeReport(PrintWriter out) {
        out.println("--- Stack sampler (where the loading thread spends its time) -------------------");
        if (samples == 0) {
            out.println("No samples (disabled with -Dneiextend.profiler.sampleInterval=0?).");
            out.println();
            return;
        }
        out.println(
            String.format(
                Locale.ROOT,
                "Samples: %d every %d ms (~%.1f s of the loading thread). Thread states: %s",
                samples,
                intervalMs,
                samples * intervalMs / 1000.0,
                stateSummary()));
        out.println("One sample ~ " + intervalMs + " ms. 'self' = method on top of the stack, 'total' = anywhere on it.");
        writeTop(out, "Top 40 code packages (self time, first non-JDK/library frame)", packages, 40);
        writeTop(out, "Top 60 methods by self time (first non-JDK/library frame)", selfCode, 60);
        writeTop(out, "Top 30 methods by raw self time (including JDK frames)", selfRaw, 30);
        writeTop(out, "Top 120 methods by total time (inclusive)", inclusive, 120);
        out.println();
    }

    private static String stateSummary() {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<Thread.State, int[]> e : states.entrySet()) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(e.getKey())
                .append('=')
                .append(String.format(Locale.ROOT, "%.1f%%", 100.0 * e.getValue()[0] / samples));
        }
        return sb.toString();
    }

    private static void writeTop(PrintWriter out, String title, Map<String, int[]> map, int limit) {
        out.println();
        out.println(title + ":");
        List<Map.Entry<String, int[]>> list = new ArrayList<>(map.entrySet());
        list.sort((a, b) -> Integer.compare(b.getValue()[0], a.getValue()[0]));
        for (int i = 0; i < Math.min(limit, list.size()); i++) {
            Map.Entry<String, int[]> e = list.get(i);
            int n = e.getValue()[0];
            out.println(
                String.format(
                    Locale.ROOT,
                    "  %6d %5.1f%% %9.1f ms  %s",
                    n,
                    100.0 * n / samples,
                    (double) n * intervalMs,
                    e.getKey()));
        }
    }
}
