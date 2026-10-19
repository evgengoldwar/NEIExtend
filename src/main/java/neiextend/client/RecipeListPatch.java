package neiextend.client;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import codechicken.nei.recipe.GuiRecipe;
import codechicken.nei.recipe.IRecipeHandler;
import codechicken.nei.recipe.RecipePageManager;
import neiextend.NEIExtend;
import neiextend.mixins.late.AccessorGuiRecipe;

/**
 * Takes recipes just hidden off the tab shown, in place. Showing the tab again would make NEI go through all its
 * recipes twice (its filters, then what is searched), which takes a while on big tabs; here the recipes are only
 * struck from the lists NEI already made, and the pages laid out again.
 *
 * <p>
 * The lists are in NEI's {@code SearchRecipeHandler}, which is not public: its fields are reached by name. They are
 * the same in all NEI 2.8 versions; if they are not found, {@link #remove} says so and the tab is shown again instead.
 */
final class RecipeListPatch {

    private static Field screenHandler, original, filtered, searched;
    private static boolean broken;

    private RecipeListPatch() {}

    /**
     * Strikes the recipes (indices in the handler) off the tab shown, keeping its page. Returns false when it could
     * not, or when the tab has no recipe left: the caller then shows the tab again the usual way.
     */
    @SuppressWarnings("unchecked")
    static boolean remove(GuiRecipe<?> gui, IRecipeHandler handler, Set<Integer> recipeIndices) {
        if (broken || !find()) {
            return false;
        }
        try {
            Object search = screenHandler.get(gui);
            if (search == null || original.get(search) != handler) {
                return false;
            }
            List<Integer> oldFiltered = (List<Integer>) filtered.get(search);
            List<Integer> oldSearched = (List<Integer>) searched.get(search);
            if (oldFiltered == null) {
                return false;
            }
            // Where each recipe of the old list is in the new one, -1 for those struck off.
            int[] moved = new int[oldFiltered.size()];
            List<Integer> newFiltered = new ArrayList<>(oldFiltered.size());
            for (int i = 0; i < oldFiltered.size(); i++) {
                Integer recipe = oldFiltered.get(i);
                if (recipeIndices.contains(recipe)) {
                    moved[i] = -1;
                } else {
                    moved[i] = newFiltered.size();
                    newFiltered.add(recipe);
                }
            }
            List<Integer> newSearched = null;
            if (oldSearched != null) {
                // What is searched holds places in the list above.
                newSearched = new ArrayList<>(oldSearched.size());
                for (int place : oldSearched) {
                    if (place >= 0 && place < moved.length && moved[place] >= 0) {
                        newSearched.add(moved[place]);
                    }
                }
            }
            if (newFiltered.isEmpty() || newSearched != null && newSearched.isEmpty()) {
                return false;
            }
            AccessorGuiRecipe accessor = (AccessorGuiRecipe) gui;
            RecipePageManager pages = accessor.neiextend$getHandlerPages();
            if (pages == null) {
                return false;
            }
            int page = Math.max(0, pages.getCurrentPageIndex());
            filtered.set(search, newFiltered);
            searched.set(search, newSearched);
            pages.rebuildPages();
            int last = Math.max(0, pages.getNumPages() - 1);
            pages.changePage(Math.min(page, last) - Math.max(0, pages.getCurrentPageIndex()));
            accessor.neiextend$refreshContainer();
            return true;
        } catch (ReflectiveOperationException | RuntimeException e) {
            broken = true;
            NEIExtend.LOG.warn("Could not take hidden recipes off the screen in place; it is shown again instead", e);
            return false;
        }
    }

    private static boolean find() {
        if (screenHandler != null) {
            return true;
        }
        try {
            Field handler = GuiRecipe.class.getDeclaredField("handler");
            Class<?> type = handler.getType();
            Field originalField = type.getDeclaredField("original");
            Field filteredField = type.getDeclaredField("filteredRecipes");
            Field searchedField = type.getDeclaredField("searchRecipes");
            for (Field field : new Field[] { handler, originalField, filteredField, searchedField }) {
                field.setAccessible(true);
            }
            original = originalField;
            filtered = filteredField;
            searched = searchedField;
            screenHandler = handler;
            return true;
        } catch (ReflectiveOperationException | RuntimeException e) {
            broken = true;
            NEIExtend.LOG
                .warn("NEI's recipe lists were not found; hidden recipes are taken off by showing the tab again");
            return false;
        }
    }
}
