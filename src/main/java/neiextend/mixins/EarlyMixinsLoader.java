package neiextend.mixins;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.gtnewhorizon.gtnhmixins.IEarlyMixinLoader;

import cpw.mods.fml.relauncher.FMLLaunchHandler;
import cpw.mods.fml.relauncher.IFMLLoadingPlugin;
import neiextend.profiler.LoadProfiler;
import neiextend.profiler.TransformerProfiler;

/**
 * The coremod is only here for the load profiler: it is the earliest point a mod can run code, so the profiler sees
 * the whole start-up of the game, and its mixins have to be in place before FML starts loading mods.
 */
@IFMLLoadingPlugin.MCVersion("1.7.10")
@IFMLLoadingPlugin.Name("NEIExtend")
public class EarlyMixinsLoader implements IFMLLoadingPlugin, IEarlyMixinLoader {

    public EarlyMixinsLoader() {
        LoadProfiler.bootstrap();
    }

    @Override
    public String[] getASMTransformerClass() {
        return null;
    }

    @Override
    public String getModContainerClass() {
        return null;
    }

    @Override
    public String getSetupClass() {
        return null;
    }

    @Override
    public void injectData(Map<String, Object> data) {
        LoadProfiler.milestone("NEIExtend coremod injectData");
        TransformerProfiler.wrapNewTransformers();
    }

    @Override
    public String getAccessTransformerClass() {
        return null;
    }

    @Override
    public String getMixinConfig() {
        return "mixins.neiextend.early.json";
    }

    @Override
    public List<String> getMixins(Set<String> loadedCoreMods) {
        List<String> mixins = new ArrayList<>();
        if (!LoadProfiler.isActive()) return mixins;
        if (FMLLaunchHandler.side()
            .isClient()) {
            mixins.add("profiler.MixinMinecraft_LoadProfiler");
            mixins.add("profiler.MixinSimpleReloadableResourceManager_LoadProfiler");
        }
        return mixins;
    }
}
