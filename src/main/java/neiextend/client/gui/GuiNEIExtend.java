package neiextend.client.gui;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import net.minecraft.client.audio.PositionedSoundRecord;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.resources.I18n;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ResourceLocation;

import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;
import org.lwjgl.opengl.GL11;

import codechicken.nei.drawable.DrawableResource;
import codechicken.nei.recipe.GuiRecipe;
import codechicken.nei.recipe.HandlerInfo;
import codechicken.nei.recipe.IRecipeHandler;
import codechicken.nei.recipe.StackInfo;
import neiextend.Tags;
import neiextend.client.AllRecipes;
import neiextend.client.HandlerCatalog;
import neiextend.client.HiddenStore;
import neiextend.client.KeyHandler;
import neiextend.client.RecipeScreenHooks;
import neiextend.client.gui.ui.Draw;
import neiextend.client.gui.ui.FlatButton;
import neiextend.client.gui.ui.FlatTextField;
import neiextend.client.gui.ui.Icons;
import neiextend.client.gui.ui.ScaledScreen;
import neiextend.client.gui.ui.ScrollbarDrag;
import neiextend.client.gui.ui.Smooth;
import neiextend.client.gui.ui.Theme;
import neiextend.client.gui.ui.WindowHeader;

/**
 * The mod's window: every recipe tab of NEI with a switch to hide or show it and a star to put it first, the order of
 * the tabs put first, the recipes hidden so far with a way to bring each back, and how it all works. Opened from the
 * bar beside NEI's recipe screen, or with its key; closing it
 * goes back to the recipe screen it was opened from, looked up again with the changes.
 */
public class GuiNEIExtend extends ScaledScreen {

    public static final int WIDTH = 560, HEIGHT = 372;
    private static final int MENU = 96, PAGE_HEIGHT = 25;
    private static final int TAB_ROW = 26, RECIPE_ROW = 30, CONTROLS = 18, SWITCH = 24, NOTE = 14;
    private static final int ORDER_BUTTON = 16;
    /** A tab's place control: its up and down buttons, and all of it. */
    private static final int PLACE_BUTTON = 12, PLACE_WIDTH = PLACE_BUTTON * 2 + 22;
    /** Widths the search field keeps between. */
    private static final int MIN_SEARCH = 90, MAX_SEARCH = 170;
    private static final int RESTORE_WIDTH = 74, INGREDIENT = 14, MAX_INGREDIENTS = 9;
    /** How long a "show everything" waits for the second click that confirms it (ms). */
    private static final long CONFIRM_MS = 3000, STATUS_MS = 3000;
    private static final int ID_CLOSE = 900, ID_SHOW_ALL = 1;

    /** The pages of the menu, in its order. */
    private enum Page {

        TABS(Icons.TABS, "neiextend.page.tabs"),
        ORDER(Icons.ORDER, "neiextend.page.order"),
        RECIPES(Icons.RECIPES, "neiextend.page.recipes"),
        HELP(Icons.HELP, "neiextend.page.help");

        final String[] icon;
        final String name;

        Page(String[] icon, String name) {
            this.icon = icon;
            this.name = name;
        }
    }

    /** Which tabs the list shows. */
    private enum Filter {
        ALL,
        VISIBLE,
        HIDDEN
    }

    /**
     * A line of the tabs' list: a tab NEI has, a hidden one whose mod is gone, or the heading of a mod's tabs (its
     * {@link #members} are the tabs of the mod the list shows, folded or not).
     */
    private static final class TabRow {

        final HandlerCatalog.Entry entry;
        final String id, name, mod, searchText;
        final List<TabRow> members;
        final Smooth light = new Smooth(0), knob = new Smooth(0);

        /** The heading of a mod. */
        TabRow(String mod, List<TabRow> members) {
            this.entry = null;
            this.id = null;
            this.name = mod;
            this.mod = mod;
            this.searchText = "";
            this.members = members;
        }

        boolean isHeading() {
            return members != null;
        }

        /** How much of a heading's mod is shown: 1 all of it, 0 none, between for some. */
        double shownShare() {
            int shown = 0;
            for (TabRow member : members) {
                if (!member.hidden()) {
                    shown++;
                }
            }
            return members.isEmpty() ? 1 : shown == members.size() ? 1 : shown == 0 ? 0 : 0.5;
        }

        TabRow(HandlerCatalog.Entry entry) {
            this.entry = entry;
            this.id = entry.id;
            this.name = entry.name;
            this.mod = entry.mod;
            this.searchText = entry.searchText;
            this.members = null;
        }

        TabRow(HiddenStore.SavedTab tab) {
            this.entry = null;
            this.id = tab.id;
            this.name = tab.name;
            this.mod = tab.mod;
            this.searchText = (tab.name + "\n" + tab.mod + "\n" + tab.id).toLowerCase(Locale.ROOT);
            this.members = null;
        }

        boolean hidden() {
            return !isHeading() && HiddenStore.INSTANCE.isTabHidden(id);
        }

        /** The switch starts where it is, instead of sliding there when the list is shown. */
        TabRow settled() {
            knob.set(isHeading() ? shownShare() : hidden() ? 0 : 1);
            return this;
        }
    }

    /**
     * A line of the recipes' list: one hidden recipe, or the heading of a tab's hidden recipes (its {@link #members}
     * are the ones the list shows, folded or not).
     */
    private static final class RecipeRow {

        final HiddenStore.HiddenRecipe recipe;
        final String handlerId, tab;
        final List<RecipeRow> members;
        final ItemStack result;
        /** The ingredients, each kind once with how many of it the recipe takes. */
        final List<ItemStack> ingredients;
        final String name, searchText;
        final Smooth light = new Smooth(0), buttonLight = new Smooth(0);
        /** Shown again from the list: it stays in it, dimmed, to be hidden again, until the list is filled again. */
        boolean restored;
        /** A heading's "show all" waits for a second click until then. */
        long confirmUntil;

        RecipeRow(HiddenStore.HiddenRecipe recipe) {
            this.recipe = recipe;
            this.handlerId = recipe.handlerId;
            this.tab = recipe.tab;
            this.members = null;
            this.result = recipe.result();
            this.ingredients = merge(recipe.ingredients());
            String shown;
            try {
                shown = result == null ? null : result.getDisplayName();
            } catch (Throwable t) {
                shown = null;
            }
            this.name = shown != null ? shown : I18n.format("neiextend.recipes.unknown");
            StringBuilder text = new StringBuilder(name).append('\n')
                .append(recipe.tab);
            for (ItemStack ingredient : ingredients) {
                try {
                    text.append('\n')
                        .append(ingredient.getDisplayName());
                } catch (Throwable ignored) {
                    // An item that can't tell its name: not searchable by it.
                }
            }
            this.searchText = text.toString()
                .toLowerCase(Locale.ROOT);
        }

        /** The heading of a tab. */
        RecipeRow(String handlerId, String tab, List<RecipeRow> members) {
            this.recipe = null;
            this.handlerId = handlerId;
            this.tab = tab;
            this.members = members;
            this.result = null;
            this.ingredients = Collections.emptyList();
            this.name = tab;
            this.searchText = "";
        }

        boolean isHeading() {
            return members != null;
        }

        /** The hidden recipes of a heading's tab still hidden. */
        List<HiddenStore.HiddenRecipe> stillHidden() {
            List<HiddenStore.HiddenRecipe> hidden = new ArrayList<>();
            for (RecipeRow member : members) {
                if (!member.restored) {
                    hidden.add(member.recipe);
                }
            }
            return hidden;
        }

        /** The same items put together: two rods in two slots are shown as one rod, twice. */
        private static List<ItemStack> merge(List<ItemStack> stacks) {
            List<ItemStack> merged = new ArrayList<>();
            for (ItemStack stack : stacks) {
                if (stack == null || stack.getItem() == null) {
                    continue;
                }
                ItemStack same = null;
                for (ItemStack each : merged) {
                    if (StackInfo.equalItemAndNBT(each, stack, true)) {
                        same = each;
                        break;
                    }
                }
                if (same != null) {
                    same.stackSize += Math.max(1, stack.stackSize);
                } else {
                    ItemStack copy = stack.copy();
                    copy.stackSize = Math.max(1, copy.stackSize);
                    merged.add(copy);
                }
            }
            return merged;
        }
    }

    /** The page last open: the window opens on it again. */
    private static Page lastPage = Page.TABS;

    private final GuiScreen parent;
    private final int revisionAtOpen = HiddenStore.INSTANCE.revision();
    private final List<HandlerCatalog.Entry> catalog = HandlerCatalog.all();
    private final Map<String, HandlerCatalog.Entry> catalogById = new HashMap<>();
    private Page page = lastPage;
    private Filter filter = Filter.ALL;

    private final List<TabRow> tabRows = new ArrayList<>();
    /** How many tabs the list of tabs shows, folded mods included. */
    private int tabCount;
    /** The mods whose tabs are folded under their heading, for as long as the game runs. */
    private static final Set<String> foldedMods = new HashSet<>();
    /** The tabs whose hidden recipes are folded under their heading, for as long as the game runs. */
    private static final Set<String> foldedRecipeTabs = new HashSet<>();
    /** How many hidden recipes the list of recipes shows, folded tabs included. */
    private int recipeCount;
    /** The tab whose place among the ones put first is being typed (null for none), and what is typed. */
    private String placeEditId;
    private String placeInput = "";
    /** The tabs put first, in their order. */
    private final List<TabRow> orderRows = new ArrayList<>();
    /** The row of the order being dragged (-1 for none), where it was grabbed, and whether it moved yet. */
    private int dragIndex = -1, dragGrab;
    /** How tall the help is, as last drawn: it scrolls when taller than the window. */
    private int helpHeight;
    private boolean dragMoved;
    private final List<RecipeRow> recipeRows = new ArrayList<>();
    private int scroll;
    private final Smooth shownScroll = new Smooth(0);
    private final ScrollbarDrag scrollbarDrag = new ScrollbarDrag();
    private final Smooth[] pageLight = new Smooth[Page.values().length];
    private final Smooth[] filterLight = new Smooth[Filter.values().length];

    private int left, top;
    private int contentLeft, contentTop, contentRight, contentBottom;
    private int listTop, listBottom;
    private FlatTextField search;
    private FlatButton showAll;
    private long confirmUntil;
    /** How wide the search field is, as the row of controls was last laid out. */
    private int searchWidth = MAX_SEARCH;
    /** The button on the right of the controls shows only its icon: its text is its tooltip. */
    private boolean showAllCompact;
    private String showAllLabel;
    /** A short message in the note at the bottom (a tab that could not be opened), until when it shows. */
    private String status;
    private long statusUntil;
    /** All recipes of a tab being looked up (a right click), shown over the window until done; null for none. */
    private AllRecipes lookup;
    private boolean lookupCancelled;
    /** Where the lookup's cancel button is, as last drawn. */
    private int cancelX0, cancelY0, cancelX1, cancelY1;
    /** What the tooltip under the mouse says, set while drawing and drawn over everything. */
    private String hoverText;
    private ItemStack hoverStack;

