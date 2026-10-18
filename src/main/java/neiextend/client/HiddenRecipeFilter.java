package neiextend.client;

import codechicken.nei.api.IRecipeFilter;
import codechicken.nei.api.IRecipeFilter.IRecipeFilterProvider;
import codechicken.nei.recipe.IRecipeHandler;

/**
 * Leaves the hidden recipes out of NEI's recipe screen. NEI asks it about each recipe of each tab it shows; a tab
 * with all its recipes hidden is left out too. NEI only filters handlers built on its template, which nearly all are.
 */
public final class HiddenRecipeFilter implements IRecipeFilterProvider, IRecipeFilter {

    public static final HiddenRecipeFilter INSTANCE = new HiddenRecipeFilter();

    private HiddenRecipeFilter() {}

    @Override
    public IRecipeFilter getRecipeFilter() {
        // No filter at all while nothing is hidden: NEI then skips filtering.
        return !HiddenStore.INSTANCE.hasHiddenRecipes() ? null : this;
    }

    @Override
    public boolean matches(IRecipeHandler handler, int recipeIndex) {
        return !HiddenStore.INSTANCE.isRecipeHidden(handler, recipeIndex);
    }
}
