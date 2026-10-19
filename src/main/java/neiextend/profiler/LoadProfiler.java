package neiextend.profiler;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.lang.management.ClassLoadingMXBean;
import java.lang.management.CompilationMXBean;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
import java.lang.management.RuntimeMXBean;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.launchwrapper.Launch;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Collects timings of the whole game start-up: from JVM start through coremods, FML mod loading phases,
 * resource reloading and up to the first client tick (or the start of a dedicated server). At the end a detailed report
 * is written
 * to {@code logs/neiextend-loadprofile.txt} plus CSV files that can be compared between runs.
 */
public final class LoadProfiler {

    public static final Logger LOG = LogManager.getLogger("NEIExtend-LoadProfiler");

    /** Mod events slower than this are reported to the log immediately. */
    private static final long SLOW_MOD_EVENT_NANOS = 250_000_000L;
    /** Reload listeners slower than this are reported to the log immediately. */
    private static final long SLOW_RELOAD_LISTENER_NANOS = 250_000_000L;

    private static final long JVM_START_MILLIS;
    private static final long JVM_START_NANOS;

    private static volatile boolean enabled = true;
    private static volatile boolean finished;
    private static boolean bootstrapped;

    private static final List<Milestone> milestones = Collections.synchronizedList(new ArrayList<>());
    /** modId -> (phase -> nanos) in insertion order. */
    private static final Map<String, Map<String, Long>> modPhaseTimes = new LinkedHashMap<>();
    private static final Map<String, String> modNames = new LinkedHashMap<>();
    private static final List<String> phaseOrder = new ArrayList<>();
    private static final List<TimedEntry> reloadListenerTimes = Collections.synchronizedList(new ArrayList<>());
    private static final Map<String, Long> stateEnterNanos = new ConcurrentHashMap<>();

    private static String currentState = "COREMODS";
    private static long currentStateStart;
    private static final List<TimedEntry> stateDurations = new ArrayList<>();

    private static int reloadCount;
    private static long reloadStart;

    private static long modEventStart;

    static {
        RuntimeMXBean runtime = ManagementFactory.getRuntimeMXBean();
        JVM_START_MILLIS = runtime.getStartTime();
        long sinceStartNanos = (System.currentTimeMillis() - JVM_START_MILLIS) * 1_000_000L;
        JVM_START_NANOS = System.nanoTime() - sinceStartNanos;
        currentStateStart = System.nanoTime();
    }

    private LoadProfiler() {}

    /**
     * Called from the coremod constructor, the earliest point a mod can run code. Options (JVM arguments):
     * <ul>
     * <li>{@code -Dneiextend.profiler=false} disables the profiler completely</li>
     * <li>{@code -Dneiextend.profiler.transformers=false} disables class transformer timing</li>
     * <li>{@code -Dneiextend.profiler.sampleInterval=10} stack sampler interval in ms, 0 disables it</li>
     * </ul>
     */
    public static synchronized void bootstrap() {
        if (bootstrapped) return;
        bootstrapped = true;
        enabled = Boolean.parseBoolean(System.getProperty("neiextend.profiler", "true"));
        if (!enabled) return;
        try {
            // The profiler runs inside class transformers, so its own classes must never be transformed.
            Launch.classLoader.addTransformerExclusion("neiextend.profiler.");
        } catch (Throwable t) {
            LOG.warn("Could not exclude profiler classes from transformation", t);
        }
        TransformerProfiler
            .setEnabled(Boolean.parseBoolean(System.getProperty("neiextend.profiler.transformers", "true")));
        milestone("NEIExtend coremod loaded (time before this: JVM, launcher, LaunchWrapper, coremod discovery)");
        StackSampler.start(Thread.currentThread(), Integer.getInteger("neiextend.profiler.sampleInterval", 10));
    }

    public static boolean isActive() {
        return enabled && !finished;
    }

    public static void setEnabled(boolean value) {
        enabled = value;
    }

    /** Milliseconds since the JVM was started. */
    public static long uptimeMillis() {
        return (System.nanoTime() - JVM_START_NANOS) / 1_000_000L;
    }