    public GuiNEIExtend(GuiScreen parent) {
        this.parent = parent;
        for (HandlerCatalog.Entry entry : catalog) {
            catalogById.put(entry.id, entry);
        }
        for (int i = 0; i < pageLight.length; i++) {
            pageLight[i] = new Smooth(0);
        }
        for (int i = 0; i < filterLight.length; i++) {
            filterLight[i] = new Smooth(0);
        }
    }

    // Layout.

    @Override
    public void initGui() {
        Keyboard.enableRepeatEvents(true);
        left = (width - WIDTH) / 2;
        top = Math.max(4, (height - HEIGHT) / 2);
        contentLeft = left + MENU + 12;
        contentRight = left + WIDTH - 12;
        contentTop = top + WindowHeader.HEIGHT + 10;
        contentBottom = top + HEIGHT - 10;
        listTop = contentTop + CONTROLS + 8;
        listBottom = contentBottom - NOTE;

        makeSearch(searchWidth, search == null || search.isFocused());

        buttonList.clear();
        buttonList.add(WindowHeader.closeButton(ID_CLOSE, left + WIDTH, top));
        showAll = new FlatButton(ID_SHOW_ALL, contentRight - 128, contentTop, 128, CONTROLS, "");
        showAll.icon = Icons.SMALL_EYE;
        buttonList.add(showAll);
        rebuild();
    }

    /** The search field at the width given, keeping what is typed in it. */
    private void makeSearch(int width, boolean focused) {
        String text = search == null ? "" : search.getText();
        int cursor = search == null ? text.length() : search.getCursorPosition();
        search = new FlatTextField(fontRendererObj, contentLeft, contentTop, width, CONTROLS);
        search.setMaxStringLength(64);
        search.setText(text);
        search.setCursorPosition(Math.min(cursor, text.length()));
        search.setFocused(focused && usesSearch());
        search.setHint(I18n.format("neiextend.search"))
            .setOnCleared(this::rebuild);
    }

    /**
     * Lays the row of controls over the list out for what it holds now (its texts change with the counts, the page and
     * the language): the button on the right as wide as its text, the filters after the search field, which gets
     * narrower to leave them room. When even so they don't fit, the button shows only its icon.
     */
    private void layoutControls() {
        String label = showAll.displayString;
        int full = 6 + Icons.width(showAll.icon) + 5 + fontRendererObj.getStringWidth(label) + 8;
        int filters = 0;
        if (page == Page.TABS) {
            for (Filter each : Filter.values()) {
                filters += fontRendererObj.getStringWidth(filterLabel(each)) + 16;
            }
            filters += 8;
        }
        int room = contentRight - contentLeft - filters - 8;
        int buttonWidth = full;
        showAllCompact = room - full < MIN_SEARCH;
        if (showAllCompact) {
            buttonWidth = CONTROLS + 4;
            showAll.displayString = "";
        }
        showAll.setWidth(buttonWidth);
        showAll.iconSlot = showAllCompact ? buttonWidth - 12 : 0;
        showAll.xPosition = contentRight - buttonWidth;
        int width = Math.max(MIN_SEARCH, Math.min(MAX_SEARCH, room - buttonWidth));
        if (width != searchWidth) {
            searchWidth = width;
            makeSearch(width, search.isFocused());
        }
    }

    /** Fills the list of the page again from what is searched and filtered. */
    private void rebuild() {
        String query = search == null ? ""
            : search.getText()
                .trim()
                .toLowerCase(Locale.ROOT);
        tabRows.clear();
        orderRows.clear();
        recipeRows.clear();
        if (page == Page.TABS) {
            Set<String> known = new HashSet<>();
            for (HandlerCatalog.Entry entry : catalog) {
                known.add(entry.id);
                addTab(new TabRow(entry).settled(), query);
            }
            for (HiddenStore.SavedTab tab : HiddenStore.INSTANCE.tabs()) {
                if (!known.contains(tab.id)) {
                    addTab(new TabRow(tab).settled(), query);
                }
            }
            groupByMod(!query.isEmpty());
        } else if (page == Page.ORDER) {
            for (HiddenStore.SavedTab tab : HiddenStore.INSTANCE.pinned()) {
                HandlerCatalog.Entry entry = catalogById.get(tab.id);
                orderRows.add(entry != null ? new TabRow(entry) : new TabRow(tab));
            }
        } else if (page == Page.RECIPES) {
            List<HiddenStore.HiddenRecipe> recipes = new ArrayList<>(HiddenStore.INSTANCE.recipes());
            // The last hidden first, and the tabs too by the last hidden of each.
            Collections.reverse(recipes);
            Map<String, List<RecipeRow>> byTab = new LinkedHashMap<>();
            recipeCount = 0;
            for (HiddenStore.HiddenRecipe recipe : recipes) {
                RecipeRow row = new RecipeRow(recipe);
                if (query.isEmpty() || row.searchText.contains(query)) {
                    byTab.computeIfAbsent(recipe.handlerId, k -> new ArrayList<>())
                        .add(row);
                    recipeCount++;
                }
            }
            for (Map.Entry<String, List<RecipeRow>> tab : byTab.entrySet()) {
                List<RecipeRow> members = tab.getValue();
                HandlerCatalog.Entry entry = catalogById.get(tab.getKey());
                String name = entry != null ? entry.name : members.get(0).tab;
                recipeRows.add(new RecipeRow(tab.getKey(), name, members));
                if (!query.isEmpty() || !foldedRecipeTabs.contains(tab.getKey())) {
                    recipeRows.addAll(members);
                }
            }
        }
        clampScroll();
    }

    /**
     * Puts the tabs found under a heading for each mod, in the order they came (by mod, then name); the tabs of a
     * folded mod are left out, but for a search, which shows all it finds.
     */
    private void groupByMod(boolean searching) {
        Map<String, List<TabRow>> byMod = new LinkedHashMap<>();
        for (TabRow row : tabRows) {
            byMod.computeIfAbsent(row.mod, k -> new ArrayList<>())
                .add(row);
        }
        tabCount = tabRows.size();
        tabRows.clear();
        for (Map.Entry<String, List<TabRow>> mod : byMod.entrySet()) {
            tabRows.add(new TabRow(mod.getKey(), mod.getValue()).settled());
            if (searching || !foldedMods.contains(mod.getKey())) {
                tabRows.addAll(mod.getValue());
            }
        }
    }

    private void addTab(TabRow row, String query) {
        boolean hidden = row.hidden();
        if (filter == Filter.VISIBLE && hidden || filter == Filter.HIDDEN && !hidden) {
            return;
        }
        if (query.isEmpty() || row.searchText.contains(query)) {
            tabRows.add(row);
        }
    }

    private int rowHeight() {
        return page == Page.RECIPES ? RECIPE_ROW : TAB_ROW;
    }

    private int rowCount() {
        switch (page) {
            case TABS:
                return tabRows.size();
            case ORDER:
                return orderRows.size();
            case RECIPES:
                return recipeRows.size();
            default:
                return 0;
        }
    }

    /** The pages with a search over their list. */
    private boolean usesSearch() {
        return page == Page.TABS || page == Page.RECIPES;
    }

    private int contentHeight() {
        return rowCount() * rowHeight() + 4;
    }

    private int maxScroll() {
        if (page == Page.HELP) {
            return Math.max(0, helpHeight - (contentBottom - contentTop));
        }
        return Math.max(0, contentHeight() - (listBottom - listTop));
    }

    private void clampScroll() {
        scroll = Math.max(0, Math.min(scroll, maxScroll()));
    }

    private int rowY(int index, int shown) {
        return listTop + 2 + index * rowHeight() - shown;
    }

    /** The row under the mouse, or -1. */
    private int rowAt(int mouseX, int mouseY) {
        if (!Theme.inside(mouseX, mouseY, contentLeft + 1, listTop + 1, contentRight - 6, listBottom - 1)) {
            return -1;
        }
        int index = (mouseY - listTop - 2 + scroll) / rowHeight();
        return mouseY - listTop - 2 + scroll >= 0 && index < rowCount() ? index : -1;
    }

    private int switchX() {
        return contentRight - 14 - SWITCH;
    }

    private int restoreX() {
        return contentRight - 14 - RESTORE_WIDTH;
    }

    /** The buttons of a line of the order: up, down, and out of it. */
    private int upX() {
        return downX() - 2 - ORDER_BUTTON;
    }

    private int downX() {
        return removeX() - 2 - ORDER_BUTTON;
    }

    private int removeX() {
        return contentRight - 14 - ORDER_BUTTON;
    }

    /** Width of a tab's R and U tags. */
    private static int tagsWidth(HandlerCatalog.Entry entry) {
        return entry == null ? 0 : (entry.usage ? 14 : 0) + (entry.crafting ? entry.usage ? 16 : 14 : 0);
    }

    /** Where a tab's place control (up, place, down) starts, left of its tags. */
    private int placeX(TabRow row) {
        int tags = tagsWidth(row.entry);
        return switchX() - 8 - tags - (tags > 0 ? 6 : 0) - PLACE_WIDTH;
    }

    /** What of a tab's place control the mouse is on: -1 up, 0 the place, 1 down, or null for none. */
    private Integer placePart(TabRow row, int y, int mouseX, int mouseY) {
        int x = placeX(row);
        if (row.isHeading() || !Theme.inside(mouseX, mouseY, x, y + 5, x + PLACE_WIDTH, y + TAB_ROW - 5)) {
            return null;
        }
        return mouseX < x + PLACE_BUTTON ? -1 : mouseX >= x + PLACE_WIDTH - PLACE_BUTTON ? 1 : 0;
    }

    /** Where a mod heading's star is: left of its switch. */
    private int headingStarX() {
        return switchX() - 10 - 13;
    }

    /** The filter buttons: where each starts, and where the last ends. */
    private int[] filterEdges() {
        int[] edges = new int[Filter.values().length + 1];
        int x = contentLeft + searchWidth + 8;
        for (Filter each : Filter.values()) {
            edges[each.ordinal()] = x;
            x += fontRendererObj.getStringWidth(filterLabel(each)) + 16;
        }
        edges[edges.length - 1] = x;
        return edges;
    }

    private String filterLabel(Filter each) {
        switch (each) {
            case VISIBLE:
                return I18n.format("neiextend.filter.visible", catalog.size() - hiddenKnownTabs());
            case HIDDEN:
                return I18n.format("neiextend.filter.hidden", HiddenStore.INSTANCE.tabCount());
            default:
                return I18n.format("neiextend.filter.all", catalog.size());
        }
    }

    private int hiddenKnownTabs() {
        int count = 0;
        for (HandlerCatalog.Entry entry : catalog) {
            if (HiddenStore.INSTANCE.isTabHidden(entry.id)) {
                count++;
            }
        }
        return count;
    }

