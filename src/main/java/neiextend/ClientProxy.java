package neiextend;

import java.io.File;

import net.minecraftforge.common.MinecraftForge;

import codechicken.nei.api.API;
import codechicken.nei.guihook.GuiContainerManager;
import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.common.event.FMLLoadCompleteEvent;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;
import neiextend.client.HiddenRecipeFilter;
import neiextend.client.HiddenStore;
import neiextend.client.KeyHandler;
import neiextend.client.RecipeScreenHooks;

public class ClientProxy extends CommonProxy {

    @Override
    public void preInit(FMLPreInitializationEvent event) {
        HiddenStore.INSTANCE.setFile(new File(event.getModConfigurationDirectory(), "neiextend.json"));
    }

    @Override
    public void init(FMLInitializationEvent event) {
        KeyHandler.register();
        FMLCommonHandler.instance()
            .bus()
            .register(new KeyHandler());
        MinecraftForge.EVENT_BUS.register(RecipeScreenHooks.INSTANCE);

        // The buttons on the recipe screen: drawn, given tooltips and kept clear of the item panel through NEI's own
        // hooks. Clicks come from a mixin, as the recipe screen handles them before NEI's hooks see them.
        GuiContainerManager.addDrawHandler(RecipeScreenHooks.INSTANCE);
        GuiContainerManager.addTooltipHandler(RecipeScreenHooks.INSTANCE);
        API.registerNEIGuiHandler(RecipeScreenHooks.INSTANCE);

        // Hidden recipes are left out by a recipe filter, the way NEI's own presets leave recipes out.
        API.addRecipeFilter(HiddenRecipeFilter.INSTANCE);
    }

    @Override
    public void loadComplete(FMLLoadCompleteEvent event) {
        // Hidden recipes are stored with their items, which can only be read once every mod registered its items.
        HiddenStore.INSTANCE.load();
    }
}
