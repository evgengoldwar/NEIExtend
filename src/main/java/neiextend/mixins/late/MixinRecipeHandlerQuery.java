package neiextend.mixins.late;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import codechicken.nei.recipe.IRecipeHandler;
import codechicken.nei.recipe.RecipeHandlerQuery;
import neiextend.client.HiddenStore;

/**
 * Leaves the hidden tabs out of every recipe and usage lookup, next to the ones in NEI's own hiddenhandlers.cfg. It
 * runs on NEI's lookup threads.
 */
@Mixin(value = RecipeHandlerQuery.class, remap = false)
public abstract class MixinRecipeHandlerQuery {

    @Inject(method = "isHidden", at = @At("HEAD"), cancellable = true)
    private void neiextend$isHidden(IRecipeHandler handler, CallbackInfoReturnable<Boolean> cir) {
        if (HiddenStore.INSTANCE.isTabHidden(handler)) {
            cir.setReturnValue(true);
        }
    }
}
