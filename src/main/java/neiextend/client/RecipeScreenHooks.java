package neiextend.client;

import java.awt.Point;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

import net.minecraft.client.Minecraft;
import net.minecraft.client.audio.PositionedSoundRecord;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.inventory.GuiContainer;
import net.minecraft.client.resources.I18n;
import net.minecraft.inventory.Slot;
import net.minecraft.item.ItemStack;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.client.event.GuiOpenEvent;

import org.lwjgl.opengl.GL11;

import codechicken.lib.gui.GuiDraw;
import codechicken.lib.vec.Rectangle4i;
import codechicken.nei.ItemsTooltipLineHandler;
import codechicken.nei.NEICPH;
import codechicken.nei.NEIClientConfig;
import codechicken.nei.PositionedStack;
import codechicken.nei.RecipeSearchField;
import codechicken.nei.Widget;
import codechicken.nei.api.INEIGuiHandler;
import codechicken.nei.api.IRecipeFilter;
import codechicken.nei.guihook.IContainerDrawHandler;
import codechicken.nei.guihook.IContainerInputHandler;
import codechicken.nei.guihook.IContainerTooltipHandler;
import codechicken.nei.recipe.GuiCraftingRecipe;
import codechicken.nei.recipe.GuiRecipe;
import codechicken.nei.recipe.GuiUsageRecipe;
import codechicken.nei.recipe.HandlerInfo;
import codechicken.nei.recipe.IRecipeHandler;
import codechicken.nei.recipe.RecipeHandlerRef;
import codechicken.nei.recipe.RecipePageManager;
import codechicken.nei.recipe.TemplateRecipeHandler;
import codechicken.nei.scroll.ScrollContainer;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import neiextend.client.gui.GuiNEIExtend;
import neiextend.client.gui.ui.Icons;
import neiextend.client.gui.ui.Smooth;
import neiextend.client.gui.ui.Theme;
import neiextend.mixins.late.AccessorGuiRecipe;

/**
 * The mod on NEI's recipe screen: a small bar of buttons on the right of the window (hide this tab, put it first,
 * pick recipes to hide, open the mod's window), a red frame over the recipe under the mouse while picking, and a note
 * under the
 * window after something was hidden, with a way to take it back.
 */