    private int pageY(Page each) {
        return top + WindowHeader.HEIGHT + 8 + each.ordinal() * PAGE_HEIGHT;
    }

    private Page pageAt(int mouseX, int mouseY) {
        for (Page each : Page.values()) {
            int y = pageY(each);
            if (Theme.inside(mouseX, mouseY, left + 1, y, left + MENU, y + PAGE_HEIGHT)) {
                return each;
            }
        }
        return null;
    }

    private void switchTo(Page next) {
        if (next == page) {
            return;
        }
        page = lastPage = next;
        scroll = 0;
        shownScroll.set(0);
        confirmUntil = 0;
        search.setText("");
        search.setFocused(next == Page.TABS || next == Page.RECIPES);
        rebuild();
    }

    // Input.

    @Override
    protected void actionPerformed(GuiButton button) {
        if (button.id == ID_CLOSE) {
            close();
        } else if (button.id == ID_SHOW_ALL) {
            long now = System.currentTimeMillis();
            if (now > confirmUntil) {
                confirmUntil = now + CONFIRM_MS;
                return;
            }
            confirmUntil = 0;
            if (page == Page.TABS) {
                HiddenStore.INSTANCE.showAllTabs();
            } else if (page == Page.ORDER) {
                HiddenStore.INSTANCE.unpinAll();
            } else if (searching()) {
                // Only what was found.
                HiddenStore.INSTANCE.showRecipes(foundRecipes());
            } else {
                HiddenStore.INSTANCE.showAllRecipes();
            }
            rebuild();
        }
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int button) {
        // A click anywhere ends the typing of a place, keeping what was typed.
        if (placeEditId != null) {
            applyPlace();
        }
        if (lookup != null) {
            // The window waits for the lookup: only its cancel button works.
            if (button == 0 && Theme.inside(mouseX, mouseY, cancelX0, cancelY0, cancelX1, cancelY1)) {
                cancelLookup();
            }
            return;
        }
        Page clickedPage = button == 0 ? pageAt(mouseX, mouseY) : null;
        if (clickedPage != null) {
            click();
            switchTo(clickedPage);
            return;
        }
        if (usesSearch()) {
            search.mouseClicked(mouseX, mouseY, button);
        }
        super.mouseClicked(mouseX, mouseY, button);
        if (button == 1 && page != Page.HELP) {
            int index = rowAt(mouseX, mouseY);
            if (index >= 0) {
                openAllRecipes(index);
            }
            return;
        }
        if (button != 0 || page == Page.HELP) {
            return;
        }
        int dragged = scrollbarDrag.press(
            mouseX,
            mouseY,
            contentRight - 4,
            listTop + 2,
            listBottom - 2,
            listBottom - listTop,
            contentHeight(),
            scroll,
            maxScroll());
        if (dragged >= 0) {
            scroll = dragged;
            return;
        }
        if (page == Page.TABS) {
            int[] edges = filterEdges();
            for (Filter each : Filter.values()) {
                if (Theme.inside(
                    mouseX,
                    mouseY,
                    edges[each.ordinal()],
                    contentTop,
                    edges[each.ordinal() + 1] - 2,
                    contentTop + CONTROLS)) {
                    click();
                    filter = each;
                    scroll = 0;
                    rebuild();
                    return;
                }
            }
        }
        int index = rowAt(mouseX, mouseY);
        if (index < 0) {
            return;
        }
        int y = rowY(index, scroll);
        if (page == Page.TABS) {
            TabRow row = tabRows.get(index);
            if (row.isHeading()) {
                clickHeading(row, mouseX, mouseY, y);
                return;
            }
            Integer part = placePart(row, y, mouseX, mouseY);
            if (part != null) {
                if (part == 0) {
                    int place = HiddenStore.INSTANCE.pinPosition(row.id);
                    placeEditId = row.id;
                    placeInput = place >= 0 ? String.valueOf(place + 1) : "";
                    click();
                } else {
                    movePlace(row, part, isShiftKeyDown());
                }
                return;
            }
            if (row.hidden()) {
                HiddenStore.INSTANCE.showTab(row.id);
            } else if (row.entry != null) {
                HiddenStore.INSTANCE.hideTab(row.entry.handler);
            }
            click();
        } else if (page == Page.ORDER) {
            clickOrder(index, y, mouseX, mouseY);
        } else {
            clickRecipe(recipeRows.get(index), mouseX, mouseY, y);
        }
    }

    /**
     * A click on a line of the recipes. A recipe's button shows it again, or hides it again: it stays in the list
     * until the list is filled again. A heading's button shows all its tab's recipes (a second click confirms it);
     * elsewhere the heading folds or unfolds the tab, all tabs with Shift.
     */
    private void clickRecipe(RecipeRow row, int mouseX, int mouseY, int y) {
        int by = y + (RECIPE_ROW - 16) / 2;
        boolean onButton = Theme.inside(mouseX, mouseY, restoreX(), by, restoreX() + RESTORE_WIDTH, by + 16);
        if (!row.isHeading()) {
            if (onButton) {
                if (row.restored) {
                    HiddenStore.INSTANCE.hideAgain(row.recipe);
                } else {
                    HiddenStore.INSTANCE.showRecipe(row.recipe);
                }
                row.restored = !row.restored;
                click();
            }
            return;
        }
        click();
        if (onButton) {
            long now = System.currentTimeMillis();
            if (now > row.confirmUntil) {
                row.confirmUntil = now + CONFIRM_MS;
                return;
            }
            row.confirmUntil = 0;
            HiddenStore.INSTANCE.showRecipes(row.stillHidden());
            rebuild();
            return;
        }
        if (searching()) {
            // A search shows all it finds, folded or not.
            return;
        }
        boolean fold = !foldedRecipeTabs.contains(row.handlerId);
        if (isShiftKeyDown()) {
            for (RecipeRow each : recipeRows) {
                if (each.isHeading()) {
                    if (fold) {
                        foldedRecipeTabs.add(each.handlerId);
                    } else {
                        foldedRecipeTabs.remove(each.handlerId);
                    }
                }
            }
        } else if (fold) {
            foldedRecipeTabs.add(row.handlerId);
        } else {
            foldedRecipeTabs.remove(row.handlerId);
        }
        rebuild();
    }

    private boolean searching() {
        return !search.getText()
            .trim()
            .isEmpty();
    }

    /** The hidden recipes the search found, but the ones shown again already. */
    private List<HiddenStore.HiddenRecipe> foundRecipes() {
        List<HiddenStore.HiddenRecipe> found = new ArrayList<>();
        for (RecipeRow row : recipeRows) {
            if (!row.isHeading() && !row.restored) {
                found.add(row.recipe);
            }
        }
        return found;
    }

    /**
     * A click on a mod's heading: its switch hides or shows all its tabs, its star puts them all first (or takes
     * them out); elsewhere it folds or unfolds the mod, all mods with Shift.
     */
    private void clickHeading(TabRow heading, int mouseX, int mouseY, int y) {
        List<IRecipeHandler> handlers = new ArrayList<>();
        List<String> ids = new ArrayList<>();
        for (TabRow member : heading.members) {
            ids.add(member.id);
            if (member.entry != null) {
                handlers.add(member.entry.handler);
            }
        }
        int sy = y + (TAB_ROW - 14) / 2;
        if (Theme.inside(mouseX, mouseY, switchX(), sy, switchX() + SWITCH, sy + 14)) {
            if (heading.shownShare() > 0) {
                HiddenStore.INSTANCE.hideTabs(handlers);
            } else {
                HiddenStore.INSTANCE.showTabs(ids);
            }
            click();
            return;
        }
        int starX = headingStarX();
        if (Theme.inside(mouseX, mouseY, starX - 2, y + 4, starX + 11, y + TAB_ROW - 4)) {
            if (pinnedCount(heading) > 0) {
                HiddenStore.INSTANCE.unpin(ids);
            } else {
                HiddenStore.INSTANCE.pinAll(handlers);
            }
            click();
            return;
        }
        click();
        if (isShiftKeyDown()) {
            boolean fold = !foldedMods.contains(heading.mod);
            for (TabRow row : tabRows) {
                if (row.isHeading()) {
                    if (fold) {
                        foldedMods.add(row.mod);
                    } else {
                        foldedMods.remove(row.mod);
                    }
                }
            }
        } else if (!foldedMods.remove(heading.mod)) {
            foldedMods.add(heading.mod);
        }
        rebuild();
    }

    private static int pinnedCount(TabRow heading) {
        int count = 0;
        for (TabRow member : heading.members) {
            if (HiddenStore.INSTANCE.pinPosition(member.id) >= 0) {
                count++;
            }
        }
        return count;
    }

    /**
     * Moves a tab up (-1) or down (1) among the ones put first; up puts a tab that isn't first last of them, down takes
     * the last one out of them. With Shift, up goes to the top and down takes it out at once.
     */
    private void movePlace(TabRow row, int direction, boolean shift) {
        int place = HiddenStore.INSTANCE.pinPosition(row.id);
        int count = HiddenStore.INSTANCE.pinned()
            .size();
        if (direction < 0) {
            if (place < 0) {
                if (row.entry == null) {
                    return;
                }
                HiddenStore.INSTANCE.pin(row.entry.handler, shift ? 0 : -1);
            } else if (place > 0) {
                HiddenStore.INSTANCE.movePin(row.id, shift ? 0 : place - 1);
            } else {
                return;
            }
        } else {
            if (place < 0) {
                return;
            }
            if (shift || place == count - 1) {
                HiddenStore.INSTANCE.unpin(row.id);
            } else {
                HiddenStore.INSTANCE.movePin(row.id, place + 1);
            }
        }
        click();
    }

    /** Puts the tab whose place was typed at that place; nothing typed (or 0) takes it out of the ones put first. */
    private void applyPlace() {
        String id = placeEditId;
        placeEditId = null;
        if (id == null) {
            return;
        }
        int wanted = placeInput.isEmpty() ? 0 : Integer.parseInt(placeInput);
        if (wanted <= 0) {
            HiddenStore.INSTANCE.unpin(id);
        } else if (HiddenStore.INSTANCE.pinPosition(id) >= 0) {
            HiddenStore.INSTANCE.movePin(id, wanted - 1);
        } else {
            HandlerCatalog.Entry entry = catalogById.get(id);
            if (entry != null) {
                HiddenStore.INSTANCE.pin(entry.handler, wanted - 1);
            }
        }
        click();
    }

