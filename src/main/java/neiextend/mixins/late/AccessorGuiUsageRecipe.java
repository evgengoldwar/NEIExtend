package neiextend.mixins.late;

import java.util.ArrayList;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

import codechicken.nei.recipe.GuiUsageRecipe;
import codechicken.nei.recipe.IUsageHandler;

/** NEI's usage screen made straight from given tabs, without looking them up. */
@Mixin(value = GuiUsageRecipe.class, remap = false)
public interface AccessorGuiUsageRecipe {

    @Invoker("<init>")
    static GuiUsageRecipe neiextend$create(ArrayList<IUsageHandler> handlers) {
        throw new AssertionError();
    }
}