    static double toMillis(long nanos) {
        return nanos / 1_000_000.0;
    }

    // ----------------------------------------------------------------------------------------------------------------
    // Milestones
    // ----------------------------------------------------------------------------------------------------------------

    public static void milestone(String name) {
        if (!isActive()) return;
        Milestone m = new Milestone(
            name,
            System.nanoTime(),
            Thread.currentThread()
                .getName());
        milestones.add(m);
        LOG.info(
            String.format(
                Locale.ROOT,
                "[%8.3fs] %s (heap used %d MB)",
                toMillis(m.nanos - JVM_START_NANOS) / 1000.0,
                name,
                usedHeapMb()));
    }

    // ----------------------------------------------------------------------------------------------------------------
    // FML loader states
    // ----------------------------------------------------------------------------------------------------------------

    public static synchronized void onLoaderStateTransition(String newState) {
        if (!isActive()) return;
        long now = System.nanoTime();
        stateDurations.add(new TimedEntry(currentState, now - currentStateStart));
        LOG.info(
            String.format(
                Locale.ROOT,
                "Loader state %s -> %s, previous state took %.1f ms",
                currentState,
                newState,
                toMillis(now - currentStateStart)));
        currentState = newState;
        currentStateStart = now;
        stateEnterNanos.putIfAbsent(newState, now);
        milestone("FML state " + newState);
        StackSampler.setTarget(Thread.currentThread());
        TransformerProfiler.wrapNewTransformers();
    }

    // ----------------------------------------------------------------------------------------------------------------
    // Per mod event timings
    // ----------------------------------------------------------------------------------------------------------------

    public static void onModEventStart() {
        if (!isActive()) return;
        modEventStart = System.nanoTime();
    }

    public static void onModEventEnd(String modId, String modName, String eventType) {
        if (!isActive() || modEventStart == 0) return;
        long took = System.nanoTime() - modEventStart;
        modEventStart = 0;
        String phase = shortPhaseName(eventType);
        synchronized (LoadProfiler.class) {
            recordModTime(modId, modName, phase, took);
        }
        if (took >= SLOW_MOD_EVENT_NANOS) {
            LOG.info(String.format(Locale.ROOT, "%-16s %-40s took %8.1f ms", phase, modId, toMillis(took)));
        } else if (LOG.isDebugEnabled()) {
            LOG.debug(String.format(Locale.ROOT, "%-16s %-40s took %8.1f ms", phase, modId, toMillis(took)));
        }
    }

    private static void recordModTime(String modId, String modName, String phase, long nanos) {
        if (!phaseOrder.contains(phase)) phaseOrder.add(phase);
        modNames.putIfAbsent(modId, modName);
        modPhaseTimes.computeIfAbsent(modId, k -> new LinkedHashMap<>())
            .merge(phase, nanos, Long::sum);
    }

    private static String shortPhaseName(String eventType) {
        if (eventType == null) return "?";
        String s = eventType;
        int dot = s.lastIndexOf('.');
        if (dot >= 0) s = s.substring(dot + 1);
        if (s.startsWith("FML")) s = s.substring(3);
        if (s.endsWith("Event")) s = s.substring(0, s.length() - 5);
        if (s.startsWith("Initialization")) s = "Init";
        if (s.startsWith("PreInitialization")) s = "PreInit";
        if (s.startsWith("PostInitialization")) s = "PostInit";
        return s;
    }

    // ----------------------------------------------------------------------------------------------------------------
    // Resource reloading
    // ----------------------------------------------------------------------------------------------------------------

    public static void onResourceReloadStart() {
        if (!isActive()) return;
        reloadCount++;
        reloadStart = System.nanoTime();
        milestone("Resource reload #" + reloadCount + " started");
    }

    public static void onResourceReloadEnd() {
        if (!isActive() || reloadStart == 0) return;
        milestone(
            String.format(
                Locale.ROOT,
                "Resource reload #%d finished in %.1f ms",
                reloadCount,
                toMillis(System.nanoTime() - reloadStart)));
        reloadStart = 0;
    }