    /** A click on a line of the order: its buttons move it or take it out; elsewhere it starts dragging it. */
    private void clickOrder(int index, int y, int mouseX, int mouseY) {
        TabRow row = orderRows.get(index);
        int by = y + (TAB_ROW - ORDER_BUTTON) / 2;
        boolean shift = isShiftKeyDown();
        if (Theme.inside(mouseX, mouseY, upX(), by, upX() + ORDER_BUTTON, by + ORDER_BUTTON)) {
            if (index > 0) {
                HiddenStore.INSTANCE.movePin(row.id, shift ? 0 : index - 1);
                click();
                rebuild();
            }
        } else if (Theme.inside(mouseX, mouseY, downX(), by, downX() + ORDER_BUTTON, by + ORDER_BUTTON)) {
            if (index < orderRows.size() - 1) {
                HiddenStore.INSTANCE.movePin(row.id, shift ? orderRows.size() - 1 : index + 1);
                click();
                rebuild();
            }
        } else if (Theme.inside(mouseX, mouseY, removeX(), by, removeX() + ORDER_BUTTON, by + ORDER_BUTTON)) {
            HiddenStore.INSTANCE.unpin(row.id);
            click();
            rebuild();
        } else {
            dragIndex = index;
            dragGrab = mouseY - y;
            dragMoved = false;
        }
    }

    /** Where the dragged line would go if dropped with the mouse here. */
    private int dropIndex(int mouseY) {
        int top = mouseY - dragGrab + TAB_ROW / 2 - listTop - 2 + scroll;
        return Math.max(0, Math.min(orderRows.size() - 1, Math.floorDiv(top, TAB_ROW)));
    }

    /** Opens the tab of the row with all its recipes (a right click on it). */
    private void openAllRecipes(int index) {
        if (page == Page.TABS && tabRows.get(index)
            .isHeading()) {
            return;
        }
        String id = page == Page.TABS ? tabRows.get(index).id
            : page == Page.ORDER ? orderRows.get(index).id : recipeRows.get(index).handlerId;
        HandlerCatalog.Entry entry = null;
        for (HandlerCatalog.Entry each : catalog) {
            if (each.id.equals(id)) {
                entry = each;
                break;
            }
        }
        click();
        if (entry == null) {
            showStatus(I18n.format("neiextend.open.missing"));
            return;
        }
        // Looked up on other threads, the window showing how far it got: some tabs are asked about every item.
        lookup = AllRecipes.start(entry.handler);
        lookupCancelled = false;
    }

    private void showStatus(String text) {
        status = text;
        statusUntil = System.currentTimeMillis() + STATUS_MS;
    }

    private void cancelLookup() {
        if (lookup != null) {
            lookup.cancel();
            lookupCancelled = true;
            click();
        }
    }

    @Override
    protected void mouseClickMove(int mouseX, int mouseY, int button, long held) {
        if (dragIndex >= 0) {
            dragMoved = true;
            return;
        }
        int dragged = scrollbarDrag.drag(mouseY);
        if (dragged >= 0) {
            scroll = dragged;
        }
    }

    @Override
    protected void mouseMovedOrUp(int mouseX, int mouseY, int state) {
        super.mouseMovedOrUp(mouseX, mouseY, state);
        if (state >= 0) {
            scrollbarDrag.release();
            if (dragIndex >= 0 && dragMoved && dragIndex < orderRows.size()) {
                int to = dropIndex(mouseY);
                if (to != dragIndex) {
                    HiddenStore.INSTANCE.movePin(orderRows.get(dragIndex).id, to);
                    click();
                    rebuild();
                }
            }
            dragIndex = -1;
        }
    }

    @Override
    public void handleMouseInput() {
        super.handleMouseInput();
        int wheel = Mouse.getEventDWheel();
        if (wheel != 0 && page == Page.TABS && lookup == null) {
            // Over a tab's place control the wheel moves the tab instead of the list.
            int mx = mouseX(), my = mouseY();
            int index = rowAt(mx, my);
            if (index >= 0) {
                TabRow row = tabRows.get(index);
                if (placePart(row, rowY(index, scroll), mx, my) != null) {
                    movePlace(row, wheel > 0 ? -1 : 1, false);
                    return;
                }
            }
        }
        if (wheel != 0) {
            scroll -= Integer.signum(wheel) * 2 * rowHeight();
            clampScroll();
        }
    }

    @Override
    protected void keyTyped(char typedChar, int keyCode) {
        if (lookup != null) {
            if (keyCode == Keyboard.KEY_ESCAPE) {
                cancelLookup();
            }
            return;
        }
        if (placeEditId != null) {
            if (keyCode == Keyboard.KEY_ESCAPE) {
                placeEditId = null;
            } else if (keyCode == Keyboard.KEY_RETURN || keyCode == Keyboard.KEY_NUMPADENTER) {
                applyPlace();
            } else if (keyCode == Keyboard.KEY_BACK) {
                placeInput = placeInput.isEmpty() ? "" : placeInput.substring(0, placeInput.length() - 1);
            } else if (typedChar >= '0' && typedChar <= '9' && placeInput.length() < 3) {
                placeInput += typedChar;
            }
            return;
        }
        if (keyCode == Keyboard.KEY_ESCAPE) {
            close();
            return;
        }
        if (usesSearch() && search.isFocused()) {
            String before = search.getText();
            if (search.textboxKeyTyped(typedChar, keyCode)) {
                if (!before.equals(search.getText())) {
                    scroll = 0;
                    rebuild();
                }
                return;
            }
        }
        // Typed letters of some layouts come with no key code: only a set key closes the window.
        int openKey = KeyHandler.OPEN.getKeyCode();
        if (keyCode == mc.gameSettings.keyBindInventory.getKeyCode()
            || openKey != Keyboard.KEY_NONE && keyCode == openKey) {
            close();
        }
    }

    @Override
    public void updateScreen() {
        search.updateCursorCounter();
        if (lookup != null && lookup.finished()) {
            AllRecipes done = lookup;
            lookup = null;
            if (lookupCancelled) {
                showStatus(I18n.format("neiextend.scan.cancelled"));
            } else if (!done.show()) {
                showStatus(I18n.format("neiextend.open.empty"));
            }
        }
    }

    private void click() {
        mc.getSoundHandler()
            .playSound(PositionedSoundRecord.func_147674_a(new ResourceLocation("gui.button.press"), 1.0F));
    }

    /** Back to the recipe screen it was opened from, showing the changes, or to the game. */
    private void close() {
        mc.displayGuiScreen(parent);
        if (parent instanceof GuiRecipe && HiddenStore.INSTANCE.revision() != revisionAtOpen) {
            RecipeScreenHooks.INSTANCE.refresh((GuiRecipe<?>) parent);
        }
    }

    @Override
    public void onGuiClosed() {
        Keyboard.enableRepeatEvents(false);
    }

    // Drawing.

    @Override
    public void drawScaled(int mouseX, int mouseY, float partialTicks) {
        hoverText = null;
        hoverStack = null;
        Theme.fill(0, 0, width, height, Theme.SCREEN_DIM);
        Theme.panel(left, top, left + WIDTH, top + HEIGHT);
        Theme.fill(left + MENU + 1, top + WindowHeader.HEIGHT, left + WIDTH - 1, top + HEIGHT - 1, Theme.CONTENT);
        WindowHeader.draw(
            fontRendererObj,
            left,
            top,
            left + WIDTH,
            left + WIDTH - WindowHeader.CLOSE_ROOM,
            Icons.LOGO,
            "NEIExtend",
            I18n.format("neiextend.gui.subtitle"),
            Theme.TEXT_MUTED,
            null);
        drawMenu(mouseX, mouseY);

        boolean confirming = System.currentTimeMillis() < confirmUntil;
        showAll.visible = page != Page.HELP;
        showAll.enabled = page == Page.TABS ? HiddenStore.INSTANCE.tabCount() > 0
            : page == Page.ORDER ? !HiddenStore.INSTANCE.pinned()
                .isEmpty() : HiddenStore.INSTANCE.hasHiddenRecipes();
        showAll.icon = page == Page.ORDER ? Icons.SMALL_CLEAR : Icons.SMALL_EYE;
        showAll.danger = confirming;
        boolean onlyFound = page == Page.RECIPES && searching();
        if (onlyFound) {
            showAll.enabled = !foundRecipes().isEmpty();
        }
        showAll.displayString = confirming ? I18n.format("neiextend.show_all.confirm")
            : onlyFound ? I18n.format("neiextend.show_all.found", foundRecipes().size())
                : I18n.format(
                    page == Page.TABS ? "neiextend.show_all.tabs"
                        : page == Page.ORDER ? "neiextend.show_all.order" : "neiextend.show_all.recipes");
        showAllLabel = showAll.displayString;
        if (page != Page.HELP) {
            layoutControls();
        }

        if (page == Page.HELP) {
            drawHelp();
        } else {
            if (usesSearch()) {
                search.drawTextBox();
            } else {
                // The order has no search: what it does, in its place.
                Icons.draw(Icons.STAR, contentLeft + 1, contentTop + 4, Theme.WARN);
                fontRendererObj.drawString(
                    Theme.ellipsize(
                        fontRendererObj,
                        I18n.format("neiextend.order.hint"),
                        showAll.xPosition - 18 - contentLeft),
                    contentLeft + 15,
                    contentTop + 5,
                    Theme.TEXT_MUTED);
            }
            if (page == Page.TABS) {
                drawFilters(mouseX, mouseY);
            }
            drawList(mouseX, mouseY);
            drawNote();
        }
        super.drawScaled(mouseX, mouseY, partialTicks);
        if (lookup != null) {
            drawLookup(lookup, mouseX, mouseY);
            return;
        }
        if (showAllCompact && showAll.visible
            && hoverText == null
            && hoverStack == null
            && showAll.isMouseOver(mouseX, mouseY)) {
            hoverText = showAllLabel;
        }
        if (hoverStack != null) {
            List<String> lines = new ArrayList<>();
            List<Integer> colors = new ArrayList<>();
            lines.add(hoverStack.getDisplayName());
            colors.add(Theme.TEXT);
            Draw.tooltip(fontRendererObj, lines, colors, mouseX, mouseY, width, height);
        } else if (hoverText != null) {
            tooltip(hoverText, mouseX, mouseY);
        }
    }

