package neiextend.mixins.late;

import net.minecraft.client.gui.GuiButton;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

import codechicken.nei.recipe.GuiRecipe;
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

    /** Shows the tab at the index of the screen's tabs, from its first page. */
    @Invoker("setRecipePage")
    void neiextend$setRecipePage(int index);
}
