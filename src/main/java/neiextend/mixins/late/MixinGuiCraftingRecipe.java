package neiextend.mixins.late;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import codechicken.nei.recipe.GuiCraftingRecipe;
import codechicken.nei.recipe.GuiRecipe;
import neiextend.client.RecipeScreenHooks;

/** Remembers what a recipe screen was opened for, to look it up again when tabs are shown again. */
@Mixin(value = GuiCraftingRecipe.class, remap = false)
public abstract class MixinGuiCraftingRecipe {

    @Inject(method = "createRecipeGui", at = @At("RETURN"))
    private static void neiextend$remember(String outputId, boolean open, Object[] results,
        CallbackInfoReturnable<GuiRecipe<?>> cir) {
        if (cir.getReturnValue() != null) {
            RecipeScreenHooks.INSTANCE.rememberQuery(cir.getReturnValue(), true, outputId, results);
        }
    }
}