    private void drawMenu(int mouseX, int mouseY) {
        int x0 = left + 1, x1 = left + MENU, y0 = top + WindowHeader.HEIGHT, y1 = top + HEIGHT - 1;
        Theme.fill(x0, y0, x1, y1, Theme.WINDOW);
        Theme.fill(x1, y0, x1 + 1, y1, Theme.BORDER);
        Page hovered = pageAt(mouseX, mouseY);
        for (Page each : Page.values()) {
            int y = pageY(each);
            boolean chosen = each == page;
            double lit = pageLight[each.ordinal()].update(each == hovered ? 1 : 0, 22);
            if (chosen) {
                Theme.fill(x0, y, x1, y + PAGE_HEIGHT, Theme.SELECTED);
                Theme.fill(x0, y, x0 + 2, y + PAGE_HEIGHT, Theme.ACCENT_FILL);
            } else if (lit > 0.01) {
                Theme.fill(x0, y, x1, y + PAGE_HEIGHT, Theme.blend(0x00FFFFFF, 0x14FFFFFF, lit));
            }
            int iconColor = chosen ? Theme.ACCENT : Theme.blend(0xFF98A6BA, Theme.TEXT, lit);
            int iconWidth = Icons.width(each.icon);
            Icons.draw(each.icon, x0 + 8 + (11 - iconWidth) / 2, y + (PAGE_HEIGHT - each.icon.length) / 2, iconColor);
            int textColor = chosen ? Theme.TEXT : Theme.blend(0xFFB8C4D6, Theme.TEXT, lit);
            int room = x1 - x0 - 24 - 4;
            int hidden = each == Page.TABS ? HiddenStore.INSTANCE.tabCount()
                : each == Page.ORDER ? HiddenStore.INSTANCE.pinned()
                    .size()
                    : each == Page.RECIPES ? HiddenStore.INSTANCE.recipes()
                        .size() : 0;
            if (hidden > 0) {
                String count = String.valueOf(hidden);
                int countWidth = fontRendererObj.getStringWidth(count) + 6;
                int cx1 = x1 - 5, cx0 = cx1 - countWidth;
                int cy0 = y + (PAGE_HEIGHT - 12) / 2;
                Theme.roundRect(cx0, cy0, cx1, cy0 + 12, chosen ? Theme.ACCENT_DIM : 0xFF1F2E47);
                fontRendererObj.drawString(count, cx0 + 3, cy0 + 2, chosen ? Theme.TEXT : Theme.TEXT_MUTED);
                room -= countWidth + 3;
            }
            fontRendererObj.drawString(
                Theme.ellipsize(fontRendererObj, I18n.format(each.name), room),
                x0 + 24,
                y + (PAGE_HEIGHT - 8) / 2,
                textColor);
        }
        String version = "v" + Tags.VERSION;
        fontRendererObj
            .drawString(Theme.ellipsize(fontRendererObj, version, MENU - 12), x0 + 8, y1 - 12, Theme.TEXT_MUTED);
    }

    /** All, visible or hidden tabs: a row of segments joined into one box. */
    private void drawFilters(int mouseX, int mouseY) {
        int[] edges = filterEdges();
        int x0 = edges[0], x1 = edges[edges.length - 1] - 2, y0 = contentTop, y1 = contentTop + CONTROLS;
        Theme.roundBox(x0, y0, x1, y1, Theme.CONTROL, 0xFF2A3D5C);
        for (Filter each : Filter.values()) {
            int sx0 = edges[each.ordinal()], sx1 = edges[each.ordinal() + 1] - 2;
            boolean over = Theme.inside(mouseX, mouseY, sx0, y0, sx1, y1);
            double lit = filterLight[each.ordinal()].update(over ? 1 : 0, 22);
            if (each == filter) {
                Theme.roundBox(
                    sx0,
                    y0,
                    sx1,
                    y1,
                    Theme.blend(Theme.ACCENT_DIM, Theme.ACCENT_FILL, 0.5 + 0.5 * lit),
                    Theme.ACCENT_EDGE);
            } else if (lit > 0.01) {
                Theme.fill(sx0 + 1, y0 + 1, sx1 - 1, y1 - 1, Theme.blend(Theme.CONTROL, Theme.CONTROL_HOVER, lit));
            }
            int color = each == filter ? Theme.TEXT : Theme.blend(Theme.TEXT_MUTED, Theme.TEXT, lit);
            Theme.centered(fontRendererObj, filterLabel(each), (sx0 + sx1) / 2, y0 + 5, color);
        }
    }

    private void drawList(int mouseX, int mouseY) {
        int x0 = contentLeft, x1 = contentRight;
        Theme.card(x0, listTop, x1, listBottom);
        int shown = (int) Math.round(shownScroll.update(scroll, 18));
        boolean inList = Theme.inside(mouseX, mouseY, x0, listTop, x1, listBottom);
        if (rowCount() == 0) {
            drawEmpty();
            return;
        }
        Theme.clip(x0 + 1, listTop + 1, x1 - 1, listBottom - 1);
        int height = rowHeight();
        if (page == Page.ORDER) {
            drawOrder(shown, inList, mouseX, mouseY);
        }
        for (int i = 0; page != Page.ORDER && i < rowCount(); i++) {
            int y = rowY(i, shown);
            if (y + height < listTop || y > listBottom) {
                continue;
            }
            boolean hovered = inList && Theme.inside(mouseX, mouseY, x0 + 1, y, x1 - 6, y + height);
            if (page == Page.TABS) {
                TabRow row = tabRows.get(i);
                if (row.isHeading()) {
                    drawHeading(row, y, hovered, mouseX, mouseY);
                } else {
                    drawTabRow(row, y, hovered, mouseX, mouseY);
                }
            } else {
                RecipeRow row = recipeRows.get(i);
                if (row.isHeading()) {
                    drawRecipeHeading(row, y, hovered, mouseX, mouseY);
                } else {
                    drawRecipeRow(row, y, hovered, mouseX, mouseY);
                }
            }
        }
        Theme.unclip();
        int total = contentHeight(), visible = listBottom - listTop;
        if (total > visible) {
            Theme.scrollbar(
                x1 - 4,
                listTop + 2,
                listBottom - 2,
                visible,
                total,
                shown / (double) (total - visible),
                inList);
        }
    }

    /** The tabs put first; the one being dragged follows the mouse, the others make room where it would go. */
    private void drawOrder(int shown, boolean inList, int mouseX, int mouseY) {
        boolean dragging = dragIndex >= 0 && dragMoved && dragIndex < orderRows.size();
        int target = dragging ? dropIndex(mouseY) : -1;
        int slot = 0;
        for (int i = 0; i < orderRows.size(); i++) {
            if (dragging && i == dragIndex) {
                continue;
            }
            int place = dragging && slot >= target ? slot + 1 : slot;
            slot++;
            int y = rowY(place, shown);
            if (y + TAB_ROW < listTop || y > listBottom) {
                continue;
            }
            boolean hovered = !dragging && inList
                && Theme.inside(mouseX, mouseY, contentLeft + 1, y, contentRight - 6, y + TAB_ROW);
            drawOrderRow(orderRows.get(i), place + 1, y, hovered, mouseX, mouseY, false);
        }
        if (dragging) {
            int y = Math.max(listTop - TAB_ROW / 2, Math.min(listBottom - TAB_ROW / 2, mouseY - dragGrab));
            int x0 = contentLeft + 2, x1 = contentRight - 7;
            int markY = rowY(target, shown);
            Theme.fill(x0, markY, x1, markY + 1, Theme.WARN);
            Theme.shadow(x0, y, x1, y + TAB_ROW, 4);
            Theme.roundBox(x0, y, x1, y + TAB_ROW, Theme.SELECTED, Theme.WARN);
            drawOrderRow(orderRows.get(dragIndex), target + 1, y, true, -10000, -10000, true);
        }
    }

    private void drawOrderRow(TabRow row, int rank, int y, boolean hovered, int mouseX, int mouseY, boolean floating) {
        int x0 = contentLeft, x1 = contentRight - 6;
        double lit = row.light.update(hovered ? 1 : 0, 22);
        if (!floating && lit > 0.01) {
            Theme.fill(x0 + 1, y, x1, y + TAB_ROW, Theme.blend(0x00FFFFFF, Theme.ROW_HOVER, lit));
        }
        // A grip: the line can be dragged.
        int gripColor = floating ? Theme.WARN : Theme.blend(Theme.TEXT_DISABLED, Theme.TEXT_MUTED, lit);
        for (int dy = 0; dy < 3; dy++) {
            Theme.fill(x0 + 6, y + 9 + dy * 3, x0 + 8, y + 10 + dy * 3, gripColor);
            Theme.fill(x0 + 9, y + 9 + dy * 3, x0 + 11, y + 10 + dy * 3, gripColor);
        }
        String number = String.valueOf(rank);
        int badgeWidth = Math.max(16, fontRendererObj.getStringWidth(number) + 8);
        int bx = x0 + 15, by = y + (TAB_ROW - 12) / 2;
        Theme.roundBox(bx, by, bx + badgeWidth, by + 12, 0xFF3A3218, Theme.blend(Theme.WARN, 0xFF3A3218, 0.4));
        Theme.centered(fontRendererObj, number, bx + badgeWidth / 2, by + 2, Theme.WARN);

        int boxX = bx + badgeWidth + 6, boxY = y + 3;
        boolean hidden = row.hidden();
        Theme.roundBox(boxX, boxY, boxX + 20, boxY + 20, 0xFF0A1424, hidden ? 0xFF4A2A30 : Theme.BOX_BORDER);
        drawHandlerIcon(row, boxX + 2, boxY + 2, hidden);

        int textX = boxX + 28, room = upX() - 8 - textX;
        int nameX = textX;
        if (hidden) {
            Icons.draw(Icons.SMALL_EYE_OFF, textX, y + 4, Theme.DANGER);
            nameX += 10;
        }
        fontRendererObj.drawString(
            Theme.ellipsize(fontRendererObj, row.name, room - (nameX - textX)),
            nameX,
            y + 4,
            hidden ? Theme.TEXT_MUTED : Theme.TEXT);
        String detail = row.entry == null ? I18n.format("neiextend.tabs.missing")
            : hidden ? I18n.format("neiextend.order.hidden", row.mod) : row.mod;
        fontRendererObj.drawString(
            Theme.ellipsize(fontRendererObj, detail, room),
            textX,
            y + 14,
            row.entry == null || hidden ? Theme.WARN : Theme.blend(Draw.modColor(row.mod), Theme.TEXT_MUTED, 0.25));

        int buttonY = y + (TAB_ROW - ORDER_BUTTON) / 2;
        boolean up = orderButton(upX(), buttonY, Icons.SMALL_UP, rank > 1, false, mouseX, mouseY);
        boolean down = orderButton(downX(), buttonY, Icons.SMALL_DOWN, rank < orderRows.size(), false, mouseX, mouseY);
        boolean remove = orderButton(removeX(), buttonY, Icons.SMALL_CLEAR, true, true, mouseX, mouseY);
        if (floating || hoverText != null) {
            return;
        }
        if (up) {
            hoverText = I18n.format("neiextend.order.up");
        } else if (down) {
            hoverText = I18n.format("neiextend.order.down");
        } else if (remove) {
            hoverText = I18n.format("neiextend.order.remove");
        } else if (hovered) {
            hoverText = row.name + "\n" + I18n.format("neiextend.order.tip", rank);
        }
    }

    /** A small square button of a line of the order; returns whether the mouse is on it. */
    private boolean orderButton(int x, int y, String[] icon, boolean enabled, boolean danger, int mouseX, int mouseY) {
        boolean over = enabled && Theme.inside(mouseX, mouseY, x, y, x + ORDER_BUTTON, y + ORDER_BUTTON);
        int accent = danger ? Theme.DANGER : Theme.ACCENT;
        int background = !enabled ? Theme.CONTROL_DISABLED
            : over ? (danger ? 0xFF3A2326 : Theme.CONTROL_HOVER) : Theme.CONTROL;
        Theme.roundBox(x, y, x + ORDER_BUTTON, y + ORDER_BUTTON, background, over ? accent : 0xFF2A3D5C);
        Icons.drawCentered(
            icon,
            x,
            y,
            x + ORDER_BUTTON,
            y + ORDER_BUTTON,
            !enabled ? Theme.TEXT_DISABLED : over ? (danger ? Theme.DANGER : Theme.TEXT) : Theme.TEXT_MUTED);
        return over;
    }

