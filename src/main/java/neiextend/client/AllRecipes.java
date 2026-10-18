package neiextend.client;

import java.util.ArrayList;
import java.util.Collections;

import net.minecraft.client.Minecraft;

import codechicken.nei.recipe.GuiRecipe;
import codechicken.nei.recipe.ICraftingHandler;
import codechicken.nei.recipe.IRecipeHandler;
import codechicken.nei.recipe.IUsageHandler;
import codechicken.nei.recipe.TemplateRecipeHandler;
import neiextend.NEIExtend;
import neiextend.mixins.late.AccessorGuiCraftingRecipe;
import neiextend.mixins.late.AccessorGuiUsageRecipe;

/**
 * Opens one tab of NEI with all its recipes, as a click on a tab's title does in NEI, but for that tab alone. It
 * opens even when the tab is hidden; Escape comes back to the screen it was opened from.
 */
public final class AllRecipes {

    private AllRecipes() {}

    /** Opens the tab; false when it has no recipes to show. */
    public static boolean open(IRecipeHandler handler) {
        GuiRecipe<?> gui = null;
        try {
            if (handler instanceof ICraftingHandler) {
                ICraftingHandler all = handler instanceof TemplateRecipeHandler
                    ? ((TemplateRecipeHandler) handler).getAllRecipeHandler()
                    : ((ICraftingHandler) handler).getRecipeHandler("all");
                if (all != null && all.numRecipes() > 0) {
                    gui = AccessorGuiCraftingRecipe.neiextend$create(new ArrayList<>(Collections.singletonList(all)));
                }
            }
            if (gui == null && handler instanceof IUsageHandler) {
                IUsageHandler all = ((IUsageHandler) handler).getUsageHandler("all");
                if (all != null && all.numRecipes() > 0) {
                    gui = AccessorGuiUsageRecipe.neiextend$create(new ArrayList<>(Collections.singletonList(all)));
                }
            }
        } catch (Throwable t) {
            NEIExtend.LOG.warn("Could not load all recipes of {}", HandlerCatalog.idOf(handler), t);
        }
        if (gui == null) {
            return false;
        }
        Minecraft.getMinecraft()
            .displayGuiScreen(gui);
        return true;
    }
}