    public static void onReloadListener(Object listener, long nanos) {
        if (!isActive()) return;
        String context = reloadStart != 0 ? "reload #" + reloadCount : "register";
        String name = context + " "
            + (listener == null ? "null"
                : listener.getClass()
                    .getName());
        reloadListenerTimes.add(new TimedEntry(name, nanos));
        if (nanos >= SLOW_RELOAD_LISTENER_NANOS) {
            LOG.info(String.format(Locale.ROOT, "Reload listener %s took %.1f ms", name, toMillis(nanos)));
        }
    }

    // ----------------------------------------------------------------------------------------------------------------
    // Finish & report
    // ----------------------------------------------------------------------------------------------------------------

    public static void finish(String reason) {
        if (!isActive()) return;
        milestone(reason);
        synchronized (LoadProfiler.class) {
            long now = System.nanoTime();
            stateDurations.add(new TimedEntry(currentState, now - currentStateStart));
            finished = true;
        }
        StackSampler.stop();
        try {
            writeReport(reason);
        } catch (Throwable t) {
            LOG.error("Failed to write load profile report", t);
        }
    }

    private static long usedHeapMb() {
        MemoryUsage heap = ManagementFactory.getMemoryMXBean()
            .getHeapMemoryUsage();
        return heap.getUsed() / (1024 * 1024);
    }

    private static File logsDir() {
        File dir = new File("logs");
        if (!dir.isDirectory()) dir.mkdirs();
        return dir;
    }

    private static synchronized void writeReport(String reason) throws Exception {
        long totalMillis = uptimeMillis();
        File dir = logsDir();
        File report = new File(dir, "neiextend-loadprofile.txt");
        try (PrintWriter out = new PrintWriter(
            new OutputStreamWriter(new FileOutputStream(report), StandardCharsets.UTF_8))) {
            writeHeader(out, reason, totalMillis);
            writeTimeline(out);
            writeStates(out);
            writeModTotals(out);
            writeModPhases(out);
            writeReloadListeners(out);
            TransformerProfiler.writeReport(out);
            StackSampler.writeReport(out);
            writeJvmStats(out);
            writeHints(out, totalMillis);
        }
        writeModCsv(new File(dir, "neiextend-loadprofile-mods.csv"));
        TransformerProfiler.writeCsv(new File(dir, "neiextend-loadprofile-transformers.csv"));

        LOG.info(
            String.format(
                Locale.ROOT,
                "Game loaded in %.2f s (since JVM start). Full report: %s",
                totalMillis / 1000.0,
                report.getAbsolutePath()));
        logTopMods(10);
        TransformerProfiler.logSummary();
    }