    private void drawEmpty() {
        int centerX = (contentLeft + contentRight) / 2, centerY = (listTop + listBottom) / 2;
        boolean searching = !search.getText()
            .trim()
            .isEmpty() || page == Page.TABS && filter != Filter.ALL;
        String[] icon = searching ? Icons.SMALL_SEARCH
            : page == Page.RECIPES ? Icons.TARGET : page == Page.ORDER ? Icons.STAR : Icons.TABS;
        Icons.draw(icon, centerX - Icons.width(icon) / 2, centerY - 22, Theme.TEXT_DISABLED);
        String title = I18n.format(
            searching ? "neiextend.empty.search"
                : "neiextend.empty." + page.name()
                    .toLowerCase(Locale.ROOT));
        Theme.centered(fontRendererObj, title, centerX, centerY - 6, Theme.TEXT_MUTED);
        if (!searching && (page == Page.RECIPES || page == Page.ORDER)) {
            int y = centerY + 8;
            for (Object line : fontRendererObj.listFormattedStringToWidth(
                I18n.format(page == Page.ORDER ? "neiextend.empty.order_text" : "neiextend.empty.recipes_text"),
                300)) {
                Theme.centered(fontRendererObj, (String) line, centerX, y, Theme.TEXT_DISABLED);
                y += 10;
            }
        }
    }

    private void drawTabRow(TabRow row, int y, boolean hovered, int mouseX, int mouseY) {
        int x0 = contentLeft, x1 = contentRight - 6;
        boolean hidden = row.hidden();
        double lit = row.light.update(hovered ? 1 : 0, 22);
        if (lit > 0.01) {
            Theme.fill(x0 + 1, y, x1, y + TAB_ROW, Theme.blend(0x00FFFFFF, Theme.ROW_HOVER, lit));
        }
        int boxX = x0 + 8, boxY = y + 3;
        Theme.roundBox(boxX, boxY, boxX + 20, boxY + 20, 0xFF0A1424, hidden ? 0xFF4A2A30 : Theme.BOX_BORDER);
        drawHandlerIcon(row, boxX + 2, boxY + 2, hidden);

        int textX = x0 + 36;
        int tagsWidth = 0;
        if (row.entry != null) {
            tagsWidth = drawTags(row.entry, switchX() - 8, y + (TAB_ROW - 12) / 2, mouseX, mouseY);
        }
        drawPlace(row, y, lit, mouseX, mouseY);
        int room = placeX(row) - 6 - textX;
        int nameColor = hidden ? Theme.TEXT_MUTED
            : Theme.blend(Theme.blend(Theme.TEXT_MUTED, Theme.TEXT, 0.8), Theme.TEXT, lit);
        int nameX = textX;
        if (hidden) {
            Icons.draw(Icons.SMALL_EYE_OFF, textX, y + 4, Theme.DANGER);
            nameX += 10;
        }
        String name = Theme.ellipsize(fontRendererObj, row.name, room - (nameX - textX));
        fontRendererObj.drawString(name, nameX, y + 4, nameColor);
        if (hidden) {
            // Struck through: out of NEI's way.
            Theme.fill(nameX - 1, y + 8, nameX + fontRendererObj.getStringWidth(name) + 1, y + 9, 0xA0E5534B);
        }
        String mod = Theme.ellipsize(fontRendererObj, row.mod, room / 2);
        int modColor = Theme.blend(Draw.modColor(row.mod), Theme.TEXT_MUTED, hidden ? 0.6 : 0.25);
        fontRendererObj.drawString(mod, textX, y + 14, modColor);
        int idX = textX + fontRendererObj.getStringWidth(mod) + 6;
        String detail = row.entry == null ? I18n.format("neiextend.tabs.missing") : row.id;
        fontRendererObj.drawString(
            Theme.ellipsize(fontRendererObj, detail, textX + room - idX),
            idX,
            y + 14,
            row.entry == null ? Theme.WARN : Theme.TEXT_DISABLED);

        drawSwitch(row, switchX(), y + (TAB_ROW - 14) / 2, hovered);
        if (hovered && hoverText == null
            && Theme.inside(mouseX, mouseY, x0, y, switchX() - 8 - tagsWidth, y + TAB_ROW)) {
            hoverText = row.name + "\n"
                + I18n.format(hidden ? "neiextend.tabs.tip_hidden" : "neiextend.tabs.tip_shown")
                + "\n"
                + row.mod
                + " - "
                + row.id;
        }
    }

    /**
     * A tab's place among the ones put first: up, the place ("#3", or a dash when it isn't first), down. The place can
     * be typed after a click on it, and the wheel moves it too.
     */
    private void drawPlace(TabRow row, int y, double lit, int mouseX, int mouseY) {
        int x = placeX(row), y0 = y + (TAB_ROW - PLACE_BUTTON) / 2, y1 = y0 + PLACE_BUTTON;
        int place = HiddenStore.INSTANCE.pinPosition(row.id);
        boolean first = place >= 0;
        boolean editing = row.id.equals(placeEditId);
        Integer part = placePart(row, y, mouseX, mouseY);
        boolean canUp = first ? place > 0 : row.entry != null;
        placeButton(x, y0, Icons.SMALL_UP, canUp, part != null && part == -1);
        placeButton(x + PLACE_WIDTH - PLACE_BUTTON, y0, Icons.SMALL_DOWN, first, part != null && part == 1);
        int nx0 = x + PLACE_BUTTON + 1, nx1 = x + PLACE_WIDTH - PLACE_BUTTON - 1;
        boolean overPlace = part != null && part == 0;
        if (editing) {
            Theme.roundBox(nx0, y0 - 1, nx1, y1 + 1, 0xFF0A1424, Theme.WARN);
            boolean caret = System.currentTimeMillis() / 500 % 2 == 0;
            String text = placeInput + (caret ? "_" : "");
            Theme.centered(fontRendererObj, text, (nx0 + nx1) / 2, y0 + 2, Theme.TEXT);
        } else if (first || overPlace) {
            if (overPlace) {
                Theme.roundRect(nx0, y0 - 1, nx1, y1 + 1, 0x30F2C14E);
            }
            String text = first ? "#" + (place + 1) : "-";
            Theme.centered(fontRendererObj, text, (nx0 + nx1) / 2, y0 + 2, first ? Theme.WARN : Theme.TEXT_MUTED);
        } else {
            Theme.centered(
                fontRendererObj,
                "-",
                (nx0 + nx1) / 2,
                y0 + 2,
                Theme.blend(Theme.TEXT_DISABLED, Theme.TEXT_MUTED, lit));
        }
        if (part != null && !editing) {
            hoverText = part == 0 ? I18n.format("neiextend.place.edit")
                : I18n.format(
                    part < 0 ? (first ? "neiextend.place.up" : "neiextend.place.pin")
                        : place == HiddenStore.INSTANCE.pinned()
                            .size() - 1 ? "neiextend.place.unpin" : "neiextend.place.down",
                    place + 1);
        }
    }

    private void placeButton(int x, int y, String[] icon, boolean enabled, boolean over) {
        boolean lit = enabled && over;
        Theme.roundBox(
            x,
            y,
            x + PLACE_BUTTON,
            y + PLACE_BUTTON,
            !enabled ? Theme.CONTROL_DISABLED : lit ? 0xFF3A3218 : Theme.CONTROL,
            lit ? Theme.WARN : 0xFF2A3D5C);
        Icons.drawCentered(
            icon,
            x,
            y,
            x + PLACE_BUTTON,
            y + PLACE_BUTTON,
            !enabled ? Theme.TEXT_DISABLED : lit ? Theme.WARN : Theme.TEXT_MUTED);
    }

    /**
     * The heading of a mod's tabs: folded or not, its name and counts, a star putting all its tabs first, a switch
     * hiding or showing all of them.
     */
    private void drawHeading(TabRow heading, int y, boolean hovered, int mouseX, int mouseY) {
        int x0 = contentLeft, x1 = contentRight - 6;
        double lit = heading.light.update(hovered ? 1 : 0, 22);
        int color = Draw.modColor(heading.mod);
        Theme.fill(x0 + 1, y + 1, x1, y + TAB_ROW - 1, Theme.blend(0xFF101C30, 0xFF16263F, lit));
        Theme.fill(x0 + 1, y + 1, x0 + 3, y + TAB_ROW - 1, color);
        boolean folded = foldedMods.contains(heading.mod) && search.getText()
            .trim()
            .isEmpty();
        Icons.draw(folded ? Icons.SMALL_ARROW : Icons.SMALL_DOWN, x0 + 9, y + 9, Theme.TEXT_MUTED);

        int total = heading.members.size(), hidden = 0;
        for (TabRow member : heading.members) {
            if (member.hidden()) {
                hidden++;
            }
        }
        int pinned = pinnedCount(heading);
        StringBuilder counts = new StringBuilder(I18n.format("neiextend.group.tabs", total));
        if (hidden > 0) {
            counts.append("  ")
                .append(I18n.format("neiextend.group.hidden", hidden));
        }
        if (pinned > 0) {
            counts.append("  ")
                .append(I18n.format("neiextend.group.first", pinned));
        }
        int starX = headingStarX();
        int textX = x0 + 22, room = starX - 10 - textX;
        String name = Theme.ellipsize(fontRendererObj, heading.mod, room * 2 / 3);
        fontRendererObj.drawStringWithShadow(name, textX, y + 9, Theme.blend(color, Theme.TEXT, 0.35));
        int countsX = textX + fontRendererObj.getStringWidth(name) + 8;
        fontRendererObj.drawString(
            Theme.ellipsize(fontRendererObj, counts.toString(), textX + room - countsX),
            countsX,
            y + 9,
            Theme.TEXT_MUTED);

        boolean overStar = Theme.inside(mouseX, mouseY, starX - 2, y + 4, starX + 11, y + TAB_ROW - 4);
        Icons.draw(
            pinned > 0 ? Icons.SMALL_STAR : Icons.SMALL_STAR_EMPTY,
            starX + 1,
            y + (TAB_ROW - 7) / 2,
            pinned > 0 || overStar ? Theme.WARN : Theme.TEXT_MUTED);

        double at = heading.knob.update(heading.shownShare(), 18);
        drawSwitchAt(switchX(), y + (TAB_ROW - 14) / 2, at, hovered);

        int sy = y + (TAB_ROW - 14) / 2;
        if (overStar) {
            hoverText = I18n.format(pinned > 0 ? "neiextend.group.unpin" : "neiextend.group.pin", heading.mod);
        } else if (Theme.inside(mouseX, mouseY, switchX(), sy, switchX() + SWITCH, sy + 14)) {
            hoverText = I18n
                .format(heading.shownShare() > 0 ? "neiextend.group.hide" : "neiextend.group.show", heading.mod);
        } else if (hovered) {
            hoverText = heading.mod + "\n" + I18n.format(folded ? "neiextend.group.unfold" : "neiextend.group.fold");
        }
    }

