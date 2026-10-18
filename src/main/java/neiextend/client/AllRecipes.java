package neiextend.client;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;

import net.minecraft.client.Minecraft;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;

import codechicken.nei.ItemList;
import codechicken.nei.ItemStackSet;
import codechicken.nei.api.ItemInfo;
import codechicken.nei.recipe.GuiRecipe;
import codechicken.nei.recipe.ICraftingHandler;
import codechicken.nei.recipe.IRecipeHandler;
import codechicken.nei.recipe.IUsageHandler;
import codechicken.nei.recipe.Recipe.RecipeId;
import codechicken.nei.recipe.TemplateRecipeHandler;
import neiextend.NEIExtend;
import neiextend.mixins.late.AccessorGuiCraftingRecipe;
import neiextend.mixins.late.AccessorGuiUsageRecipe;

/**
 * Opens one tab of NEI with all its recipes, as a click on a tab's title does in NEI, but for that tab alone. It
 * opens even when the tab is hidden; Escape comes back to the screen it was opened from.
 * <p>
 * Some tabs can't list all their recipes at once: they only answer for one item (NEI's own "all" shows nothing for
 * them). Those are asked about every item of the game instead, the ones hidden in NEI too, and what they find is
 * put together in one tab.
 */
public final class AllRecipes {

    /** Every item, hidden ones included, and the item list of NEI it was made from (it is made again with it). */
    private static List<ItemStack> allItems;
    private static List<ItemStack> allItemsFrom;

    private AllRecipes() {}

    /** Opens the tab; false when it has no recipes to show. */
    public static boolean open(IRecipeHandler handler) {
        GuiRecipe<?> gui = null;
        if (handler instanceof ICraftingHandler) {
            ICraftingHandler all = (ICraftingHandler) load(handler, true);
            if (all != null) {
                gui = AccessorGuiCraftingRecipe.neiextend$create(new ArrayList<>(Collections.singletonList(all)));
            }
        }
        if (gui == null && handler instanceof IUsageHandler) {
            IUsageHandler all = (IUsageHandler) load(handler, false);
            if (all != null) {
                gui = AccessorGuiUsageRecipe.neiextend$create(new ArrayList<>(Collections.singletonList(all)));
            }
        }
        if (gui == null) {
            return false;
        }
        Minecraft.getMinecraft()
            .displayGuiScreen(gui);
        return true;
    }

    /** All recipes (or uses) of the handler: its own "all" first, item by item when that is empty. Null for none. */
    private static IRecipeHandler load(IRecipeHandler handler, boolean crafting) {
        IRecipeHandler all = null;
        try {
            if (crafting) {
                all = handler instanceof TemplateRecipeHandler ? ((TemplateRecipeHandler) handler).getAllRecipeHandler()
                    : ((ICraftingHandler) handler).getRecipeHandler("all");
            } else {
                all = ((IUsageHandler) handler).getUsageHandler("all");
            }
        } catch (Throwable t) {
            NEIExtend.LOG.warn("Could not load all recipes of {}", HandlerCatalog.idOf(handler), t);
        }
        if ((all == null || all.numRecipes() == 0) && handler instanceof TemplateRecipeHandler) {
            try {
                all = byItems((TemplateRecipeHandler) handler, crafting);
            } catch (Throwable t) {
                NEIExtend.LOG.warn("Could not look up the recipes of {} item by item", HandlerCatalog.idOf(handler), t);
            }
        }
        return all != null && all.numRecipes() > 0 ? all : null;
    }

    /**
     * Every recipe (or use) of the handler, found by asking it about each item of NEI's item list, on NEI's lookup
     * threads; each recipe is kept once, in the order of the item list. Null when it found none.
     */
    private static TemplateRecipeHandler byItems(TemplateRecipeHandler base, boolean crafting)
        throws InterruptedException, ExecutionException {
        List<ItemStack> items = allItems();
        if (items.isEmpty() || ItemList.forkJoinPool == null) {
            return null;
        }
        List<TemplateRecipeHandler> found = ItemList.forkJoinPool.submit(
            () -> items.parallelStream()
                .map(stack -> lookUp(base, crafting, stack))
                .filter(Objects::nonNull)
                .collect(Collectors.toList()))
            .get();
        TemplateRecipeHandler combined = base.newInstance();
        Set<String> seen = new HashSet<>();
        for (TemplateRecipeHandler each : found) {
            for (int i = 0; i < each.arecipes.size(); i++) {
                String key;
                try {
                    key = RecipeId.of(each, i)
                        .toJsonObject()
                        .toString();
                } catch (Throwable t) {
                    key = each.getClass()
                        .getName() + "#"
                        + System.identityHashCode(each.arecipes.get(i));
                }
                if (seen.add(key)) {
                    combined.arecipes.add(each.arecipes.get(i));
                }
            }
        }
        return combined.arecipes.isEmpty() ? null : combined;
    }

    /**
     * NEI's item list, then the items NEI hides from it: a recipe making a hidden item is still a recipe of the tab.
     * Client thread only.
     */
    @SuppressWarnings("unchecked")
    private static List<ItemStack> allItems() {
        List<ItemStack> shown = ItemList.items;
        if (shown == null) {
            return Collections.emptyList();
        }
        if (allItems != null && allItemsFrom == shown) {
            return allItems;
        }
        List<ItemStack> all = new ArrayList<>(shown);
        ItemStackSet known = new ItemStackSet();
        known.addAll(shown);
        for (Item item : (Iterable<Item>) Item.itemRegistry) {
            if (item == null) {
                continue;
            }
            List<ItemStack> variants = new ArrayList<>(ItemInfo.itemOverrides.get(item));
            try {
                if (variants.isEmpty()) {
                    item.getSubItems(item, null, variants);
                }
            } catch (Throwable ignored) {
                // An item that can't list its kinds without a creative tab: only NEI's own list has it.
            }
            variants.addAll(ItemInfo.itemVariants.get(item));
            for (ItemStack stack : variants) {
                if (stack != null && stack.getItem() != null && !known.contains(stack)) {
                    known.add(stack);
                    all.add(stack);
                }
            }
        }
        allItems = all;
        allItemsFrom = shown;
        return all;
    }

    /** The handler's recipes (or uses) of one item, or null for none. */
    private static TemplateRecipeHandler lookUp(TemplateRecipeHandler base, boolean crafting, ItemStack stack) {
        try {
            TemplateRecipeHandler handler = base.newInstance();
            if (crafting) {
                handler.loadCraftingRecipes("item", stack);
            } else {
                handler.loadUsageRecipes("item", stack);
            }
            return handler.arecipes.isEmpty() ? null : handler;
        } catch (Throwable t) {
            return null;
        }
    }
}
