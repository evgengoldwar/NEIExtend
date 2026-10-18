package neiextend.mixins.late;

import java.util.Comparator;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import codechicken.nei.recipe.IRecipeHandler;
import codechicken.nei.recipe.RecipeHandlerQuery;
import neiextend.client.HiddenStore;
import neiextend.client.TabOrder;

/**
 * Leaves the hidden tabs out of every recipe and usage lookup, next to the ones in NEI's own hiddenhandlers.cfg (on
 * NEI's lookup threads), and puts the tabs the player put first before the others.
 */
@Mixin(value = RecipeHandlerQuery.class, remap = false)
public abstract class MixinRecipeHandlerQuery {

    @Inject(method = "isHidden", at = @At("HEAD"), cancellable = true)
    private void neiextend$isHidden(IRecipeHandler handler, CallbackInfoReturnable<Boolean> cir) {
        if (HiddenStore.INSTANCE.isTabHidden(handler)) {
            cir.setReturnValue(true);
        }
    }

    @ModifyArg(
        method = "getRecipeHandlersParallel",
        at = @At(value = "INVOKE", target = "Ljava/util/ArrayList;sort(Ljava/util/Comparator;)V"))
    private Comparator<IRecipeHandler> neiextend$order(Comparator<IRecipeHandler> neiOrder) {
        return TabOrder.around(neiOrder);
    }
}