    /** Whether the tab shows recipes (R) and uses (U): small tags ending at {@code right}; returns their width. */
    private int drawTags(HandlerCatalog.Entry entry, int right, int y, int mouseX, int mouseY) {
        int x = right;
        if (entry.usage) {
            x -= 14;
            drawTag("U", x, y, 0xFFB37FEB, "neiextend.tag.usage", mouseX, mouseY);
        }
        if (entry.crafting) {
            x -= entry.usage ? 16 : 14;
            drawTag("R", x, y, 0xFF39C5CF, "neiextend.tag.crafting", mouseX, mouseY);
        }
        return right - x;
    }

    private void drawTag(String letter, int x, int y, int color, String tip, int mouseX, int mouseY) {
        Theme.roundBox(x, y, x + 14, y + 12, Theme.CONTROL, Theme.blend(color, Theme.CONTROL, 0.4));
        Theme.centered(fontRendererObj, letter, x + 7, y + 2, color);
        if (Theme.inside(mouseX, mouseY, x, y, x + 14, y + 12)) {
            hoverText = I18n.format(tip);
        }
    }

    /** The tab's icon as NEI shows it on its tab: a picture, an item, or the first letters of its name. */
    private void drawHandlerIcon(TabRow row, int x, int y, boolean dim) {
        drawHandlerIcon(row.entry, row.name, x, y, dim);
    }

    private void drawHandlerIcon(HandlerCatalog.Entry entry, String name, int x, int y, boolean dim) {
        HandlerInfo info = entry == null ? null : entry.info;
        DrawableResource image = info == null ? null : info.getImage();
        ItemStack stack = info == null ? null : info.getItemStack();
        if (image != null) {
            GL11.glColor4f(1f, 1f, 1f, 1f);
            GL11.glEnable(GL11.GL_BLEND);
            image.draw(x + (16 - image.getWidth()) / 2, y + (16 - image.getHeight()) / 2);
            GL11.glColor4f(1f, 1f, 1f, 1f);
        } else if (stack != null) {
            Draw.item(stack, x, y, 16);
        } else {
            String letters = name.length() > 2 ? name.substring(0, 2) : name;
            Theme.centered(fontRendererObj, letters, x + 8, y + 4, Theme.TEXT_MUTED);
        }
        if (dim) {
            // A dark veil: the tab is out of NEI.
            GL11.glDisable(GL11.GL_DEPTH_TEST);
            Theme.fill(x, y, x + 16, y + 16, 0x900A1424);
        }
    }

    /** On: the tab is shown in NEI; off: hidden. */
    private void drawSwitch(TabRow row, int x0, int y, boolean hovered) {
        drawSwitchAt(x0, y, row.knob.update(row.hidden() ? 0 : 1, 18), hovered);
    }

    /** A switch with its knob at {@code at}: 0 off (hidden), 1 on (shown), between for some. */
    private void drawSwitchAt(int x0, int y, double at, boolean hovered) {
        int x1 = x0 + SWITCH, y1 = y + 14;
        int track = Theme.blend(0xFF3A2326, Theme.ACCENT_DIM, at);
        Theme.fill(x0 + 1, y, x1 - 1, y1, track);
        Theme.fill(x0, y + 1, x1, y1 - 1, track);
        int edge = Theme.blend(Theme.DANGER, Theme.ACCENT, at);
        Theme.outline(x0, y, x1, y1, hovered ? edge : Theme.blend(edge, Theme.BORDER, 0.4));
        int knobX = x0 + 2 + (int) Math.round(at * (SWITCH - 14));
        Theme.fill(knobX, y + 2, knobX + 10, y1 - 2, Theme.blend(Theme.TEXT_MUTED, Theme.TEXT, at));
    }

    /**
     * The heading of a tab's hidden recipes: folded or not, the tab's icon, name and how many of its recipes are
     * hidden, and a button showing them all again.
     */
    private void drawRecipeHeading(RecipeRow heading, int y, boolean hovered, int mouseX, int mouseY) {
        int x0 = contentLeft, x1 = contentRight - 6;
        double lit = heading.light.update(hovered ? 1 : 0, 22);
        HandlerCatalog.Entry entry = catalogById.get(heading.handlerId);
        int color = Draw.modColor(entry != null ? entry.mod : heading.tab);
        Theme.fill(x0 + 1, y + 1, x1, y + RECIPE_ROW - 1, Theme.blend(0xFF101C30, 0xFF16263F, lit));
        Theme.fill(x0 + 1, y + 1, x0 + 3, y + RECIPE_ROW - 1, color);
        boolean folded = foldedRecipeTabs.contains(heading.handlerId) && !searching();
        Icons.draw(folded ? Icons.SMALL_ARROW : Icons.SMALL_DOWN, x0 + 9, y + 12, Theme.TEXT_MUTED);
        drawHandlerIcon(entry, heading.tab, x0 + 21, y + 7, false);

        int hidden = heading.stillHidden()
            .size();
        int textX = x0 + 43, room = restoreX() - 10 - textX;
        String name = Theme.ellipsize(fontRendererObj, heading.tab, room * 2 / 3);
        fontRendererObj.drawStringWithShadow(name, textX, y + 6, Theme.blend(color, Theme.TEXT, 0.35));
        String mod = entry != null ? entry.mod : I18n.format("neiextend.open.missing");
        fontRendererObj.drawString(Theme.ellipsize(fontRendererObj, mod, room), textX, y + 17, Theme.TEXT_DISABLED);
        String count = I18n.format("neiextend.recipes.count", hidden);
        int countX = textX + fontRendererObj.getStringWidth(name) + 8;
        if (countX + fontRendererObj.getStringWidth(count) <= textX + room) {
            Theme.pill(fontRendererObj, count, countX, y + 4, Theme.TEXT_MUTED);
        }

        boolean confirming = System.currentTimeMillis() < heading.confirmUntil;
        boolean over = hidden > 0 && rowButton(
            heading.buttonLight,
            y,
            Icons.SMALL_EYE,
            I18n.format(confirming ? "neiextend.recipes.sure" : "neiextend.recipes.show_tab"),
            confirming ? Theme.DANGER : Theme.ACCENT,
            mouseX,
            mouseY);
        if (over) {
            hoverText = I18n.format("neiextend.recipes.show_tab_tip", hidden, heading.tab);
        } else if (hovered && hoverText == null) {
            hoverText = heading.tab + "\n"
                + (searching() ? I18n.format("neiextend.recipes.tip")
                    : I18n.format(folded ? "neiextend.recipes.unfold" : "neiextend.recipes.fold"));
        }
    }

    private void drawRecipeRow(RecipeRow row, int y, boolean hovered, int mouseX, int mouseY) {
        int x0 = contentLeft, x1 = contentRight - 6;
        double lit = row.light.update(hovered ? 1 : 0, 22);
        if (lit > 0.01) {
            Theme.fill(x0 + 1, y, x1, y + RECIPE_ROW, Theme.blend(0x00FFFFFF, Theme.ROW_HOVER, lit));
        }
        // Under its tab's heading.
        Theme.fill(x0 + 10, y, x0 + 11, y + RECIPE_ROW, Theme.BOX_BORDER);
        int boxX = x0 + 18, boxY = y + 4;
        Theme.roundBox(
            boxX,
            boxY,
            boxX + 22,
            boxY + 22,
            0xFF0A1424,
            row.restored ? Theme.blend(Theme.SUCCESS, Theme.BOX_BORDER, 0.5) : Theme.BOX_BORDER);
        if (row.result != null) {
            drawStack(row.result, boxX + 3, boxY + 3, 16, row.result.stackSize, row.restored);
            if (Theme.inside(mouseX, mouseY, boxX, boxY, boxX + 22, boxY + 22)) {
                hoverStack = row.result;
            }
        } else {
            Theme.centered(fontRendererObj, "?", boxX + 11, boxY + 7, Theme.WARN);
        }

        int restoreX = restoreX();
        int ingredientsRight = restoreX - 10;
        int shownIngredients = Math.min(row.ingredients.size(), MAX_INGREDIENTS);
        boolean more = row.ingredients.size() > MAX_INGREDIENTS;
        if (more) {
            shownIngredients = MAX_INGREDIENTS - 1;
        }
        int ingredientsLeft = ingredientsRight - MAX_INGREDIENTS * INGREDIENT;
        int ix = ingredientsLeft, iy = y + (RECIPE_ROW - 12) / 2;
        for (int i = 0; i < shownIngredients; i++) {
            ItemStack ingredient = row.ingredients.get(i);
            drawStack(ingredient, ix + 1, iy, 12, ingredient.stackSize, row.restored);
            if (Theme.inside(mouseX, mouseY, ix, iy, ix + INGREDIENT, iy + 12)) {
                hoverStack = ingredient;
            }
            ix += INGREDIENT;
        }
        if (more) {
            String rest = "+" + (row.ingredients.size() - shownIngredients);
            fontRendererObj.drawString(rest, ix + 1, iy + 2, Theme.TEXT_MUTED);
        }
        if (!row.ingredients.isEmpty()) {
            Icons.draw(Icons.SMALL_ARROW, ingredientsLeft - 12, iy + 3, Theme.TEXT_DISABLED);
        }

        int textX = boxX + 30, room = ingredientsLeft - 16 - textX;
        int nameColor = row.restored ? Theme.TEXT_MUTED : row.result == null ? Theme.WARN : Theme.TEXT;
        fontRendererObj.drawString(Theme.ellipsize(fontRendererObj, row.name, room), textX, y + 6, nameColor);
        if (row.restored) {
            Icons.draw(Icons.SMALL_CHECK, textX, y + 18, Theme.SUCCESS);
            fontRendererObj.drawString(
                Theme.ellipsize(fontRendererObj, I18n.format("neiextend.recipes.restored"), room - 10),
                textX + 10,
                y + 17,
                Theme.SUCCESS);
        } else {
            fontRendererObj.drawString(
                Theme.ellipsize(fontRendererObj, ago(row.recipe.time), room),
                textX,
                y + 17,
                Theme.TEXT_DISABLED);
        }

        boolean over = rowButton(
            row.buttonLight,
            y,
            row.restored ? Icons.SMALL_EYE_OFF : Icons.SMALL_EYE,
            I18n.format(row.restored ? "neiextend.recipes.hide_again" : "neiextend.recipes.restore"),
            row.restored ? Theme.DANGER : Theme.ACCENT,
            mouseX,
            mouseY);
        if (over) {
            hoverText = I18n
                .format(row.restored ? "neiextend.recipes.hide_again_tip" : "neiextend.recipes.restore_tip");
        } else if (hovered && hoverText == null && hoverStack == null) {
            hoverText = row.name + "\n" + I18n.format("neiextend.recipes.tip");
        }
    }

