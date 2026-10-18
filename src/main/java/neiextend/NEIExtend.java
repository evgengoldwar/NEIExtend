package neiextend;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import cpw.mods.fml.common.Mod;
import cpw.mods.fml.common.SidedProxy;
import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.common.event.FMLLoadCompleteEvent;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;
import cpw.mods.fml.common.event.FMLServerStartedEvent;
import neiextend.profiler.LoadProfiler;

/**
 * NEIExtend: hides the recipe tabs (handlers) and single recipes of NEI that a player has no use for, from a screen in
 * the game. Purely client side: a server does not need it, and a client with it can join any server.
 */
@Mod(
    modid = NEIExtend.MODID,
    version = Tags.VERSION,
    name = "NEIExtend",
    acceptedMinecraftVersions = "[1.7.10]",
    acceptableRemoteVersions = "*",
    dependencies = "required-after:NotEnoughItems")
public class NEIExtend {

    public static final String MODID = "neiextend";
    public static final Logger LOG = LogManager.getLogger(MODID);

    @SidedProxy(clientSide = "neiextend.ClientProxy", serverSide = "neiextend.CommonProxy")
    public static CommonProxy proxy;

    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        proxy.preInit(event);
    }

    @Mod.EventHandler
    public void init(FMLInitializationEvent event) {
        proxy.init(event);
    }

    @Mod.EventHandler
    public void loadComplete(FMLLoadCompleteEvent event) {
        proxy.loadComplete(event);
    }

    @Mod.EventHandler
    public void serverStarted(FMLServerStartedEvent event) {
        // A client ends the load profile at its first tick, before any server starts: this ends a dedicated server's.
        LoadProfiler.finish("Server started");
    }
}
