package neiextend.profiler;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import net.minecraft.launchwrapper.IClassNameTransformer;
import net.minecraft.launchwrapper.IClassTransformer;
import net.minecraft.launchwrapper.Launch;
import net.minecraft.launchwrapper.LaunchClassLoader;

/**
 * Measures the self time of every class transformer (ASM coremods, access transformers, FML patches...).
 * Nested class loading inside a transformer is subtracted, so the numbers do not overlap.
 */
public final class TransformerProfiler {

    /**
     * Transformers that must keep their identity: the class name transformer is looked up by type, Mixin excludes these
     * FML transformers by class name, and wrapping the Mixin proxy would break Mixin's transformer delegation.
     */
    private static final String[] NEVER_WRAP = { "org.spongepowered.",
        "cpw.mods.fml.common.asm.transformers.EventSubscriptionTransformer",
        "cpw.mods.fml.common.asm.transformers.TerminalTransformer", "cpw.mods.fml.common.asm.transformers.DeobfuscationTransformer" };

    private static volatile boolean enabled;
    private static Field transformersField;

    private static final Map<String, Stat> stats = new ConcurrentHashMap<>();
    private static final Map<String, AtomicLong> perClassNanos = new ConcurrentHashMap<>();
    private static final AtomicLong totalSelfNanos = new AtomicLong();
    private static final Set<String> skipped = new HashSet<>();
    private static final ThreadLocal<ThreadState> threadState = new ThreadLocal<ThreadState>() {

        @Override
        protected ThreadState initialValue() {
            return new ThreadState();
        }
    };

    private TransformerProfiler() {}

    public static void setEnabled(boolean value) {
        enabled = value;
    }

    /**
     * Wraps every transformer that is not wrapped yet. Called several times during start-up because coremods, Mixin and
     * FML keep registering transformers until the game starts.
     */
    public static synchronized void wrapNewTransformers() {
        if (!enabled || !LoadProfiler.isActive()) return;
        LaunchClassLoader loader = Launch.classLoader;
        if (loader == null) return;
        try {
            if (transformersField == null) {
                transformersField = LaunchClassLoader.class.getDeclaredField("transformers");
                transformersField.setAccessible(true);
                // Make sure the hot path classes are loaded before any transformer is wrapped.
                threadState.get();
                Class.forName(Stat.class.getName());
            }
            @SuppressWarnings("unchecked")
            List<IClassTransformer> list = (List<IClassTransformer>) transformersField.get(loader);
            int wrapped = 0;
            for (int i = 0; i < list.size(); i++) {
                IClassTransformer t = list.get(i);
                if (t == null || t instanceof ProfilingTransformer) continue;
                String name = t.getClass()
                    .getName();
                if (t instanceof IClassNameTransformer || isNeverWrap(name)) {
                    skipped.add(name);
                    continue;
                }
                list.set(i, new ProfilingTransformer(t));
                wrapped++;
            }
            if (wrapped > 0) {
                LoadProfiler.LOG.info("Profiling " + wrapped + " new class transformer(s), " + list.size() + " in total");
            }
        } catch (Throwable t) {
            enabled = false;
            LoadProfiler.LOG.warn("Could not wrap class transformers, transformer profiling disabled", t);
        }
    }

    private static boolean isNeverWrap(String name) {
        for (String prefix : NEVER_WRAP) {
            if (name.startsWith(prefix)) return true;
        }
        return false;
    }

    static long enter() {
        ThreadState st = threadState.get();
        st.push();
        return System.nanoTime();
    }

    static void exit(long start, String transformer, String className) {
        long total = System.nanoTime() - start;
        ThreadState st = threadState.get();
        long self = total - st.pop(total);
        if (self < 0) self = 0;
        Stat stat = stats.get(transformer);
        if (stat == null) {
            stat = new Stat();
            Stat prev = stats.putIfAbsent(transformer, stat);
            if (prev != null) stat = prev;
        }
        stat.calls.incrementAndGet();
        stat.selfNanos.addAndGet(self);
        if (self > stat.maxNanos) {
            stat.maxNanos = self;
            stat.maxClass = className;
        }
        totalSelfNanos.addAndGet(self);
        if (className != null) {
            AtomicLong c = perClassNanos.get(className);
            if (c == null) {
                c = new AtomicLong();
                AtomicLong prev = perClassNanos.putIfAbsent(className, c);
                if (prev != null) c = prev;
            }
            c.addAndGet(self);
        }
    }

    static long totalSelfNanos() {
        return totalSelfNanos.get();
    }

    private static List<Map.Entry<String, Stat>> sortedStats() {
        List<Map.Entry<String, Stat>> list = new ArrayList<>(stats.entrySet());
        list.sort((a, b) -> Long.compare(b.getValue().selfNanos.get(), a.getValue().selfNanos.get()));
        return list;
    }

