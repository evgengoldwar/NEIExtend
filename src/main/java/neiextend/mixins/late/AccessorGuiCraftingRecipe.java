package neiextend.mixins.late;

import java.util.ArrayList;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

import codechicken.nei.recipe.GuiCraftingRecipe;
import codechicken.nei.recipe.ICraftingHandler;

/** NEI's recipe screen made straight from given tabs, without looking them up. */
@Mixin(value = GuiCraftingRecipe.class, remap = false)
public interface AccessorGuiCraftingRecipe {

    @Invoker("<init>")
    static GuiCraftingRecipe neiextend$create(ArrayList<ICraftingHandler> handlers) {
        throw new AssertionError();
    }
}