public final class RecipeScreenHooks
    implements IContainerDrawHandler, IContainerTooltipHandler, IContainerInputHandler, INEIGuiHandler {

    public static final RecipeScreenHooks INSTANCE = new RecipeScreenHooks();

    /** The buttons of the bar, top to bottom. */
    private enum Tool {

        HIDE_TAB(Icons.EYE_OFF),
        PUT_FIRST(Icons.STAR),
        PICK_RECIPES(Icons.TARGET),
        GROUP(Icons.GROUP),
        OPEN_WINDOW(Icons.GEAR);

        final String[] icon;

        Tool(String[] icon) {
            this.icon = icon;
        }
    }

    private static final int BUTTON = 16, GAP = 2, PAD = 3;
    private static final int BAR_WIDTH = BUTTON + PAD * 2;
    private static final int BAR_HEIGHT = Tool.values().length * (BUTTON + GAP) - GAP + PAD * 2;
    /** How long the note under the window stays, and how long it takes to fade out at the end (ms). */
    private static final long TOAST_MS = 7000, TOAST_FADE_MS = 400;
    /** How long a grouped recipe shows each of its recipes, in ticks (as NEI cycles its permutations). */
    private static final int GROUP_CYCLE_TICKS = 20;

    /** What a recipe screen was opened for, to look it up again when hidden tabs are shown again. */
    private static final class Query {

        final boolean crafting;
        final String id;
        final Object[] args;

        Query(boolean crafting, String id, Object[] args) {
            this.crafting = crafting;
            this.id = id;
            this.args = args.clone();
        }
    }

    /** The note under the window: what was hidden, and how to take it back. */
    private static final class Toast {

        final GuiRecipe<?> gui;
        final String text;
        final Runnable undo;
        final long shownAt = System.currentTimeMillis();
        int x0, y0, x1, y1, undoX0;

        Toast(GuiRecipe<?> gui, String text, Runnable undo) {
            this.gui = gui;
            this.text = text;
            this.undo = undo;
        }

        double alpha() {
            long left = TOAST_MS - (System.currentTimeMillis() - shownAt);
            return Math.max(0, Math.min(1, left / (double) TOAST_FADE_MS));
        }
    }

    /**
     * Where the player is in the tab shown: its page, and what is searched in it. NEI shows a tab again from its first
     * page with the search closed; this puts them back after.
     */
    private static final class View {

        final int page;
        /** What is typed in NEI's search over the tab, or null when the search is closed. */
        final String search;
        final int searchButton;

        View(GuiRecipe<?> gui) {
            RecipePageManager pages = ((AccessorGuiRecipe) gui).neiextend$getHandlerPages();
            RecipeSearchField field = AccessorGuiRecipe.neiextend$getSearchField();
            this.page = pages == null ? 0 : Math.max(0, pages.getCurrentPageIndex());
            this.search = field.isVisible() ? field.text() : null;
            this.searchButton = AccessorGuiRecipe.neiextend$getToggleSearch().state;
        }

        /** Puts it back, on the same tab shown again; a page gone (its last recipe was hidden) gives the last one. */
        void restore(GuiRecipe<?> gui) {
            AccessorGuiRecipe accessor = (AccessorGuiRecipe) gui;
            if (search != null && gui.getHandler() instanceof TemplateRecipeHandler) {
                RecipeSearchField field = AccessorGuiRecipe.neiextend$getSearchField();
                field.setVisible(true);
                field.setText(search);
                AccessorGuiRecipe.neiextend$getToggleSearch().state = searchButton;
                // NEI filters on a thread of its own; done now too, so the page is counted among the recipes found.
                AccessorGuiRecipe.neiextend$getUpdateFilter()
                    .execute();
            }
            RecipePageManager pages = accessor.neiextend$getHandlerPages();
            if (pages == null) {
                return;
            }
            accessor.neiextend$refreshContainer();
            int last = Math.max(0, pages.getNumPages() - 1);
            pages.changePage(Math.min(page, last) - Math.max(0, pages.getCurrentPageIndex()));
            accessor.neiextend$refreshContainer();
        }
    }

    private final Map<GuiRecipe<?>, Query> queries = new WeakHashMap<>();
    /** Ticks counted on the recipe screen, for showing grouped recipes in turn. */
    private int groupTicks;
    /** The list of a grouped slot's stacks in the tooltip, kept while the same slot is hovered. */
    private ItemsTooltipLineHandler variantsLine;
    private Object variantsKey;
    private final Smooth[] light = new Smooth[Tool.values().length];
    private final Smooth undoLight = new Smooth(0);
    /** Picking recipes to hide: a click on a recipe hides it. */
    private boolean picking;
    private Toast toast;

    private RecipeScreenHooks() {
        for (int i = 0; i < light.length; i++) {
            light[i] = new Smooth(0);
        }
    }

    // Where things are.

    private static int barLeft(GuiRecipe<?> gui) {
        return gui.guiLeft + gui.xSize;
    }

    private static int barTop(GuiRecipe<?> gui) {
        return gui.guiTop + 4;
    }

    private static int buttonTop(GuiRecipe<?> gui, Tool tool) {
        return barTop(gui) + PAD + tool.ordinal() * (BUTTON + GAP);
    }

    private static Tool toolAt(GuiRecipe<?> gui, int mouseX, int mouseY) {
        int x = barLeft(gui) + PAD;
        for (Tool tool : Tool.values()) {
            int y = buttonTop(gui, tool);
            if (Theme.inside(mouseX, mouseY, x, y, x + BUTTON, y + BUTTON)) {
                return tool;
            }
        }
        return null;
    }

    private static boolean overBar(GuiRecipe<?> gui, int mouseX, int mouseY) {
        int x = barLeft(gui), y = barTop(gui);
        return Theme.inside(mouseX, mouseY, x, y, x + BAR_WIDTH, y + BAR_HEIGHT);
    }

    private static ScrollContainer container(GuiRecipe<?> gui) {
        return ((AccessorGuiRecipe) gui).neiextend$getContainer();
    }

    /** The recipe under the mouse, or null. */
    private static Widget recipeAt(GuiRecipe<?> gui, int mouseX, int mouseY) {
        ScrollContainer container = container(gui);
        if (container == null || !container.boundsInside()
            .contains(mouseX, mouseY)) {
            return null;
        }
        Widget widget = container.getWidgetUnderMouse(mouseX, mouseY);
        return RecipeWidgets.refOf(widget) != null ? widget : null;
    }

    private static boolean overUndo(Toast toast, int mouseX, int mouseY) {
        return toast != null && toast.alpha() > 0.5
            && Theme.inside(mouseX, mouseY, toast.undoX0, toast.y0, toast.x1, toast.y1);
    }

    private Toast currentToast(GuiRecipe<?> gui) {
        if (toast != null && (toast.gui != gui || toast.alpha() <= 0)) {
            toast = null;
        }
        return toast;
    }

    // Input.

    /** A click on the recipe screen, before the screen sees it; true when it was ours. */
    public boolean mouseClicked(GuiRecipe<?> gui, int mouseX, int mouseY, int button) {
        Toast shown = currentToast(gui);
        if (button == 0 && overUndo(shown, mouseX, mouseY)) {
            toast = null;
            shown.undo.run();
            click();
            return true;
        }
        Tool tool = toolAt(gui, mouseX, mouseY);
        if (tool != null || overBar(gui, mouseX, mouseY)) {
            if (tool != null && button == 0) {
                press(gui, tool);
            }
            return true;
        }
        if (picking) {
            if (button == 1) {
                // A right click anywhere ends the picking.
                picking = false;
                click();
                return true;
            }
            Widget recipe = recipeAt(gui, mouseX, mouseY);
            if (recipe != null && button == 0) {
                hideRecipe(gui, recipe);
                return true;
            }
        }
        return false;
    }

    private void press(GuiRecipe<?> gui, Tool tool) {
        click();
        switch (tool) {
            case HIDE_TAB:
                hideTab(gui);
                break;
            case PUT_FIRST:
                togglePutFirst(gui);
                break;
            case PICK_RECIPES:
                picking = !picking;
                break;
            case GROUP:
                toggleGrouping(gui);
                break;
            case OPEN_WINDOW:
                picking = false;
                Minecraft.getMinecraft()
                    .displayGuiScreen(new GuiNEIExtend(gui));
                break;
        }
    }

    private static void click() {
        Minecraft.getMinecraft()
            .getSoundHandler()
            .playSound(PositionedSoundRecord.func_147674_a(new ResourceLocation("gui.button.press"), 1.0F));
    }

    @SubscribeEvent
    public void onGuiOpen(GuiOpenEvent event) {
        if (!(event.gui instanceof GuiRecipe)) {
            picking = false;
        }
    }

    // Hiding.

    private void hideTab(GuiRecipe<?> gui) {
        IRecipeHandler handler = gui.getHandler();
        if (handler == null) {
            return;
        }
        Runnable restore = snapshot(gui);
        String name = HandlerCatalog.nameOf(handler);
        String id = HandlerCatalog.idOf(handler);
        HiddenStore.INSTANCE.hideTab(handler);
        GuiRecipe<?> shown = removeTab(gui, gui.recipetype);
        if (shown != null) {
            toast = new Toast(shown, I18n.format("neiextend.toast.tab_hidden", name), () -> {
                HiddenStore.INSTANCE.showTab(id);
                restore.run();
            });
        }
    }

    /** Turns showing recipes differing only by material as one on or off, for the tabs open now too. */
    private void toggleGrouping(GuiRecipe<?> gui) {
        boolean on = !HiddenStore.INSTANCE.groupRecipes();
        HiddenStore.INSTANCE.setGroupRecipes(on);
        regroup(gui, on);
        toast = new Toast(gui, I18n.format(on ? "neiextend.toast.grouped" : "neiextend.toast.ungrouped"), () -> {
            HiddenStore.INSTANCE.setGroupRecipes(!on);
            regroup(gui, !on);
        });
    }

    private static void regroup(GuiRecipe<?> gui, boolean on) {
        for (IRecipeHandler handler : handlers(gui)) {
            if (on) {
                RecipeGroups.group(handler);
            } else {
                RecipeGroups.ungroup(handler);
            }
        }
        refreshPage(gui);
    }

    /** Every second the grouped recipes on the screen show the next of their group, but the one under the mouse. */
    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        GuiScreen screen = Minecraft.getMinecraft().currentScreen;
        if (!(screen instanceof GuiRecipe) || ++groupTicks % GROUP_CYCLE_TICKS != 0 || GuiScreen.isShiftKeyDown()) {
            return;
        }
        GuiRecipe<?> gui = (GuiRecipe<?>) screen;
        IRecipeHandler handler = gui.getHandler();
        List<RecipeGroups.Group> groups = RecipeGroups.groupsOf(handler);
        if (groups.isEmpty()) {
            return;
        }
        Point mouse = GuiDraw.getMousePosition();
        RecipeHandlerRef hovered = RecipeWidgets.refOf(recipeAt(gui, mouse.x, mouse.y));
        for (RecipeGroups.Group group : groups) {
            if (hovered == null || hovered.handler != handler || hovered.recipeIndex != group.index()) {
                RecipeGroups.step(handler, group, 1);
            }
        }
    }

    /** Puts the tab shown before all others, or back in NEI's order when it already is first. */
    private void togglePutFirst(GuiRecipe<?> gui) {
        IRecipeHandler handler = gui.getHandler();
        if (handler == null) {
            return;
        }
        String id = HandlerCatalog.idOf(handler);
        String name = HandlerCatalog.nameOf(handler);
        int was = HiddenStore.INSTANCE.pinPosition(id);
        if (was >= 0) {
            HiddenStore.INSTANCE.unpin(id);
        } else {
            HiddenStore.INSTANCE.pin(handler, 0);
        }
        refreshPage(gui);
        String text = I18n.format(was >= 0 ? "neiextend.toast.unpinned" : "neiextend.toast.pinned", name);
        toast = new Toast(gui, text, () -> {
            if (was >= 0) {
                HiddenStore.INSTANCE.pin(handler, was);
            } else {
                HiddenStore.INSTANCE.unpin(id);
            }
            refreshPage(gui);
        });
    }

    private void hideRecipe(GuiRecipe<?> gui, Widget widget) {
        RecipeHandlerRef ref = RecipeWidgets.refOf(widget);
        if (ref == null) {
            return;
        }
        Runnable restore = snapshot(gui);
        HiddenStore.HiddenRecipe hidden = HiddenStore.INSTANCE.hideRecipe(ref.handler, ref.recipeIndex);
        if (hidden == null) {
            return;
        }
        click();
        // A grouped recipe shows another of its group, so the group stays.
        RecipeGroups.skipHidden(ref.handler, ref.recipeIndex);
        GuiRecipe<?> shown = reloadRecipes(gui);
        if (shown != null) {
            toast = new Toast(shown, I18n.format("neiextend.toast.recipe_hidden", hidden.tab), () -> {
                HiddenStore.INSTANCE.showRecipe(hidden);
                restore.run();
            });
        }
    }

    /** Puts the screen back as it is now: its tabs, the one shown, its page and what is searched in it. */
    private Runnable snapshot(GuiRecipe<?> gui) {
        List<IRecipeHandler> tabs = new ArrayList<>(handlers(gui));
        int index = gui.recipetype;
        View view = new View(gui);
        return () -> {
            List<IRecipeHandler> handlers = handlers(gui);
            handlers.clear();
            handlers.addAll(tabs);
            showTab(gui, index);
            view.restore(gui);
        };
    }

    /** Shows the tab again after a change, on the same page and with the same search. */
    private static void refreshPage(GuiRecipe<?> gui) {
        View view = new View(gui);
        gui.forceRefreshPage();
        view.restore(gui);
    }

    @SuppressWarnings("unchecked")
    private static List<IRecipeHandler> handlers(GuiRecipe<?> gui) {
        return (List<IRecipeHandler>) (List<?>) gui.currenthandlers;
    }

    /** Shows the tab at the index, from its first page. */
    private static void showTab(GuiRecipe<?> gui, int index) {
        List<IRecipeHandler> handlers = handlers(gui);
        if (handlers.isEmpty()) {
            return;
        }
        AccessorGuiRecipe accessor = (AccessorGuiRecipe) gui;
        accessor.neiextend$setRecipePage(Math.max(0, Math.min(index, handlers.size() - 1)));
        boolean several = handlers.size() > 1;
        GuiButton prev = accessor.neiextend$getPrevType(), next = accessor.neiextend$getNextType();
        if (prev != null && next != null) {
            prev.visible = next.visible = several;
        }
    }

    /**
     * Takes the tab at the index off the screen and shows the one now there; closes the screen when it was the last.
     * Returns the screen still open, or null.
     */
    private static GuiRecipe<?> removeTab(GuiRecipe<?> gui, int index) {
        List<IRecipeHandler> handlers = handlers(gui);
        if (index >= 0 && index < handlers.size()) {
            handlers.remove(index);
        }
        if (handlers.isEmpty()) {
            close(gui);
            return null;
        }
        showTab(gui, index);
        return gui;
    }

    /** Back to the screen the recipes were opened from, as NEI does on Escape. */
    private static void close(GuiRecipe<?> gui) {
        Minecraft.getMinecraft()
            .displayGuiScreen(gui.getFirstScreenGeneral());
        NEICPH.sendRequestContainer();
    }

    /**
     * Shows the recipes of the tab again after some were hidden or shown, on the same page and keeping what is
     * searched; a tab left without recipes goes away. Returns the screen still open, or null.
     */
    private static GuiRecipe<?> reloadRecipes(GuiRecipe<?> gui) {
        IRecipeHandler current = gui.getHandler();
        if (current != null && !hasVisibleRecipe(current)) {
            return removeTab(gui, gui.recipetype);
        }
        refreshPage(gui);
        return gui;
    }

    /** Whether the handler has a recipe NEI's filters (ours among them) let through. */
    private static boolean hasVisibleRecipe(IRecipeHandler handler) {
        IRecipeFilter filter = handler instanceof TemplateRecipeHandler ? GuiRecipe.getRecipeListFilter() : null;
        int count = handler.numRecipes();
        if (filter == null) {
            return count > 0;
        }
        for (int i = 0; i < count; i++) {
            if (filter.matches(handler, i)) {
                return true;
            }
        }
        return false;
    }

    /** Remembers what the screen was opened for (called by the mixins on NEI's lookups). */
    public void rememberQuery(GuiRecipe<?> gui, boolean crafting, String id, Object[] args) {
        if (gui != null && id != null && args != null) {
            queries.put(gui, new Query(crafting, id, args));
            // Not for a screen opened on one recipe (a bookmark): it is already on that recipe's page.
            if (!"recipeId".equals(id) && groupAll(gui)) {
                gui.forceRefreshPage();
            }
        }
    }

    /**
     * Puts the recipes differing only by material together in each tab of the screen, when that is on; true when some
     * were. Only screens are grouped: NEI's other lookups (bookmarks, crafting chains) keep every recipe in place.
     */
    private static boolean groupAll(GuiRecipe<?> gui) {
        boolean grouped = false;
        for (IRecipeHandler handler : handlers(gui)) {
            RecipeGroups.group(handler);
            grouped |= !RecipeGroups.groupsOf(handler)
                .isEmpty();
        }
        return grouped;
    }

    /**
     * The mod's window was closed back to the recipe screen after changes: its tabs are looked up again, so the tabs
     * and recipes shown again come back, and the hidden ones go.
     */
    public void refresh(GuiRecipe<?> gui) {
        toast = null;
        IRecipeHandler current = gui.getHandler();
        String currentId = HandlerCatalog.idOf(current);
        View view = current == null ? null : new View(gui);
        List<IRecipeHandler> handlers = handlers(gui);
        Query query = queries.get(gui);
        if (query != null) {
            List<? extends IRecipeHandler> found = query.crafting
                ? GuiCraftingRecipe.getCraftingHandlers(query.id, query.args.clone())
                : GuiUsageRecipe.getUsageHandlers(query.id, query.args.clone());
            handlers.clear();
            handlers.addAll(found);
        } else {
            // Opened some other way: what was hidden still goes, but nothing can come back.
            handlers.removeIf(h -> HiddenStore.INSTANCE.isTabHidden(h) || !hasVisibleRecipe(h));
            handlers.sort(TabOrder.around(NEIClientConfig.HANDLER_COMPARATOR));
        }
        if (handlers.isEmpty()) {
            close(gui);
            return;
        }
        groupAll(gui);
        int index = -1;
        for (int i = 0; i < handlers.size(); i++) {
            if (HandlerCatalog.idOf(handlers.get(i))
                .equals(currentId)) {
                index = i;
                break;
            }
        }
        showTab(gui, Math.max(0, index));
        if (index >= 0 && view != null) {
            // Still the same tab: on the same page, with the same search.
            view.restore(gui);
        }
    }

    // Drawing, in the screen's pixels, after the screen and NEI's panels.

    @Override
    public void onPreDraw(GuiContainer gui) {}

    @Override
    public void renderObjects(GuiContainer container, int mouseX, int mouseY) {
        if (!(container instanceof GuiRecipe)) {
            return;
        }
        GuiRecipe<?> gui = (GuiRecipe<?>) container;
        if (gui.getHandler() == null) {
            return;
        }
        GL11.glPushAttrib(GL11.GL_ENABLE_BIT | GL11.GL_COLOR_BUFFER_BIT | GL11.GL_CURRENT_BIT);
        GL11.glDisable(GL11.GL_LIGHTING);
        GL11.glDisable(GL11.GL_DEPTH_TEST);
        try {
            if (picking) {
                drawPicked(gui, mouseX, mouseY);
            }
            drawBar(gui, mouseX, mouseY);
            drawNote(gui, mouseX, mouseY);
        } finally {
            GL11.glPopAttrib();
            GL11.glColor4f(1f, 1f, 1f, 1f);
        }
    }

    @Override
    public void postRenderObjects(GuiContainer gui, int mouseX, int mouseY) {}

    @Override
    public void renderSlotUnderlay(GuiContainer gui, Slot slot) {}

    @Override
    public void renderSlotOverlay(GuiContainer gui, Slot slot) {}

    /** A red frame over the recipe under the mouse, the one a click would hide. */
    private void drawPicked(GuiRecipe<?> gui, int mouseX, int mouseY) {
        Widget recipe = recipeAt(gui, mouseX, mouseY);
        if (recipe == null) {
            return;
        }
        Rectangle4i inside = container(gui).boundsInside();
        int x0 = Math.max(recipe.x, inside.x), y0 = Math.max(recipe.y, inside.y);
        int x1 = Math.min(recipe.x + recipe.w, inside.x + inside.w),
            y1 = Math.min(recipe.y + recipe.h, inside.y + inside.h);
        if (x1 <= x0 || y1 <= y0) {
            return;
        }
        Theme.fill(x0, y0, x1, y1, 0x38E5534B);
        Theme.outline(x0, y0, x1, y1, Theme.DANGER);
        Theme.outline(x0 + 1, y0 + 1, x1 - 1, y1 - 1, 0x60E5534B);
    }

    private void drawBar(GuiRecipe<?> gui, int mouseX, int mouseY) {
        int x0 = barLeft(gui), y0 = barTop(gui), x1 = x0 + BAR_WIDTH, y1 = y0 + BAR_HEIGHT;
        Theme.shadow(x0, y0, x1, y1, 3);
        Theme.roundRect(x0, y0, x1, y1, 0xF60B1424);
        Theme.roundOutline(x0, y0, x1, y1, Theme.FRAME);
        // Joined to the window: no border on its left.
        Theme.fill(x0, y0 + 1, x0 + 1, y1 - 1, 0xF60B1424);
        Tool hovered = toolAt(gui, mouseX, mouseY);
        for (Tool tool : Tool.values()) {
            int bx = x0 + PAD, by = buttonTop(gui, tool);
            boolean active = tool == Tool.PICK_RECIPES && picking;
            boolean grouping = tool == Tool.GROUP && HiddenStore.INSTANCE.groupRecipes();
            boolean first = tool == Tool.PUT_FIRST && HiddenStore.INSTANCE.pinPosition(gui.getHandler()) >= 0;
            double lit = light[tool.ordinal()].update(tool == hovered ? 1 : 0, 22);
            int background, border;
            if (active) {
                background = Theme.blend(0xFF8A2A2E, 0xFFB3364A, lit);
                border = Theme.DANGER;
            } else if (first) {
                background = Theme.blend(0xFF3A3218, 0xFF4A4020, lit);
                border = Theme.WARN;
            } else if (grouping) {
                background = Theme.blend(0xFF13305C, 0xFF183A6E, lit);
                border = Theme.ACCENT;
            } else if (tool == Tool.HIDE_TAB) {
                background = Theme.blend(Theme.CONTROL, 0xFF3A2326, lit);
                border = Theme.blend(0xFF2A3D5C, Theme.DANGER, lit);
            } else if (tool == Tool.PUT_FIRST) {
                background = Theme.blend(Theme.CONTROL, 0xFF3A3218, lit);
                border = Theme.blend(0xFF2A3D5C, Theme.WARN, lit);
            } else {
                background = Theme.blend(Theme.CONTROL, Theme.CONTROL_HOVER, lit);
                border = Theme.blend(0xFF2A3D5C, Theme.ACCENT, lit);
            }
            if (lit > 0.01) {
                Theme.glow(
                    bx,
                    by,
                    bx + BUTTON,
                    by + BUTTON,
                    tool == Tool.OPEN_WINDOW ? Theme.ACCENT : tool == Tool.PUT_FIRST ? Theme.WARN : Theme.DANGER,
                    lit * 0.4);
            }
            Theme.roundBox(bx, by, bx + BUTTON, by + BUTTON, background, border);
            int color = active ? Theme.TEXT : first ? Theme.WARN : Theme.blend(Theme.TEXT_MUTED, Theme.TEXT, lit);
            Icons.drawCentered(tool.icon, bx, by, bx + BUTTON, by + BUTTON, color);
            if (tool == Tool.OPEN_WINDOW
                && (HiddenStore.INSTANCE.tabCount() > 0 || HiddenStore.INSTANCE.hasHiddenRecipes())) {
                // Something is hidden: a dot, the window lists it.
                Theme.fill(bx + BUTTON - 5, by + BUTTON - 5, bx + BUTTON - 1, by + BUTTON - 1, Theme.PANEL);
                Theme.fill(bx + BUTTON - 4, by + BUTTON - 4, bx + BUTTON - 2, by + BUTTON - 2, Theme.ACCENT);
            }
        }
    }

    /** Under the window: the note after hiding something, or what to do while picking. */
    private void drawNote(GuiRecipe<?> gui, int mouseX, int mouseY) {
        FontRenderer font = Minecraft.getMinecraft().fontRenderer;
        Toast shown = currentToast(gui);
        int centerX = gui.guiLeft + gui.xSize / 2, y0 = gui.guiTop + gui.ySize + 3;
        if (shown != null) {
            double alpha = shown.alpha();
            String undo = I18n.format("neiextend.toast.undo");
            int undoWidth = Icons.width(Icons.SMALL_UNDO) + 4 + font.getStringWidth(undo) + 12;
            int maxText = Math.max(40, gui.xSize + 60 - undoWidth);
            String text = Theme.ellipsize(font, shown.text, maxText);
            int width = 18 + font.getStringWidth(text) + 8 + undoWidth;
            shown.x0 = centerX - width / 2;
            shown.x1 = shown.x0 + width;
            shown.y0 = y0;
            shown.y1 = y0 + 16;
            shown.undoX0 = shown.x1 - undoWidth;
            double lit = undoLight.update(overUndo(shown, mouseX, mouseY) ? 1 : 0, 22);
            int panel = fade(0xF60B1424, alpha);
            Theme.shadow(shown.x0, shown.y0, shown.x1, shown.y1, 3);
            Theme.roundRect(shown.x0, shown.y0, shown.x1, shown.y1, panel);
            Theme.roundOutline(shown.x0, shown.y0, shown.x1, shown.y1, fade(Theme.FRAME, alpha));
            Icons.draw(Icons.SMALL_CHECK, shown.x0 + 6, shown.y0 + 5, fade(Theme.SUCCESS, alpha));
            if (alpha > 0.1) {
                font.drawString(text, shown.x0 + 18, shown.y0 + 4, fade(Theme.TEXT, alpha));
            }
            Theme.fill(shown.undoX0, shown.y0 + 3, shown.undoX0 + 1, shown.y1 - 3, fade(Theme.BORDER, alpha));
            if (lit > 0.01) {
                Theme.fill(
                    shown.undoX0 + 1,
                    shown.y0 + 1,
                    shown.x1 - 1,
                    shown.y1 - 1,
                    fade(Theme.blend(0x00FFFFFF, 0x18FFFFFF, lit), alpha));
            }
            int undoColor = fade(Theme.blend(Theme.ACCENT, 0xFF8CC2FF, lit), alpha);
            Icons.draw(Icons.SMALL_UNDO, shown.undoX0 + 6, shown.y0 + 5, undoColor);
            if (alpha > 0.1) {
                font.drawString(undo, shown.undoX0 + 6 + Icons.width(Icons.SMALL_UNDO) + 4, shown.y0 + 4, undoColor);
            }
            return;
        }
        if (picking) {
            String text = I18n.format("neiextend.pick.hint");
            int width = font.getStringWidth(text) + 24;
            int x0 = centerX - width / 2;
            Theme.shadow(x0, y0, x0 + width, y0 + 16, 3);
            Theme.roundRect(x0, y0, x0 + width, y0 + 16, 0xF63A1418);
            Theme.roundOutline(x0, y0, x0 + width, y0 + 16, Theme.DANGER);
            Icons.draw(Icons.SMALL_MOUSE, x0 + 6, y0 + 5, Theme.DANGER);
            font.drawString(text, x0 + 17, y0 + 4, Theme.TEXT);
        }
    }

    /** The color with its alpha scaled by {@code alpha} (0 to 1); text needs at least a little to be drawn. */
    private static int fade(int color, double alpha) {
        int a = (int) Math.round((color >>> 24) * alpha);
        return Math.max(a, 4) << 24 | color & 0xFFFFFF;
    }

    // Tooltips, drawn by NEI in its own style.

    @Override
    public List<String> handleTooltip(GuiContainer container, int mouseX, int mouseY, List<String> currenttip) {
        if (!(container instanceof GuiRecipe)) {
            return currenttip;
        }
        GuiRecipe<?> gui = (GuiRecipe<?>) container;
        if (gui.getHandler() == null) {
            return currenttip;
        }
        Tool tool = toolAt(gui, mouseX, mouseY);
        if (tool != null) {
            List<String> lines = new ArrayList<>();
            switch (tool) {
                case HIDE_TAB:
                    lines.add(I18n.format("neiextend.tool.hide_tab"));
                    lines.add(EnumChatFormatting.YELLOW + HandlerCatalog.nameOf(gui.getHandler()));
                    lines.add(EnumChatFormatting.GRAY + I18n.format("neiextend.tool.hide_tab_text"));
                    break;
                case PUT_FIRST:
                    int position = HiddenStore.INSTANCE.pinPosition(gui.getHandler());
                    lines.add(I18n.format(position >= 0 ? "neiextend.tool.unpin" : "neiextend.tool.pin"));
                    lines.add(EnumChatFormatting.YELLOW + HandlerCatalog.nameOf(gui.getHandler()));
                    lines.add(
                        EnumChatFormatting.GRAY
                            + (position >= 0 ? I18n.format("neiextend.tool.unpin_text", position + 1)
                                : I18n.format("neiextend.tool.pin_text")));
                    break;
                case GROUP:
                    lines.add(
                        I18n.format(
                            HiddenStore.INSTANCE.groupRecipes() ? "neiextend.tool.group_on" : "neiextend.tool.group"));
                    lines.add(EnumChatFormatting.GRAY + I18n.format("neiextend.tool.group_text"));
                    break;
                case PICK_RECIPES:
                    lines.add(I18n.format(picking ? "neiextend.tool.pick_on" : "neiextend.tool.pick"));
                    lines.add(EnumChatFormatting.GRAY + I18n.format("neiextend.tool.pick_text"));
                    break;
                case OPEN_WINDOW:
                    lines.add(I18n.format("neiextend.tool.window"));
                    lines.add(
                        EnumChatFormatting.GRAY + I18n.format(
                            "neiextend.tool.window_text",
                            HiddenStore.INSTANCE.tabCount(),
                            HiddenStore.INSTANCE.recipes()
                                .size()));
                    break;
            }
            return lines;
        }
        if (overBar(gui, mouseX, mouseY)) {
            return new ArrayList<>();
        }
        Toast shown = currentToast(gui);
        if (overUndo(shown, mouseX, mouseY)) {
            return new ArrayList<>(Collections.singletonList(I18n.format("neiextend.toast.undo_tip")));
        }
        if (picking) {
            Widget recipe = recipeAt(gui, mouseX, mouseY);
            if (recipe != null) {
                List<String> lines = new ArrayList<>();
                lines.add(EnumChatFormatting.RED + I18n.format("neiextend.pick.recipe"));
                String result = resultName(recipe);
                if (result != null) {
                    lines.add(EnumChatFormatting.GRAY + result);
                }
                return lines;
            }
        }
        return currenttip;
    }

    private static String resultName(Widget widget) {
        try {
            RecipeHandlerRef ref = RecipeWidgets.refOf(widget);
            if (ref == null || ref.handler.getResultStack(ref.recipeIndex) == null) {
                return null;
            }
            ItemStack stack = ref.handler.getResultStack(ref.recipeIndex).item;
            return stack == null ? null : stack.getDisplayName();
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Over a stack of a grouped recipe: which of its recipes is shown, and what the stack is in each of them, as NEI
     * lists the permutations a slot accepts.
     */
    @Override
    public List<String> handleItemTooltip(GuiContainer container, ItemStack stack, int mouseX, int mouseY,
        List<String> currenttip) {
        if (!(container instanceof GuiRecipe) || picking) {
            return currenttip;
        }
        Widget widget = recipeAt((GuiRecipe<?>) container, mouseX, mouseY);
        RecipeHandlerRef ref = RecipeWidgets.refOf(widget);
        RecipeGroups.Group group = ref == null ? null : RecipeGroups.groupAt(ref.handler, ref.recipeIndex);
        if (group == null) {
            return currenttip;
        }
        currenttip
            .add(EnumChatFormatting.GRAY + I18n.format("neiextend.group.variant", group.shown() + 1, group.size()));
        PositionedStack slot = slotAt(ref, widget, mouseX, mouseY);
        if (slot != null) {
            List<ItemStack> variants = group.slot(slot.relx, slot.rely);
            if (variants.size() > 1) {
                Object key = Arrays.asList(group, slot.relx, slot.rely);
                if (variantsLine == null || !key.equals(variantsKey)) {
                    variantsLine = new ItemsTooltipLineHandler(
                        I18n.format("neiextend.group.variants", variants.size()),
                        new ArrayList<>(variants));
                    variantsKey = key;
                }
                currenttip.add(GuiDraw.TOOLTIP_HANDLER + GuiDraw.getTipLineId(variantsLine));
            }
        }
        return currenttip;
    }

    /** The stack of the widget's recipe under the mouse, or null. */
    private static PositionedStack slotAt(RecipeHandlerRef ref, Widget widget, int mouseX, int mouseY) {
        HandlerInfo info = HandlerCatalog.infoOf(ref.handler);
        int x = mouseX - widget.x, y = mouseY - widget.y - (info == null ? 0 : info.getYShift());
        List<PositionedStack> stacks = new ArrayList<>();
        try {
            stacks.addAll(ref.handler.getIngredientStacks(ref.recipeIndex));
            stacks.addAll(ref.handler.getOtherStacks(ref.recipeIndex));
            PositionedStack result = ref.handler.getResultStack(ref.recipeIndex);
            if (result != null) {
                stacks.add(result);
            }
        } catch (Throwable t) {
            return null;
        }
        for (PositionedStack pStack : stacks) {
            if (pStack.contains(x, y)) {
                return pStack;
            }
        }
        return null;
    }

    // Shift and the mouse wheel over a grouped recipe show the next or the one before, before NEI uses them.

    @Override
    public boolean mouseScrolled(GuiContainer container, int mouseX, int mouseY, int scrolled) {
        if (!(container instanceof GuiRecipe) || !GuiScreen.isShiftKeyDown()) {
            return false;
        }
        RecipeHandlerRef ref = RecipeWidgets.refOf(recipeAt((GuiRecipe<?>) container, mouseX, mouseY));
        RecipeGroups.Group group = ref == null ? null : RecipeGroups.groupAt(ref.handler, ref.recipeIndex);
        if (group == null) {
            return false;
        }
        RecipeGroups.step(ref.handler, group, scrolled > 0 ? -1 : 1);
        return true;
    }

    @Override
    public boolean keyTyped(GuiContainer gui, char keyChar, int keyCode) {
        return false;
    }

    @Override
    public void onKeyTyped(GuiContainer gui, char keyChar, int keyID) {}

    @Override
    public boolean lastKeyTyped(GuiContainer gui, char keyChar, int keyID) {
        return false;
    }

    @Override
    public boolean mouseClicked(GuiContainer gui, int mouseX, int mouseY, int button) {
        return false;
    }

    @Override
    public void onMouseClicked(GuiContainer gui, int mouseX, int mouseY, int button) {}

    @Override
    public void onMouseUp(GuiContainer gui, int mouseX, int mouseY, int button) {}

    @Override
    public void onMouseScrolled(GuiContainer gui, int mouseX, int mouseY, int scrolled) {}

    @Override
    public void onMouseDragged(GuiContainer gui, int mouseX, int mouseY, int button, long heldTime) {}

    // Keeping NEI's item panel clear of the bar.

    @Override
    public boolean hideItemPanelSlot(GuiContainer container, int x, int y, int w, int h) {
        if (!(container instanceof GuiRecipe)) {
            return false;
        }
        GuiRecipe<?> gui = (GuiRecipe<?>) container;
        int x0 = barLeft(gui) - 2, y0 = barTop(gui) - 2, x1 = x0 + BAR_WIDTH + 4, y1 = y0 + BAR_HEIGHT + 4;
        return x < x1 && x + w > x0 && y < y1 && y + h > y0;
    }
}
