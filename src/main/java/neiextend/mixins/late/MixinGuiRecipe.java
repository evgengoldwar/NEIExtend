package neiextend.mixins.late;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import codechicken.nei.recipe.GuiRecipe;
import neiextend.client.RecipeScreenHooks;

/**
 * Clicks on the mod's buttons beside the recipe screen, and on recipes while picking one to hide, are taken before
 * the screen handles them: it would otherwise open the clicked item's recipes.
 */
@Mixin(value = GuiRecipe.class, remap = false)
public abstract class MixinGuiRecipe {

    // Both names: the method keeps Minecraft's own name in a dev environment and its SRG name in the game.
    @Inject(method = { "mouseClicked", "func_73864_a" }, at = @At("HEAD"), cancellable = true, require = 1)
    private void neiextend$mouseClicked(int mouseX, int mouseY, int button, CallbackInfo ci) {
        if (RecipeScreenHooks.INSTANCE.mouseClicked((GuiRecipe<?>) (Object) this, mouseX, mouseY, button)) {
            ci.cancel();
        }
    }
}
