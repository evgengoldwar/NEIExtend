package neiextend.mixins.late;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import codechicken.nei.recipe.GuiRecipe;
import codechicken.nei.recipe.GuiUsageRecipe;
import neiextend.client.RecipeScreenHooks;

/** Remembers what a usage screen was opened for, to look it up again when tabs are shown again. */
@Mixin(value = GuiUsageRecipe.class, remap = false)
public abstract class MixinGuiUsageRecipe {

    @Inject(method = "openRecipeGui", at = @At("RETURN"))
    private static void neiextend$remember(String inputId, Object[] ingredients, CallbackInfoReturnable<Boolean> cir) {
        // The screen it made is open by now.
        GuiScreen screen = Minecraft.getMinecraft().currentScreen;
        if (cir.getReturnValueZ() && screen instanceof GuiUsageRecipe) {
            RecipeScreenHooks.INSTANCE.rememberQuery((GuiRecipe<?>) screen, false, inputId, ingredients);
        }
    }
}
