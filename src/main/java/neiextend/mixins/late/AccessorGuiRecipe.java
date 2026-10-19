package neiextend.mixins.late;

import net.minecraft.client.gui.GuiButton;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

import codechicken.nei.Button;
import codechicken.nei.RecipeSearchField;
import codechicken.nei.RestartableTask;
import codechicken.nei.recipe.GuiRecipe;
import codechicken.nei.recipe.RecipePageManager;
import codechicken.nei.scroll.ScrollContainer;

/** What the recipe screen keeps to itself, for showing it again with tabs or recipes taken away. */
@Mixin(value = GuiRecipe.class, remap = false)
public interface AccessorGuiRecipe {

    /** The scrolled box with the recipes of the page. */
    @Accessor("container")
    ScrollContainer neiextend$getContainer();

    @Accessor("prevtype")
    GuiButton neiextend$getPrevType();

    @Accessor("nexttype")
    GuiButton neiextend$getNextType();

    /** The pages of the tab shown. */
    @Accessor("handlerPages")
    RecipePageManager neiextend$getHandlerPages();

    /** Shows the tab at the index of the screen's tabs, from its first page, with the search closed. */
    @Invoker("setRecipePage")
    void neiextend$setRecipePage(int index);

    /** Lays the recipes of the page out again, when they or the page changed. */
    @Invoker("refreshContainer")
    void neiextend$refreshContainer();

    /** The search over the recipes of the tab, one for all recipe screens. */
    @Accessor("searchField")
    static RecipeSearchField neiextend$getSearchField() {
        throw new AssertionError();
    }

    /** The button opening and closing the search. */
    @Accessor("toggleSearch")
    static Button neiextend$getToggleSearch() {
        throw new AssertionError();
    }

    /** Filters the recipes of the screen open by what is searched. */
    @Accessor("updateFilter")
    static RestartableTask neiextend$getUpdateFilter() {
        throw new AssertionError();
    }
}