    /** The button on the right of a line of the recipes, lit by the mouse; returns whether the mouse is on it. */
    private boolean rowButton(Smooth light, int y, String[] icon, String label, int accent, int mouseX, int mouseY) {
        int x0 = restoreX(), x1 = x0 + RESTORE_WIDTH, by = y + (RECIPE_ROW - 16) / 2;
        boolean over = Theme.inside(mouseX, mouseY, x0, by, x1, by + 16);
        double lit = light.update(over ? 1 : 0, 22);
        if (lit > 0.01) {
            Theme.glow(x0, by, x1, by + 16, accent, lit * 0.4);
        }
        Theme.roundBox(
            x0,
            by,
            x1,
            by + 16,
            Theme.blend(Theme.CONTROL, Theme.CONTROL_HOVER, lit),
            Theme.blend(0xFF2A3D5C, accent, Math.max(lit, accent == Theme.DANGER ? 0.6 : 0)));
        int color = accent == Theme.DANGER ? Theme.blend(accent, Theme.TEXT, lit)
            : Theme.blend(Theme.TEXT_MUTED, Theme.TEXT, lit);
        Icons.draw(icon, x0 + 6, by + 5, color);
        fontRendererObj
            .drawString(Theme.ellipsize(fontRendererObj, label, RESTORE_WIDTH - 20), x0 + 16, by + 4, color);
        return over;
    }

    /** An item with how many of it there are in its corner, as in the inventory; faded when {@code dim}. */
    private void drawStack(ItemStack stack, int x, int y, int size, int count, boolean dim) {
        Draw.item(stack, x, y, size);
        GL11.glDisable(GL11.GL_DEPTH_TEST);
        if (count > 1) {
            String text = String.valueOf(count);
            int textX = x + size + 1 - fontRendererObj.getStringWidth(text);
            fontRendererObj.drawStringWithShadow(text, textX, y + size - 7, Theme.TEXT);
        }
        if (dim) {
            Theme.fill(x, y, x + size, y + size, 0x900A1424);
        }
    }

    /** When the recipe was hidden, as "hidden 5 min ago". */
    private static String ago(long time) {
        if (time <= 0) {
            return "";
        }
        long minutes = Math.max(0, System.currentTimeMillis() - time) / 60000;
        if (minutes < 1) {
            return I18n.format("neiextend.recipes.ago.now");
        } else if (minutes < 60) {
            return I18n.format("neiextend.recipes.ago.minutes", minutes);
        } else if (minutes < 60 * 24) {
            return I18n.format("neiextend.recipes.ago.hours", minutes / 60);
        }
        return I18n.format("neiextend.recipes.ago.days", minutes / (60 * 24));
    }

    /** At the bottom: how much the list shows, and where it is all kept. */
    private void drawNote() {
        int y = contentBottom - 9;
        if (status != null && System.currentTimeMillis() < statusUntil) {
            Icons.draw(Icons.SMALL_WARN, contentLeft + 1, y, Theme.WARN);
            fontRendererObj.drawString(status, contentLeft + 11, y, Theme.WARN);
            return;
        }
        String count = page == Page.TABS ? I18n.format("neiextend.note.tabs", tabCount, catalog.size())
            : page == Page.ORDER ? I18n.format("neiextend.note.order", orderRows.size())
                : searching() ? I18n.format(
                    "neiextend.note.recipes_found",
                    recipeCount,
                    HiddenStore.INSTANCE.recipes()
                        .size())
                    : I18n.format("neiextend.note.recipes", recipeCount);
        fontRendererObj.drawString(count, contentLeft + 1, y, Theme.TEXT_DISABLED);
        String file = I18n.format("neiextend.note.file");
        int fileWidth = fontRendererObj.getStringWidth(file);
        Icons.draw(Icons.SMALL_FOLDER, contentRight - fileWidth - 12, y, Theme.TEXT_DISABLED);
        fontRendererObj.drawString(file, contentRight - fileWidth, y, Theme.TEXT_DISABLED);
    }

    /** How it works, in a few boxes. */
    private void drawHelp() {
        int x0 = contentLeft, x1 = contentRight - 6, gap = 6;
        int shown = (int) Math.round(shownScroll.update(scroll, 18));
        Theme.clip(x0, contentTop, contentRight, contentBottom);
        String[] sections = { "tab", "order", "recipe", "restore", "about" };
        String[][] icons = { Icons.EYE_OFF, Icons.STAR, Icons.TARGET, Icons.SMALL_UNDO, Icons.SMALL_KEY };
        int y = contentTop - shown;
        for (int i = 0; i < sections.length; i++) {
            String key = "neiextend.help." + sections[i];
            List<String> lines = new ArrayList<>();
            for (Object line : fontRendererObj
                .listFormattedStringToWidth(I18n.format(key + "_text", KeyHandler.keyName()), x1 - x0 - 24)) {
                lines.add((String) line);
            }
            int boxHeight = 22 + lines.size() * 10;
            Theme.card(x0, y, x1, y + boxHeight);
            String[] icon = icons[i];
            Icons.draw(icon, x0 + 8, y + 10 - icon.length / 2, Theme.ACCENT);
            fontRendererObj.drawString(
                I18n.format(key)
                    .toUpperCase(Locale.ROOT),
                x0 + 8 + Icons.width(icon) + 5,
                y + 6,
                Theme.TEXT);
            int ly = y + 20;
            for (String line : lines) {
                fontRendererObj.drawString(line, x0 + 12, ly, Theme.TEXT_MUTED);
                ly += 10;
            }
            y += boxHeight + gap;
        }
        Theme.unclip();
        helpHeight = y + shown - gap - contentTop;
        int visible = contentBottom - contentTop;
        if (helpHeight > visible) {
            Theme.scrollbar(
                contentRight - 3,
                contentTop + 2,
                contentBottom - 2,
                visible,
                helpHeight,
                shown / (double) (helpHeight - visible),
                true);
        }
    }

    /** How far the lookup of all recipes of a tab got, over the window, with a button to cancel it. */
    private void drawLookup(AllRecipes running, int mouseX, int mouseY) {
        GL11.glPushMatrix();
        GL11.glTranslatef(0f, 0f, 200f);
        Theme.fill(left + 1, top + WindowHeader.HEIGHT, left + WIDTH - 1, top + HEIGHT - 1, 0xC0050A12);
        int w = 330, h = 98, x0 = left + (WIDTH - w) / 2, y0 = top + (HEIGHT - h) / 2, x1 = x0 + w, y1 = y0 + h;
        Theme.shadow(x0, y0, x1, y1, 6);
        Theme.roundBox(x0, y0, x1, y1, 0xF60B1424, Theme.FRAME);
        Icons.draw(Icons.SMALL_SEARCH, x0 + 10, y0 + 10, Theme.ACCENT);
        fontRendererObj.drawString(
            Theme.ellipsize(fontRendererObj, I18n.format("neiextend.scan.title", running.name), w - 32),
            x0 + 22,
            y0 + 10,
            Theme.TEXT);

        AllRecipes.Stage stage = running.stage();
        String kind = I18n.format(running.crafting() ? "neiextend.scan.recipes" : "neiextend.scan.uses");
        String what = I18n.format(
            "neiextend.scan." + stage.name()
                .toLowerCase(Locale.ROOT),
            kind);
        fontRendererObj.drawString(Theme.ellipsize(fontRendererObj, what, w - 24), x0 + 12, y0 + 25, Theme.TEXT_MUTED);

        int bx0 = x0 + 12, bx1 = x1 - 12, by0 = y0 + 40, by1 = by0 + 8;
        Theme.roundRect(bx0, by0, bx1, by1, 0xFF0B0E12);
        int total = running.total();
        boolean counted = stage == AllRecipes.Stage.ITEMS && total > 0;
        if (counted) {
            int filled = bx0 + (int) Math.round((bx1 - bx0) * Math.min(1.0, running.done() / (double) total));
            if (filled > bx0 + 1) {
                Theme.gradient(bx0 + 1, by0 + 1, filled - 1, by1 - 1, Theme.ACCENT, Theme.ACCENT_DIM);
            }
        } else {
            // Not counted: a short bar running along the track.
            int span = 70, travel = bx1 - bx0 + span;
            int at = bx0 - span + (int) (running.millis() % 1500 * travel / 1500);
            int s0 = Math.max(bx0 + 1, at), s1 = Math.min(bx1 - 1, at + span);
            if (s1 > s0) {
                Theme.gradient(s0, by0 + 1, s1, by1 - 1, Theme.ACCENT, Theme.ACCENT_DIM);
            }
        }

        String count = counted
            ? I18n.format("neiextend.scan.progress", running.done(), total, (int) (100L * running.done() / total))
            : I18n.format("neiextend.scan.time", running.millis() / 1000.0);
        fontRendererObj.drawString(count, bx0, by1 + 6, Theme.TEXT_MUTED);
        if (stage == AllRecipes.Stage.ITEMS || stage == AllRecipes.Stage.MERGE) {
            String found = I18n.format("neiextend.scan.found", running.found());
            fontRendererObj.drawString(found, bx1 - fontRendererObj.getStringWidth(found), by1 + 6, Theme.SUCCESS);
        }

        String cancel = I18n.format("neiextend.scan.cancel");
        cancelX1 = x1 - 12;
        cancelX0 = cancelX1 - fontRendererObj.getStringWidth(cancel) - 16;
        cancelY1 = y1 - 8;
        cancelY0 = cancelY1 - 16;
        boolean over = Theme.inside(mouseX, mouseY, cancelX0, cancelY0, cancelX1, cancelY1);
        Theme.roundBox(
            cancelX0,
            cancelY0,
            cancelX1,
            cancelY1,
            over ? 0xFF3A2326 : Theme.CONTROL,
            over ? Theme.DANGER : 0xFF2A3D5C);
        Theme.centered(
            fontRendererObj,
            cancel,
            (cancelX0 + cancelX1) / 2,
            cancelY0 + 4,
            over ? Theme.DANGER : Theme.TEXT);
        fontRendererObj.drawString(
            Theme.ellipsize(fontRendererObj, I18n.format("neiextend.scan.hint"), cancelX0 - 8 - bx0),
            bx0,
            cancelY0 + 4,
            Theme.TEXT_DISABLED);
        GL11.glPopMatrix();
    }

    /** A tooltip: its first line bright, the others (after "\n") muted. */
    private void tooltip(String text, int mouseX, int mouseY) {
        List<String> lines = new ArrayList<>();
        List<Integer> colors = new ArrayList<>();
        String[] parts = text.split("\\\\n|\n");
        for (int i = 0; i < parts.length; i++) {
            for (Object line : fontRendererObj.listFormattedStringToWidth(parts[i], 260)) {
                lines.add((String) line);
                colors.add(i == 0 ? Theme.TEXT : Theme.TEXT_MUTED);
            }
        }
        Draw.tooltip(fontRendererObj, lines, colors, mouseX, mouseY, width, height);
    }
}
