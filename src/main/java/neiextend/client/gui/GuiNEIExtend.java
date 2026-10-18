package neiextend.client.gui;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
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
 * The mod's window: every recipe tab of NEI with a switch to hide or show it, the recipes hidden so far with a way to
 * bring each back, and how it all works. Opened from the bar beside NEI's recipe screen, or with its key; closing it
 * goes back to the recipe screen it was opened from, looked up again with the changes.
 */
public class GuiNEIExtend extends ScaledScreen {

    public static final int WIDTH = 560, HEIGHT = 372;
    private static final int MENU = 96, PAGE_HEIGHT = 25;
    private static final int TAB_ROW = 26, RECIPE_ROW = 30, CONTROLS = 18, SWITCH = 24, NOTE = 14;
    private static final int RESTORE_WIDTH = 74, INGREDIENT = 14, MAX_INGREDIENTS = 9;
    /** How long a "show everything" waits for the second click that confirms it (ms). */
    private static final long CONFIRM_MS = 3000, STATUS_MS = 3000;
    private static final int ID_CLOSE = 900, ID_SHOW_ALL = 1;

    /** The pages of the menu, in its order. */
    private enum Page {

        TABS(Icons.TABS, "neiextend.page.tabs"),
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

    /** A line of the tabs' list: a tab NEI has, or a hidden one whose mod is gone. */
    private static final class TabRow {

        final HandlerCatalog.Entry entry;
        final String id, name, mod, searchText;
        final Smooth light = new Smooth(0), knob = new Smooth(0);

        TabRow(HandlerCatalog.Entry entry) {
            this.entry = entry;
            this.id = entry.id;
            this.name = entry.name;
            this.mod = entry.mod;
            this.searchText = entry.searchText;
        }

        TabRow(HiddenStore.HiddenTab tab) {
            this.entry = null;
            this.id = tab.id;
            this.name = tab.name;
            this.mod = tab.mod;
            this.searchText = (tab.name + "\n" + tab.mod + "\n" + tab.id).toLowerCase(Locale.ROOT);
        }

        boolean hidden() {
            return HiddenStore.INSTANCE.isTabHidden(id);
        }

        /** The switch starts where it is, instead of sliding there when the list is shown. */
        TabRow settled() {
            knob.set(hidden() ? 0 : 1);
            return this;
        }
    }

    /** A line of the recipes' list: one hidden recipe. */
    private static final class RecipeRow {

        final HiddenStore.HiddenRecipe recipe;
        final ItemStack result;
        final List<ItemStack> ingredients;
        final String name, searchText;
        final Smooth light = new Smooth(0), restoreLight = new Smooth(0);

        RecipeRow(HiddenStore.HiddenRecipe recipe) {
            this.recipe = recipe;
            this.result = recipe.result();
            this.ingredients = recipe.ingredients();
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
    }

    /** The page last open: the window opens on it again. */
    private static Page lastPage = Page.TABS;

    private final GuiScreen parent;
    private final int revisionAtOpen = HiddenStore.INSTANCE.revision();
    private final List<HandlerCatalog.Entry> catalog = HandlerCatalog.all();
    private Page page = lastPage;
    private Filter filter = Filter.ALL;

    private final List<TabRow> tabRows = new ArrayList<>();
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
    /** A short message in the note at the bottom (a tab that could not be opened), until when it shows. */
    private String status;
    private long statusUntil;
    /** What the tooltip under the mouse says, set while drawing and drawn over everything. */
    private String hoverText;
    private ItemStack hoverStack;

    public GuiNEIExtend(GuiScreen parent) {
        this.parent = parent;
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

        String text = search == null ? "" : search.getText();
        boolean focused = search == null || search.isFocused();
        search = new FlatTextField(fontRendererObj, contentLeft, contentTop, 170, CONTROLS);
        search.setMaxStringLength(64);
        search.setText(text);
        search.setFocused(focused && page != Page.HELP);
        search.setHint(I18n.format("neiextend.search"))
            .setOnCleared(this::rebuild);

        buttonList.clear();
        buttonList.add(WindowHeader.closeButton(ID_CLOSE, left + WIDTH, top));
        showAll = new FlatButton(ID_SHOW_ALL, contentRight - 128, contentTop, 128, CONTROLS, "");
        showAll.icon = Icons.SMALL_EYE;
        buttonList.add(showAll);
        rebuild();
    }

