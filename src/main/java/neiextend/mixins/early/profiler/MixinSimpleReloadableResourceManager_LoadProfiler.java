package neiextend.mixins.early.profiler;

import java.util.List;

import net.minecraft.client.resources.IResourceManager;
import net.minecraft.client.resources.IResourceManagerReloadListener;
import net.minecraft.client.resources.SimpleReloadableResourceManager;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

import neiextend.profiler.LoadProfiler;

@Mixin(SimpleReloadableResourceManager.class)
public class MixinSimpleReloadableResourceManager_LoadProfiler {

    @Inject(method = "reloadResources", at = @At("HEAD"))
    private void neiextend$reloadStart(List<?> packs, CallbackInfo ci) {
        LoadProfiler.onResourceReloadStart();
    }

    @Inject(method = "reloadResources", at = @At("TAIL"))
    private void neiextend$reloadEnd(List<?> packs, CallbackInfo ci) {
        LoadProfiler.onResourceReloadEnd();
    }

    /** Times every reload listener: texture stitching, sounds, fonts, language, models of mods... */
    @WrapOperation(
        method = { "notifyReloadListeners", "registerReloadListener" },
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/resources/IResourceManagerReloadListener;onResourceManagerReload(Lnet/minecraft/client/resources/IResourceManager;)V"),
        require = 0)
    private void neiextend$timeListener(IResourceManagerReloadListener listener, IResourceManager manager,
        Operation<Void> original) {
        if (!LoadProfiler.isActive()) {
            original.call(listener, manager);
            return;
        }
        long start = System.nanoTime();
        try {
            original.call(listener, manager);
        } finally {
            LoadProfiler.onReloadListener(listener, System.nanoTime() - start);
        }
    }
}
