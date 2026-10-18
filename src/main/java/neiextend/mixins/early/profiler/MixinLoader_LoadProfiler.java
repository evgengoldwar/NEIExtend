package neiextend.mixins.early.profiler;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import cpw.mods.fml.common.Loader;
import cpw.mods.fml.common.discovery.ModDiscoverer;
import neiextend.profiler.LoadProfiler;

@Mixin(value = Loader.class, remap = false)
public class MixinLoader_LoadProfiler {

    /** Scanning of the mods folder: reading every jar and parsing all classes for annotations. */
    @Inject(method = "identifyMods", at = @At("HEAD"), require = 0)
    private void neiextend$identifyModsStart(CallbackInfoReturnable<ModDiscoverer> cir) {
        LoadProfiler.onIdentifyModsStart();
    }

    @Inject(method = "identifyMods", at = @At("RETURN"), require = 0)
    private void neiextend$identifyModsEnd(CallbackInfoReturnable<ModDiscoverer> cir) {
        LoadProfiler.onIdentifyModsEnd();
    }
}