    /** Fills the list of the page again from what is searched and filtered. */
    private void rebuild() {
        String query = search == null ? ""
            : search.getText()
                .trim()
                .toLowerCase(Locale.ROOT);
        tabRows.clear();
        recipeRows.clear();
        if (page == Page.TABS) {
            Set<String> known = new HashSet<>();
            for (HandlerCatalog.Entry entry : catalog) {
                known.add(entry.id);
                addTab(new TabRow(entry).settled(), query);
            }
            for (HiddenStore.HiddenTab tab : HiddenStore.INSTANCE.tabs()) {
                if (!known.contains(tab.id)) {
                    addTab(new TabRow(tab).settled(), query);
                }
            }
        } else if (page == Page.RECIPES) {
            List<HiddenStore.HiddenRecipe> recipes = new ArrayList<>(HiddenStore.INSTANCE.recipes());
            // The last hidden first.
            Collections.reverse(recipes);
            for (HiddenStore.HiddenRecipe recipe : recipes) {
                RecipeRow row = new RecipeRow(recipe);
                if (query.isEmpty() || row.searchText.contains(query)) {
                    recipeRows.add(row);
                }
            }
        }
        clampScroll();
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
        return page == Page.RECIPES ? recipeRows.size() : page == Page.TABS ? tabRows.size() : 0;
    }

    private int contentHeight() {
        return rowCount() * rowHeight() + 4;
    }

    private int maxScroll() {
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

    /** The filter buttons: where each starts, and where the last ends. */
    private int[] filterEdges() {
        int[] edges = new int[Filter.values().length + 1];
        int x = contentLeft + 170 + 8;
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
        search.setFocused(next != Page.HELP);
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
            } else {
                HiddenStore.INSTANCE.showAllRecipes();
            }
            rebuild();
        }
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int button) {
        Page clickedPage = button == 0 ? pageAt(mouseX, mouseY) : null;
        if (clickedPage != null) {
            click();
            switchTo(clickedPage);
            return;
        }
        if (page != Page.HELP) {
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
        if (page == Page.TABS) {
            TabRow row = tabRows.get(index);
            if (row.hidden()) {
                HiddenStore.INSTANCE.showTab(row.id);
            } else if (row.entry != null) {
                HiddenStore.INSTANCE.hideTab(row.entry.handler);
            }
            click();
        } else {
            RecipeRow row = recipeRows.get(index);
            int y = rowY(index, scroll), by = y + (RECIPE_ROW - 16) / 2;
            if (Theme.inside(mouseX, mouseY, restoreX(), by, restoreX() + RESTORE_WIDTH, by + 16)) {
                HiddenStore.INSTANCE.showRecipe(row.recipe);
                click();
                rebuild();
            }
        }
    }

    /** Opens the tab of the row with all its recipes (a right click on it). */
    private void openAllRecipes(int index) {
        String id = page == Page.TABS ? tabRows.get(index).id : recipeRows.get(index).recipe.handlerId;
        HandlerCatalog.Entry entry = null;
        for (HandlerCatalog.Entry each : catalog) {
            if (each.id.equals(id)) {
                entry = each;
                break;
            }
        }
        click();
        if (entry == null || !AllRecipes.open(entry.handler)) {
            status = I18n.format(entry == null ? "neiextend.open.missing" : "neiextend.open.empty");
            statusUntil = System.currentTimeMillis() + STATUS_MS;
        }
    }

    @Override
    protected void mouseClickMove(int mouseX, int mouseY, int button, long held) {
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
        }
    }

    @Override
    public void handleMouseInput() {
        super.handleMouseInput();
        int wheel = Mouse.getEventDWheel();
        if (wheel != 0) {
            scroll -= Integer.signum(wheel) * 2 * rowHeight();
            clampScroll();
        }
    }

