package neiextend.mixins.late;

import java.util.Comparator;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import codechicken.nei.recipe.GuiRecipe;
import codechicken.nei.recipe.IRecipeHandler;
import neiextend.client.RecipeScreenHooks;
import neiextend.client.TabOrder;

/**
 * Clicks on the mod's buttons beside the recipe screen, and on recipes while picking one to hide (with the drag and
 * the release that follow), are taken before the screen handles them: it would otherwise open the clicked item's
 * recipes. When the screen sorts its tabs again,
 * the ones the player put first stay first.
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

    /** While picking recipes, a drag over them adds them to the ones hidden when the button is let go. */
    @Inject(method = { "mouseClickMove", "func_146273_a" }, at = @At("HEAD"), cancellable = true, require = 1)
    private void neiextend$mouseDragged(int mouseX, int mouseY, int button, long heldTime, CallbackInfo ci) {
        if (RecipeScreenHooks.INSTANCE.mouseDragged((GuiRecipe<?>) (Object) this, mouseX, mouseY, button)) {
            ci.cancel();
        }
    }

    @Inject(method = { "mouseMovedOrUp", "func_146286_b" }, at = @At("HEAD"), cancellable = true, require = 1)
    private void neiextend$mouseReleased(int mouseX, int mouseY, int state, CallbackInfo ci) {
        if (RecipeScreenHooks.INSTANCE.mouseReleased((GuiRecipe<?>) (Object) this, mouseX, mouseY, state)) {
            ci.cancel();
        }
    }

    @ModifyArg(
        method = "forceRefreshPage",
        at = @At(value = "INVOKE", target = "Ljava/util/ArrayList;sort(Ljava/util/Comparator;)V"))
    private Comparator<IRecipeHandler> neiextend$order(Comparator<IRecipeHandler> neiOrder) {
        return TabOrder.around(neiOrder);
    }
}
