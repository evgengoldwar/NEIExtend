package neiextend.mixins;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import javax.annotation.Nonnull;

import com.gtnewhorizon.gtnhmixins.ILateMixinLoader;
import com.gtnewhorizon.gtnhmixins.LateMixin;

import cpw.mods.fml.relauncher.FMLLaunchHandler;

@LateMixin
public class LateMixinsLoader implements ILateMixinLoader {

    @Override
    public String getMixinConfig() {
        return "mixins.neiextend.late.json";
    }

    @Nonnull
    @Override
    public List<String> getMixins(Set<String> loadedMods) {
        List<String> mixins = new ArrayList<>();
        // A client mod for NEI: nothing to do on a server or without NEI.
        if (FMLLaunchHandler.side()
            .isClient() && loadedMods.contains("NotEnoughItems")) {
            mixins.add("AccessorGuiRecipe");
            mixins.add("MixinGuiRecipe");
            mixins.add("MixinRecipeHandlerQuery");
            mixins.add("MixinGuiCraftingRecipe");
            mixins.add("MixinGuiUsageRecipe");
        }
        return mixins;
    }
}
