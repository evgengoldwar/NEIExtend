package neiextend.client;

import net.minecraft.client.gui.GuiMainMenu;
import net.minecraftforge.client.event.GuiOpenEvent;
import net.minecraftforge.common.MinecraftForge;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import neiextend.profiler.LoadProfiler;

/** Detects the end of client start-up: the main menu is shown and the game loop runs its first tick. */
public class LoadProfilerClientEvents {

    private boolean mainMenuSeen;

    public static void register() {
        LoadProfilerClientEvents handler = new LoadProfilerClientEvents();
        MinecraftForge.EVENT_BUS.register(handler);
        FMLCommonHandler.instance()
            .bus()
            .register(handler);
    }

    @SubscribeEvent
    public void onGuiOpen(GuiOpenEvent event) {
        if (!mainMenuSeen && event.gui instanceof GuiMainMenu) {
            mainMenuSeen = true;
            LoadProfiler.milestone("Main menu opened");
        }
    }

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase == TickEvent.Phase.START && LoadProfiler.isActive()) {
            LoadProfiler.finish("First client tick (game is interactive)");
        }
    }
}
