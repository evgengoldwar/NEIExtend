package neiextend.mixins.early.profiler;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import cpw.mods.fml.common.LoadController;
import cpw.mods.fml.common.LoaderState;
import cpw.mods.fml.common.ModContainer;
import cpw.mods.fml.common.event.FMLEvent;
import neiextend.profiler.LoadProfiler;

@Mixin(value = LoadController.class, remap = false)
public class MixinLoadController_LoadProfiler {

    @Inject(method = "transition", at = @At("HEAD"), require = 0)
    private void neiextend$transition(LoaderState desiredState, boolean forceState, CallbackInfo ci) {
        LoadProfiler.onLoaderStateTransition(desiredState.name());
    }

    /** Called once for every mod and every FML event (construction, preInit, init, IMC, postInit, loadComplete...). */
    @Inject(method = "sendEventToModContainer", at = @At("HEAD"), require = 0)
    private void neiextend$modEventStart(FMLEvent stateEvent, ModContainer mc, CallbackInfo ci) {
        LoadProfiler.onModEventStart();
    }

    @Inject(method = "sendEventToModContainer", at = @At("RETURN"), require = 0)
    private void neiextend$modEventEnd(FMLEvent stateEvent, ModContainer mc, CallbackInfo ci) {
        LoadProfiler.onModEventEnd(mc.getModId(), mc.getName(), stateEvent.getEventType());
    }
}
