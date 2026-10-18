package neiextend.profiler;

import net.minecraft.launchwrapper.IClassTransformer;

/**
 * Wraps a class transformer registered in the LaunchClassLoader and measures how long it takes for every class.
 */
public final class ProfilingTransformer implements IClassTransformer {

    final IClassTransformer delegate;
    final String delegateName;

    ProfilingTransformer(IClassTransformer delegate) {
        this.delegate = delegate;
        this.delegateName = delegate.getClass()
            .getName();
    }

    @Override
    public byte[] transform(String name, String transformedName, byte[] basicClass) {
        if (!LoadProfiler.isActive()) {
            return delegate.transform(name, transformedName, basicClass);
        }
        long start = TransformerProfiler.enter();
        try {
            return delegate.transform(name, transformedName, basicClass);
        } finally {
            TransformerProfiler.exit(start, delegateName, transformedName);
        }
    }

    @Override
    public String toString() {
        return "Profiling[" + delegate + "]";
    }
}
