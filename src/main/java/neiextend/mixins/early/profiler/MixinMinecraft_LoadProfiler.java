package neiextend.mixins.early.profiler;

import net.minecraft.client.Minecraft;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import neiextend.profiler.LoadProfiler;
import neiextend.profiler.TransformerProfiler;

@Mixin(Minecraft.class)
public class MixinMinecraft_LoadProfiler {

    @Inject(method = "startGame", at = @At("HEAD"))
    private void neiextend$startGameHead(CallbackInfo ci) {
        TransformerProfiler.wrapNewTransformers();
        LoadProfiler.milestone("Minecraft.startGame() begin (window, OpenGL, game settings)");
    }

    @Inject(
        method = "startGame",
        at = @At(
            value = "INVOKE",
            target = "Lcpw/mods/fml/client/FMLClientHandler;beginMinecraftLoading(Lnet/minecraft/client/Minecraft;Ljava/util/List;Lnet/minecraft/client/resources/IReloadableResourceManager;)V",
            remap = false),
        require = 0)
    private void neiextend$beforeBeginLoading(CallbackInfo ci) {
        LoadProfiler.milestone("FML beginMinecraftLoading (mod discovery, construction, preInit, first resource load)");
    }

    @Inject(
        method = "startGame",
        at = @At(
            value = "INVOKE",
            target = "Lcpw/mods/fml/client/FMLClientHandler;beginMinecraftLoading(Lnet/minecraft/client/Minecraft;Ljava/util/List;Lnet/minecraft/client/resources/IReloadableResourceManager;)V",
            shift = At.Shift.AFTER,
            remap = false),
        require = 0)
    private void neiextend$afterBeginLoading(CallbackInfo ci) {
        LoadProfiler.milestone("FML beginMinecraftLoading done, vanilla engine setup (textures, renderers, sounds)");
    }

    @Inject(
        method = "startGame",
        at = @At(
            value = "INVOKE",
            target = "Lcpw/mods/fml/client/FMLClientHandler;finishMinecraftLoading()V",
            remap = false),
        require = 0)
    private void neiextend$beforeFinishLoading(CallbackInfo ci) {
        LoadProfiler.milestone("FML finishMinecraftLoading (init, IMC, postInit, loadComplete, resource reload)");
    }

    @Inject(
        method = "startGame",
        at = @At(
            value = "INVOKE",
            target = "Lcpw/mods/fml/client/FMLClientHandler;finishMinecraftLoading()V",
            shift = At.Shift.AFTER,
            remap = false),
        require = 0)
    private void neiextend$afterFinishLoading(CallbackInfo ci) {
        LoadProfiler.milestone("FML finishMinecraftLoading done");
    }

    @Inject(method = "startGame", at = @At("TAIL"))
    private void neiextend$startGameTail(CallbackInfo ci) {
        LoadProfiler.milestone("Minecraft.startGame() end");
    }
}