    private static void writeHeader(PrintWriter out, String reason, long totalMillis) {
        RuntimeMXBean runtime = ManagementFactory.getRuntimeMXBean();
        out.println("================================================================================");
        out.println(" NEIExtend load profile");
        out.println("================================================================================");
        out.println("Created:        " + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).format(new Date()));
        out.println("Finished at:    " + reason);
        out.println(String.format(Locale.ROOT, "TOTAL LOAD:     %.2f s (since JVM start)", totalMillis / 1000.0));
        out.println(
            "Java:           " + System.getProperty("java.version")
                + " ("
                + System.getProperty("java.vm.name")
                + ", "
                + System.getProperty("java.vendor")
                + ")");
        out.println(
            "OS:             " + System
                .getProperty("os.name") + " " + System.getProperty("os.version") + " " + System.getProperty("os.arch"));
        out.println(
            "CPUs:           " + Runtime.getRuntime()
                .availableProcessors());
        out.println(
            "Max heap:       " + Runtime.getRuntime()
                .maxMemory() / (1024 * 1024) + " MB");
        out.println("JVM arguments:  " + String.join(" ", runtime.getInputArguments()));
        out.println();
    }

    private static void writeTimeline(PrintWriter out) {
        out.println("--- Timeline (seconds since JVM start) -----------------------------------------");
        long prev = JVM_START_NANOS;
        out.println(String.format(Locale.ROOT, "%9s %9s  %s", "at", "+delta", "event"));
        out.println(String.format(Locale.ROOT, "%9.3f %9s  %s", 0.0, "", "JVM started"));
        synchronized (milestones) {
            for (Milestone m : milestones) {
                out.println(
                    String.format(
                        Locale.ROOT,
                        "%9.3f %+9.3f  %s%s",
                        toMillis(m.nanos - JVM_START_NANOS) / 1000.0,
                        toMillis(m.nanos - prev) / 1000.0,
                        m.name,
                        m.thread.equals("Client thread") || m.thread.equals("main") ? "" : "  [" + m.thread + "]"));
                prev = m.nanos;
            }
        }
        out.println();
    }

    private static void writeStates(PrintWriter out) {
        out.println("--- Time spent in each FML loader state ----------------------------------------");
        long total = 0;
        for (TimedEntry e : stateDurations) total += e.nanos;
        for (TimedEntry e : stateDurations) {
            out.println(
                String.format(
                    Locale.ROOT,
                    "%-28s %10.1f ms  %5.1f%%",
                    e.name,
                    toMillis(e.nanos),
                    total == 0 ? 0 : 100.0 * e.nanos / total));
        }
        out.println();
    }

    private static List<Map.Entry<String, Long>> sortedModTotals() {
        List<Map.Entry<String, Long>> totals = new ArrayList<>();
        for (Map.Entry<String, Map<String, Long>> e : modPhaseTimes.entrySet()) {
            long sum = 0;
            for (long v : e.getValue()
                .values()) sum += v;
            totals.add(new java.util.AbstractMap.SimpleEntry<>(e.getKey(), sum));
        }
        totals.sort((a, b) -> Long.compare(b.getValue(), a.getValue()));
        return totals;
    }

    private static void writeModTotals(PrintWriter out) {
        List<Map.Entry<String, Long>> totals = sortedModTotals();
        long all = 0;
        for (Map.Entry<String, Long> e : totals) all += e.getValue();
        out.println("--- Mods sorted by total time in FML events (all phases) -----------------------");
        out.println(
            String.format(Locale.ROOT, "Mods: %d, sum of all mod events: %.1f ms", totals.size(), toMillis(all)));
        StringBuilder header = new StringBuilder(
            String.format(Locale.ROOT, "%-4s %-36s %10s %6s", "#", "mod", "total ms", "%"));
        for (String phase : phaseOrder) header.append(String.format(Locale.ROOT, " %12s", trim(phase, 12)));
        out.println(header);
        int i = 1;
        for (Map.Entry<String, Long> e : totals) {
            Map<String, Long> phases = modPhaseTimes.get(e.getKey());
            StringBuilder line = new StringBuilder(
                String.format(
                    Locale.ROOT,
                    "%-4d %-36s %10.1f %5.1f%%",
                    i++,
                    trim(e.getKey(), 36),
                    toMillis(e.getValue()),
                    all == 0 ? 0 : 100.0 * e.getValue() / all));
            for (String phase : phaseOrder) {
                Long v = phases.get(phase);
                line.append(
                    v == null ? String.format(Locale.ROOT, " %12s", "-")
                        : String.format(Locale.ROOT, " %12.1f", toMillis(v)));
            }
            out.println(line);
        }
        out.println();
    }

    private static void writeModPhases(PrintWriter out) {
        out.println("--- Top 20 slowest mods per phase ----------------------------------------------");
        for (String phase : phaseOrder) {
            List<Map.Entry<String, Long>> list = new ArrayList<>();
            long sum = 0;
            for (Map.Entry<String, Map<String, Long>> e : modPhaseTimes.entrySet()) {
                Long v = e.getValue()
                    .get(phase);
                if (v != null) {
                    list.add(new java.util.AbstractMap.SimpleEntry<>(e.getKey(), v));
                    sum += v;
                }
            }
            list.sort((a, b) -> Long.compare(b.getValue(), a.getValue()));
            out.println(
                String.format(Locale.ROOT, "[%s] total %.1f ms over %d mods", phase, toMillis(sum), list.size()));
            for (int i = 0; i < Math.min(20, list.size()); i++) {
                Map.Entry<String, Long> e = list.get(i);
                out.println(
                    String.format(
                        Locale.ROOT,
                        "    %-40s %10.1f ms  %5.1f%%",
                        trim(e.getKey(), 40),
                        toMillis(e.getValue()),
                        sum == 0 ? 0 : 100.0 * e.getValue() / sum));
            }
        }
        out.println();
    }

    private static void writeReloadListeners(PrintWriter out) {
        out.println("--- Resource reload listeners (texture stitching, sounds, models, fonts...) ----");
        List<TimedEntry> list;
        synchronized (reloadListenerTimes) {
            list = new ArrayList<>(reloadListenerTimes);
        }
        long sum = 0;
        for (TimedEntry e : list) sum += e.nanos;
        out.println(
            String.format(
                Locale.ROOT,
                "Reloads: %d, listeners called: %d, total %.1f ms",
                reloadCount,
                list.size(),
                toMillis(sum)));
        list.sort((a, b) -> Long.compare(b.nanos, a.nanos));
        for (int i = 0; i < Math.min(40, list.size()); i++) {
            TimedEntry e = list.get(i);
            out.println(String.format(Locale.ROOT, "    %-70s %10.1f ms", trim(e.name, 70), toMillis(e.nanos)));
        }
        out.println();
    }

    private static void writeJvmStats(PrintWriter out) {
        out.println("--- JVM statistics during load -------------------------------------------------");
        long gcCount = 0, gcTime = 0;
        for (GarbageCollectorMXBean gc : ManagementFactory.getGarbageCollectorMXBeans()) {
            out.println(
                String.format(
                    Locale.ROOT,
                    "GC %-30s collections: %6d   time: %8d ms",
                    gc.getName(),
                    gc.getCollectionCount(),
                    gc.getCollectionTime()));
            gcCount += Math.max(0, gc.getCollectionCount());
            gcTime += Math.max(0, gc.getCollectionTime());
        }
        out.println(String.format(Locale.ROOT, "GC total: %d collections, %d ms", gcCount, gcTime));
        CompilationMXBean jit = ManagementFactory.getCompilationMXBean();
        if (jit != null && jit.isCompilationTimeMonitoringSupported()) {
            out.println("JIT compiler " + jit.getName() + " time: " + jit.getTotalCompilationTime() + " ms");
        }
        ClassLoadingMXBean cl = ManagementFactory.getClassLoadingMXBean();
        out.println(
            "Classes loaded: " + cl.getLoadedClassCount() + " (total ever " + cl.getTotalLoadedClassCount() + ")");
        MemoryMXBean mem = ManagementFactory.getMemoryMXBean();
        MemoryUsage heap = mem.getHeapMemoryUsage();
        MemoryUsage nonHeap = mem.getNonHeapMemoryUsage();
        out.println(
            String.format(
                Locale.ROOT,
                "Heap: used %d MB, committed %d MB, max %d MB",
                heap.getUsed() >> 20,
                heap.getCommitted() >> 20,
                heap.getMax() >> 20));
        out.println(
            String.format(
                Locale.ROOT,
                "Non-heap (metaspace, code cache): used %d MB, committed %d MB",
                nonHeap.getUsed() >> 20,
                nonHeap.getCommitted() >> 20));
        out.println(
            "Live threads: " + ManagementFactory.getThreadMXBean()
                .getThreadCount());
        out.println();
    }

    private static void writeHints(PrintWriter out, long totalMillis) {
        out.println("--- Hints -----------------------------------------------------------------------");
        long gcTime = 0;
        for (GarbageCollectorMXBean gc : ManagementFactory.getGarbageCollectorMXBeans()) {
            gcTime += Math.max(0, gc.getCollectionTime());
        }
        if (totalMillis > 0 && gcTime * 10 > totalMillis) {
            out.println(
                String.format(
                    Locale.ROOT,
                    "* GC took %.1f%% of load time. Set -Xms equal to -Xmx and/or use a modern collector (G1/ZGC on Java 17+).",
                    100.0 * gcTime / totalMillis));
        }
        long transformerNanos = TransformerProfiler.totalSelfNanos();
        if (transformerNanos > 0) {
            out.println(
                String.format(
                    Locale.ROOT,
                    "* Class transformers took %.1f s (%.1f%% of load). See the transformer table: the top entries are the"
                        + " first candidates for caching or replacement.",
                    toMillis(transformerNanos) / 1000.0,
                    totalMillis == 0 ? 0 : 100.0 * toMillis(transformerNanos) / totalMillis));
        }
        List<Map.Entry<String, Long>> totals = sortedModTotals();
        if (!totals.isEmpty()) {
            Map.Entry<String, Long> top = totals.get(0);
            out.println(
                String.format(
                    Locale.ROOT,
                    "* Slowest mod: %s with %.1f s in FML events. Check the stack sampler section to see which methods.",
                    top.getKey(),
                    toMillis(top.getValue()) / 1000.0));
        }
        long reloadSum = 0;
        synchronized (reloadListenerTimes) {
            for (TimedEntry e : reloadListenerTimes) reloadSum += e.nanos;
        }
        if (reloadCount > 1) {
            out.println(
                String.format(
                    Locale.ROOT,
                    "* Resources were reloaded %d times (%.1f s total). Every extra reload repeats texture stitching.",
                    reloadCount,
                    toMillis(reloadSum) / 1000.0));
        }
        out.println("* Compare neiextend-loadprofile-*.csv between runs to measure the effect of every optimisation.");
        out.println();
    }

    private static void writeModCsv(File file) {
        try (PrintWriter out = new PrintWriter(
            new OutputStreamWriter(new FileOutputStream(file), StandardCharsets.UTF_8))) {
            StringBuilder header = new StringBuilder("modid,name,total_ms");
            for (String phase : phaseOrder) header.append(',')
                .append(phase)
                .append("_ms");
            out.println(header);
            for (Map.Entry<String, Long> e : sortedModTotals()) {
                Map<String, Long> phases = modPhaseTimes.get(e.getKey());
                StringBuilder line = new StringBuilder();
                line.append(csv(e.getKey()))
                    .append(',')
                    .append(csv(modNames.get(e.getKey())))
                    .append(',')
                    .append(String.format(Locale.ROOT, "%.3f", toMillis(e.getValue())));
                for (String phase : phaseOrder) {
                    Long v = phases.get(phase);
                    line.append(',')
                        .append(v == null ? "" : String.format(Locale.ROOT, "%.3f", toMillis(v)));
                }
                out.println(line);
            }
        } catch (Exception e) {
            LOG.error("Failed to write " + file, e);
        }
    }

    private static void logTopMods(int count) {
        List<Map.Entry<String, Long>> totals = sortedModTotals();
        LOG.info("Top " + Math.min(count, totals.size()) + " slowest mods:");
        for (int i = 0; i < Math.min(count, totals.size()); i++) {
            Map.Entry<String, Long> e = totals.get(i);
            LOG.info(String.format(Locale.ROOT, "  %2d. %-40s %10.1f ms", i + 1, e.getKey(), toMillis(e.getValue())));
        }
    }

    static String csv(String s) {
        if (s == null) return "";
        if (s.indexOf(',') >= 0 || s.indexOf('"') >= 0) return '"' + s.replace("\"", "\"\"") + '"';
        return s;
    }

    static String trim(String s, int len) {
        if (s == null) return "";
        return s.length() <= len ? s : s.substring(0, len - 1) + "~";
    }

    private static final class Milestone {

        final String name;
        final long nanos;
        final String thread;

        Milestone(String name, long nanos, String thread) {
            this.name = name;
            this.nanos = nanos;
            this.thread = thread;
        }
    }

    static final class TimedEntry {

        final String name;
        final long nanos;

        TimedEntry(String name, long nanos) {
            this.name = name;
            this.nanos = nanos;
        }
    }
}