    @Override
    protected void keyTyped(char typedChar, int keyCode) {
        if (keyCode == Keyboard.KEY_ESCAPE) {
            close();
            return;
        }
        if (page != Page.HELP && search.isFocused()) {
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
            : HiddenStore.INSTANCE.hasHiddenRecipes();
        showAll.danger = confirming;
        showAll.displayString = I18n.format(
            confirming ? "neiextend.show_all.confirm"
                : page == Page.TABS ? "neiextend.show_all.tabs" : "neiextend.show_all.recipes");

        if (page == Page.HELP) {
            drawHelp();
        } else {
            search.drawTextBox();
            if (page == Page.TABS) {
                drawFilters(mouseX, mouseY);
            }
            drawList(mouseX, mouseY);
            drawNote();
        }
        super.drawScaled(mouseX, mouseY, partialTicks);
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
        for (int i = 0; i < rowCount(); i++) {
            int y = rowY(i, shown);
            if (y + height < listTop || y > listBottom) {
                continue;
            }
            boolean hovered = inList && Theme.inside(mouseX, mouseY, x0 + 1, y, x1 - 6, y + height);
            if (page == Page.TABS) {
                drawTabRow(tabRows.get(i), y, hovered, mouseX, mouseY);
            } else {
                drawRecipeRow(recipeRows.get(i), y, hovered, mouseX, mouseY);
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

    private void drawEmpty() {
        int centerX = (contentLeft + contentRight) / 2, centerY = (listTop + listBottom) / 2;
        boolean searching = !search.getText()
            .trim()
            .isEmpty() || page == Page.TABS && filter != Filter.ALL;
        String[] icon = searching ? Icons.SMALL_SEARCH : page == Page.RECIPES ? Icons.TARGET : Icons.TABS;
        Icons.draw(icon, centerX - Icons.width(icon) / 2, centerY - 22, Theme.TEXT_DISABLED);
        String title = I18n.format(
            searching ? "neiextend.empty.search"
                : "neiextend.empty." + page.name()
                    .toLowerCase(Locale.ROOT));
        Theme.centered(fontRendererObj, title, centerX, centerY - 6, Theme.TEXT_MUTED);
        if (!searching && page == Page.RECIPES) {
            int y = centerY + 8;
            for (Object line : fontRendererObj
                .listFormattedStringToWidth(I18n.format("neiextend.empty.recipes_text"), 300)) {
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
        int room = switchX() - 8 - tagsWidth - 8 - textX;
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
        HandlerInfo info = row.entry == null ? null : row.entry.info;
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
            String letters = row.name.length() > 2 ? row.name.substring(0, 2) : row.name;
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
        double at = row.knob.update(row.hidden() ? 0 : 1, 18);
        int x1 = x0 + SWITCH, y1 = y + 14;
        int track = Theme.blend(0xFF3A2326, Theme.ACCENT_DIM, at);
        Theme.fill(x0 + 1, y, x1 - 1, y1, track);
        Theme.fill(x0, y + 1, x1, y1 - 1, track);
        int edge = Theme.blend(Theme.DANGER, Theme.ACCENT, at);
        Theme.outline(x0, y, x1, y1, hovered ? edge : Theme.blend(edge, Theme.BORDER, 0.4));
        int knobX = x0 + 2 + (int) Math.round(at * (SWITCH - 14));
        Theme.fill(knobX, y + 2, knobX + 10, y1 - 2, Theme.blend(Theme.TEXT_MUTED, Theme.TEXT, at));
    }

    private void drawRecipeRow(RecipeRow row, int y, boolean hovered, int mouseX, int mouseY) {
        int x0 = contentLeft, x1 = contentRight - 6;
        double lit = row.light.update(hovered ? 1 : 0, 22);
        if (lit > 0.01) {
            Theme.fill(x0 + 1, y, x1, y + RECIPE_ROW, Theme.blend(0x00FFFFFF, Theme.ROW_HOVER, lit));
        }
        int boxX = x0 + 8, boxY = y + 4;
        Theme.roundBox(boxX, boxY, boxX + 22, boxY + 22, 0xFF0A1424, Theme.BOX_BORDER);
        if (row.result != null) {
            Draw.item(row.result, boxX + 3, boxY + 3, 16);
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
            Draw.item(ingredient, ix + 1, iy, 12);
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

        int textX = x0 + 38, room = ingredientsLeft - 16 - textX;
        fontRendererObj.drawString(
            Theme.ellipsize(fontRendererObj, row.name, room),
            textX,
            y + 6,
            row.result == null ? Theme.WARN : Theme.TEXT);
        String tab = I18n.format("neiextend.recipes.in_tab", row.recipe.tab);
        fontRendererObj.drawString(Theme.ellipsize(fontRendererObj, tab, room), textX, y + 17, Theme.TEXT_MUTED);

        int by = y + (RECIPE_ROW - 16) / 2;
        boolean over = Theme.inside(mouseX, mouseY, restoreX, by, restoreX + RESTORE_WIDTH, by + 16);
        double restoreLit = row.restoreLight.update(over ? 1 : 0, 22);
        if (restoreLit > 0.01) {
            Theme.glow(restoreX, by, restoreX + RESTORE_WIDTH, by + 16, Theme.ACCENT, restoreLit * 0.4);
        }
        Theme.roundBox(
            restoreX,
            by,
            restoreX + RESTORE_WIDTH,
            by + 16,
            Theme.blend(Theme.CONTROL, Theme.CONTROL_HOVER, restoreLit),
            Theme.blend(0xFF2A3D5C, Theme.ACCENT, restoreLit));
        int color = Theme.blend(Theme.TEXT_MUTED, Theme.TEXT, restoreLit);
        Icons.draw(Icons.SMALL_EYE, restoreX + 6, by + 5, color);
        fontRendererObj.drawString(
            Theme.ellipsize(fontRendererObj, I18n.format("neiextend.recipes.restore"), RESTORE_WIDTH - 20),
            restoreX + 16,
            by + 4,
            color);
        if (over) {
            hoverText = I18n.format("neiextend.recipes.restore_tip");
        } else if (hovered && hoverText == null && hoverStack == null) {
            hoverText = row.name + "\n" + I18n.format("neiextend.recipes.tip");
        }
    }

    /** At the bottom: how much the list shows, and where it is all kept. */
    private void drawNote() {
        int y = contentBottom - 9;
        if (status != null && System.currentTimeMillis() < statusUntil) {
            Icons.draw(Icons.SMALL_WARN, contentLeft + 1, y, Theme.WARN);
            fontRendererObj.drawString(status, contentLeft + 11, y, Theme.WARN);
            return;
        }
        String count = page == Page.TABS ? I18n.format("neiextend.note.tabs", tabRows.size(), catalog.size())
            : I18n.format("neiextend.note.recipes", recipeRows.size());
        fontRendererObj.drawString(count, contentLeft + 1, y, Theme.TEXT_DISABLED);
        String file = I18n.format("neiextend.note.file");
        int fileWidth = fontRendererObj.getStringWidth(file);
        Icons.draw(Icons.SMALL_FOLDER, contentRight - fileWidth - 12, y, Theme.TEXT_DISABLED);
        fontRendererObj.drawString(file, contentRight - fileWidth, y, Theme.TEXT_DISABLED);
    }

    /** How it works, in a few boxes. */
    private void drawHelp() {
        int x0 = contentLeft, x1 = contentRight, gap = 8;
        String[] sections = { "tab", "recipe", "restore", "about" };
        String[][] icons = { Icons.EYE_OFF, Icons.TARGET, Icons.SMALL_UNDO, Icons.SMALL_KEY };
        int y = contentTop;
        for (int i = 0; i < sections.length; i++) {
            String key = "neiextend.help." + sections[i];
            List<String> lines = new ArrayList<>();
            for (Object line : fontRendererObj
                .listFormattedStringToWidth(I18n.format(key + "_text", KeyHandler.keyName()), x1 - x0 - 24)) {
                lines.add((String) line);
            }
            int boxHeight = 24 + lines.size() * 10 + 4;
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