    static void writeReport(PrintWriter out) {
        out.println("--- Class transformers (self time, nested class loading excluded) --------------");
        if (stats.isEmpty()) {
            out.println(enabled ? "No data." : "Disabled (-Dneiextend.profiler.transformers=false or wrapping failed).");
            out.println();
            return;
        }
        long total = totalSelfNanos.get();
        out.println(
            String.format(
                Locale.ROOT,
                "Total: %.1f ms over %d classes. Not measured (must not be wrapped): %s",
                LoadProfiler.toMillis(total),
                perClassNanos.size(),
                skipped));
        out.println(String.format(Locale.ROOT, "%-4s %-70s %10s %6s %8s %9s  %s", "#", "transformer", "self ms", "%", "calls", "max ms", "slowest class"));
        int i = 1;
        for (Map.Entry<String, Stat> e : sortedStats()) {
            Stat s = e.getValue();
            out.println(
                String.format(
                    Locale.ROOT,
                    "%-4d %-70s %10.1f %5.1f%% %8d %9.2f  %s",
                    i++,
                    LoadProfiler.trim(e.getKey(), 70),
                    LoadProfiler.toMillis(s.selfNanos.get()),
                    total == 0 ? 0 : 100.0 * s.selfNanos.get() / total,
                    s.calls.get(),
                    LoadProfiler.toMillis(s.maxNanos),
                    s.maxClass));
        }
        out.println();
        out.println("Top 50 classes by total transformation time:");
        List<Map.Entry<String, AtomicLong>> classes = new ArrayList<>(perClassNanos.entrySet());
        classes.sort((a, b) -> Long.compare(b.getValue().get(), a.getValue().get()));
        for (int j = 0; j < Math.min(50, classes.size()); j++) {
            Map.Entry<String, AtomicLong> e = classes.get(j);
            out.println(String.format(Locale.ROOT, "    %-90s %9.2f ms", LoadProfiler.trim(e.getKey(), 90), LoadProfiler.toMillis(e.getValue().get())));
        }
        out.println();
    }

    static void writeCsv(File file) {
        if (stats.isEmpty()) return;
        try (PrintWriter out = new PrintWriter(
            new OutputStreamWriter(new FileOutputStream(file), StandardCharsets.UTF_8))) {
            out.println("transformer,self_ms,calls,max_ms,slowest_class");
            for (Map.Entry<String, Stat> e : sortedStats()) {
                Stat s = e.getValue();
                out.println(
                    LoadProfiler.csv(e.getKey()) + ","
                        + String.format(Locale.ROOT, "%.3f", LoadProfiler.toMillis(s.selfNanos.get()))
                        + ","
                        + s.calls.get()
                        + ","
                        + String.format(Locale.ROOT, "%.3f", LoadProfiler.toMillis(s.maxNanos))
                        + ","
                        + LoadProfiler.csv(s.maxClass));
            }
        } catch (Exception e) {
            LoadProfiler.LOG.error("Failed to write " + file, e);
        }
    }

    static void logSummary() {
        if (stats.isEmpty()) return;
        LoadProfiler.LOG.info(
            String.format(
                Locale.ROOT,
                "Class transformers took %.1f ms in total for %d classes. Top 10:",
                LoadProfiler.toMillis(totalSelfNanos.get()),
                perClassNanos.size()));
        List<Map.Entry<String, Stat>> list = sortedStats();
        for (int i = 0; i < Math.min(10, list.size()); i++) {
            Map.Entry<String, Stat> e = list.get(i);
            LoadProfiler.LOG.info(
                String.format(
                    Locale.ROOT,
                    "  %2d. %-70s %10.1f ms",
                    i + 1,
                    e.getKey(),
                    LoadProfiler.toMillis(e.getValue().selfNanos.get())));
        }
    }

    static final class Stat {

        final AtomicLong calls = new AtomicLong();
        final AtomicLong selfNanos = new AtomicLong();
        volatile long maxNanos;
        volatile String maxClass;
    }

    /** Per thread stack of "time spent in nested transformers" accumulators. */
    static final class ThreadState {

        private long[] child = new long[32];
        private int depth;

        void push() {
            depth++;
            if (depth >= child.length) {
                long[] grown = new long[child.length * 2];
                System.arraycopy(child, 0, grown, 0, child.length);
                child = grown;
            }
            child[depth] = 0;
        }

        /** Returns the nested time of the current level and adds {@code total} to the parent level. */
        long pop(long total) {
            long nested = child[depth];
            depth--;
            if (depth > 0) child[depth] += total;
            return nested;
        }
    }
}
