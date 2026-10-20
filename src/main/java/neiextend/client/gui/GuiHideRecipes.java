package neiextend.client.gui;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import net.minecraft.client.audio.PositionedSoundRecord;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.resources.I18n;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ResourceLocation;

import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;
import org.lwjgl.opengl.GL11;

import codechicken.nei.PositionedStack;
import codechicken.nei.SearchField;
import codechicken.nei.api.IRecipeFilter;
import codechicken.nei.recipe.GuiRecipe;
import codechicken.nei.recipe.IRecipeHandler;
import codechicken.nei.recipe.StackInfo;
import codechicken.nei.recipe.TemplateRecipeHandler;
import neiextend.NEIExtend;
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
 * All recipes of the tab shown on NEI's recipe screen at once, as cards in a grid over the whole screen, to hide them
 * quickly: a click on a card hides it, and holding the button down and dragging marks the block of cards between the
 * card pressed and the one under the mouse (from the 1st to the 3rd card of the 1st to the 3rd row: nine), hidden all
 * at once when the button is let go. A right click while dragging lets go of the block without hiding it. A search
 * over the cards' items narrows them down; "Undo" (or Ctrl+Z) shows the last ones hidden again. Opened by a right click
 * on the crosshair beside the recipe screen; closing it goes back there, looked up again with the changes.
 */
public class GuiHideRecipes extends ScaledScreen {

    /** Room around the window, so it is seen as one over the game. */
    private static final int MARGIN = 10;
    private static final int CONTROLS = 18, NOTE = 14, GAP = 4;
    /** A card: its height, the least width it is given (they stretch to fill a row) and its items. */
    private static final int CARD_HEIGHT = 47, CARD_MIN_WIDTH = 88, INGREDIENT = 13, MAX_INGREDIENTS = 9;
    private static final int SEARCH_WIDTH = 220, UNDO_WIDTH = 86;
    /** How close to the top or bottom of the grid the mouse makes it scroll while marking cards. */
    private static final int EDGE = 14;
    private static final long STATUS_MS = 3000;
    private static final int ID_CLOSE = 900, ID_UNDO = 1;

    /** One recipe of the tab. Its items are read the first time it is drawn or searched, not all at once. */
    private static final class Card {

        /** The recipe's index in the handler. */
        final int recipe;
        final Smooth light = new Smooth(0);
        boolean hidden;
        private boolean loaded;
        ItemStack result;
        /** The ingredients, each kind once with how many of it the recipe takes. */
        List<ItemStack> ingredients = Collections.emptyList();
        String name;
        private String searchText;

        Card(int recipe) {
            this.recipe = recipe;
        }

        void load(IRecipeHandler handler) {
            if (loaded) {
                return;
            }
            loaded = true;
            try {
                PositionedStack stack = handler.getResultStack(recipe);
                result = stack == null || stack.item == null ? null : stack.item.copy();
            } catch (Throwable t) {
                result = null;
            }
            try {
                List<ItemStack> stacks = new ArrayList<>();
                List<PositionedStack> positioned = handler.getIngredientStacks(recipe);
                if (positioned != null) {
                    for (PositionedStack each : positioned) {
                        if (each != null) {
                            stacks.add(each.item);
                        }
                    }
                }
                ingredients = merge(stacks);
            } catch (Throwable t) {
                ingredients = Collections.emptyList();
            }
            String shown;
            try {
                shown = result == null ? null : result.getDisplayName();
            } catch (Throwable t) {
                shown = null;
            }
            name = shown != null ? shown : I18n.format("neiextend.recipes.unknown");
        }

        String searchText(IRecipeHandler handler) {
            if (searchText == null) {
                load(handler);
                StringBuilder text = new StringBuilder(name);
                for (ItemStack ingredient : ingredients) {
                    try {
                        text.append('\n')
                            .append(ingredient.getDisplayName());
                    } catch (Throwable ignored) {
                        // An item that can't tell its name: not searchable by it.
                    }
                }
                searchText = text.toString()
                    .toLowerCase(Locale.ROOT);
            }
            return searchText;
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

    /** Recipes hidden at once, to be shown again by "Undo". */
    private static final class Hiding {

        final List<HiddenStore.HiddenRecipe> recipes;
        final List<Card> cards;

        Hiding(List<HiddenStore.HiddenRecipe> recipes, List<Card> cards) {
            this.recipes = recipes;
            this.cards = cards;
        }
    }

    /** Which items of a recipe the search looks at: all of them, only what goes in, or only what comes out. */
    private enum Scope {

        ALL("neiextend.grid.scope.all", ""),
        INPUTS("neiextend.grid.scope.inputs", "<"),
        OUTPUTS("neiextend.grid.scope.outputs", ">");

        final String key;
        /** NEI's mark for the items a search word looks at. */
        final String mark;

        Scope(String key, String mark) {
            this.key = key;
            this.mark = mark;
        }
    }

    /** Kept while the game runs, for the next time the screen opens. */
    private static Scope scope = Scope.ALL;

    private final GuiRecipe<?> parent;
    private final IRecipeHandler handler;
    private final String tabName;
    private final int revisionAtOpen = HiddenStore.INSTANCE.revision();
    /** Every recipe of the tab that NEI showed when the screen opened, in its order. */
    private final List<Card> cards = new ArrayList<>();
    /** The cards in the grid: not hidden, and matching what is searched. */
    private final List<Card> shown = new ArrayList<>();
    private final Deque<Hiding> undo = new ArrayDeque<>();

    private int left, top, right, bottom;
    private int contentLeft, contentRight, contentTop, contentBottom;
    private int gridLeft, gridRight, gridTop, gridBottom;
    private int columns = 1, cardWidth = CARD_MIN_WIDTH;
    private FlatTextField search;
    private int searchWidth;
    private final Smooth[] scopeLight = { new Smooth(0), new Smooth(0), new Smooth(0) };
    private FlatButton undoButton;

    private int scroll;
    private int drawnScroll;
    private final Smooth shownScroll = new Smooth(0);
    private final ScrollbarDrag scrollbarDrag = new ScrollbarDrag();

    /** Marking cards: the button is held down since a card was pressed. */
    private boolean marking;
    /** The cell pressed, and the one under the mouse now: the block between them is marked. */
    private int anchorRow, anchorColumn, markRow, markColumn;

    private String status;
    private int statusColor;
    private long statusUntil;
    private String hoverText;
    private ItemStack hoverStack;

    public GuiHideRecipes(GuiRecipe<?> parent) {
        this.parent = parent;
        this.handler = parent.getHandler();
        this.tabName = HandlerCatalog.nameOf(handler);
        IRecipeFilter filter = handler instanceof TemplateRecipeHandler ? GuiRecipe.getRecipeListFilter() : null;
        int count;
        try {
            count = handler.numRecipes();
        } catch (Throwable t) {
            count = 0;
        }
        for (int i = 0; i < count; i++) {
            try {
                if (filter != null && !filter.matches(handler, i)) {
                    continue;
                }
            } catch (Throwable t) {
                // A filter that fails on it: shown, as NEI would.
            }
            cards.add(new Card(i));
        }
    }

    // Layout.

    @Override
    public void initGui() {
        Keyboard.enableRepeatEvents(true);
        left = MARGIN;
        top = MARGIN;
        right = width - MARGIN;
        bottom = height - MARGIN;
        contentLeft = left + 12;
        contentRight = right - 12;
        contentTop = top + WindowHeader.HEIGHT + 10;
        contentBottom = bottom - 10;
        gridLeft = contentLeft;
        gridRight = contentRight - 8;
        gridTop = contentTop + CONTROLS + 8;
        gridBottom = contentBottom - NOTE;
        columns = Math.max(1, (gridRight - gridLeft + GAP) / (CARD_MIN_WIDTH + GAP));
        cardWidth = (gridRight - gridLeft + GAP) / columns - GAP;

        String text = search == null ? "" : search.getText();
        searchWidth = Math
            .max(90, Math.min(SEARCH_WIDTH, contentRight - UNDO_WIDTH - 8 - scopesWidth() - 8 - contentLeft));
        search = new FlatTextField(fontRendererObj, contentLeft, contentTop, searchWidth, CONTROLS);
        search.setMaxStringLength(64);
        search.setText(text);
        search.setFocused(true);
        search.setHint(I18n.format("neiextend.grid.search"))
            .setOnCleared(this::rebuild);

        buttonList.clear();
        buttonList.add(WindowHeader.closeButton(ID_CLOSE, right, top));
        undoButton = new FlatButton(ID_UNDO, contentRight - UNDO_WIDTH, contentTop, UNDO_WIDTH, CONTROLS, "");
        undoButton.icon = Icons.SMALL_UNDO;
        buttonList.add(undoButton);
        rebuild();
    }

    /**
     * Fills the grid again from the cards not hidden that match what is searched. The search is NEI's own, as over
     * its recipe screen: words in any order ("small gear" finds Small Gold Gear and the other materials), @mod,
     * $ore dictionary, | for either, - to leave out, and &lt; or &gt; before a word for what goes in or comes out.
     */
    private void rebuild() {
        String text = search == null ? ""
            : search.getText()
                .trim();
        String lower = text.toLowerCase(Locale.ROOT);
        IRecipeFilter filter = text.isEmpty() ? null : neiFilter(text);
        shown.clear();
        for (Card card : cards) {
            if (!card.hidden && (text.isEmpty() || matches(card, filter, lower))) {
                shown.add(card);
            }
        }
        marking = false;
        clampScroll();
    }

    /** NEI's filter for what is searched, over the items the scope looks at; null when NEI can't make one. */
    private static IRecipeFilter neiFilter(String text) {
        StringBuilder query = new StringBuilder();
        if (scope != Scope.ALL) {
            // Each alternative gets the mark: NEI reads it for the words that follow, up to the next |.
            for (String part : text.split("\\|")) {
                if (query.length() > 0) {
                    query.append(" | ");
                }
                query.append(scope.mark)
                    .append(part.trim());
            }
        } else {
            query.append(text);
        }
        try {
            return SearchField.searchParser.getRecipeFilter(query.toString());
        } catch (Throwable t) {
            NEIExtend.LOG.warn("NEI could not read the search \"{}\"; searching by name instead", text, t);
            return null;
        }
    }

    private boolean matches(Card card, IRecipeFilter filter, String lower) {
        if (filter != null) {
            try {
                return filter.matches(handler, card.recipe);
            } catch (Throwable t) {
                // A recipe NEI's filter fails on: looked at by its items' names below.
            }
        }
        return card.searchText(handler)
            .contains(lower);
    }

    // The scope of the search: segments joined into one box, right of the search field.

    private String scopeLabel(Scope each) {
        return I18n.format(each.key);
    }

    private int scopesWidth() {
        int width = 0;
        for (Scope each : Scope.values()) {
            width += fontRendererObj.getStringWidth(scopeLabel(each)) + 16;
        }
        return width;
    }

    private int[] scopeEdges() {
        int[] edges = new int[Scope.values().length + 1];
        int x = contentLeft + searchWidth + 8;
        for (Scope each : Scope.values()) {
            edges[each.ordinal()] = x;
            x += fontRendererObj.getStringWidth(scopeLabel(each)) + 16;
        }
        edges[edges.length - 1] = x;
        return edges;
    }

    private Scope scopeAt(int mouseX, int mouseY) {
        if (mouseY < contentTop || mouseY >= contentTop + CONTROLS) {
            return null;
        }
        int[] edges = scopeEdges();
        for (Scope each : Scope.values()) {
            if (mouseX >= edges[each.ordinal()] && mouseX < edges[each.ordinal() + 1] - 2) {
                return each;
            }
        }
        return null;
    }

    private void drawScopes(int mouseX, int mouseY) {
        int[] edges = scopeEdges();
        int x0 = edges[0], x1 = edges[edges.length - 1] - 2, y0 = contentTop, y1 = contentTop + CONTROLS;
        Theme.roundBox(x0, y0, x1, y1, Theme.CONTROL, 0xFF2A3D5C);
        Scope over = scopeAt(mouseX, mouseY);
        for (Scope each : Scope.values()) {
            int sx0 = edges[each.ordinal()], sx1 = edges[each.ordinal() + 1] - 2;
            double lit = scopeLight[each.ordinal()].update(each == over ? 1 : 0, 22);
            if (each == scope) {
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
            int color = each == scope ? Theme.TEXT : Theme.blend(Theme.TEXT_MUTED, Theme.TEXT, lit);
            Theme.centered(fontRendererObj, scopeLabel(each), (sx0 + sx1) / 2, y0 + (CONTROLS - 8) / 2, color);
        }
    }

    private int rows() {
        return (shown.size() + columns - 1) / columns;
    }

    private int contentHeight() {
        return Math.max(0, rows() * (CARD_HEIGHT + GAP) - GAP);
    }

    private int maxScroll() {
        return Math.max(0, contentHeight() - (gridBottom - gridTop));
    }

    private void clampScroll() {
        scroll = Math.max(0, Math.min(maxScroll(), scroll));
    }

    private int cardX(int column) {
        return gridLeft + column * (cardWidth + GAP);
    }

    private int cardY(int row) {
        return gridTop + row * (CARD_HEIGHT + GAP) - drawnScroll;
    }

    /** The index in the grid of the card under the mouse, or -1 (between cards, or off the grid). */
    private int cardAt(int mouseX, int mouseY) {
        if (!Theme.inside(mouseX, mouseY, gridLeft, gridTop, gridRight, gridBottom)) {
            return -1;
        }
        int x = mouseX - gridLeft, y = mouseY - gridTop + drawnScroll;
        int column = x / (cardWidth + GAP), row = y / (CARD_HEIGHT + GAP);
        if (column >= columns || x % (cardWidth + GAP) >= cardWidth || y % (CARD_HEIGHT + GAP) >= CARD_HEIGHT) {
            return -1;
        }
        int index = row * columns + column;
        return index < shown.size() ? index : -1;
    }

    /** The row and column under the mouse while marking, kept inside the grid wherever the mouse goes. */
    private void updateMark(int mouseX, int mouseY) {
        int x = mouseX - gridLeft, y = mouseY - gridTop + drawnScroll;
        markColumn = Math.max(0, Math.min(columns - 1, Math.floorDiv(x, cardWidth + GAP)));
        markRow = Math.max(0, Math.min(rows() - 1, Math.floorDiv(y, CARD_HEIGHT + GAP)));
    }

    private boolean isMarked(int index) {
        if (!marking) {
            return false;
        }
        int row = index / columns, column = index % columns;
        return row >= Math.min(anchorRow, markRow) && row <= Math.max(anchorRow, markRow)
            && column >= Math.min(anchorColumn, markColumn)
            && column <= Math.max(anchorColumn, markColumn);
    }

    /** The cards marked now, in the grid's order. */
    private List<Card> marked() {
        List<Card> picked = new ArrayList<>();
        int rowFrom = Math.min(anchorRow, markRow), rowTo = Math.max(anchorRow, markRow);
        int columnFrom = Math.min(anchorColumn, markColumn), columnTo = Math.max(anchorColumn, markColumn);
        for (int row = rowFrom; row <= rowTo; row++) {
            for (int column = columnFrom; column <= columnTo; column++) {
                int index = row * columns + column;
                if (index < shown.size()) {
                    picked.add(shown.get(index));
                }
            }
        }
        return picked;
    }

    // Input.

    @Override
    protected void actionPerformed(GuiButton button) {
        if (button.id == ID_CLOSE) {
            close();
        } else if (button.id == ID_UNDO) {
            undoLast();
        }
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int button) {
        if (marking) {
            if (button == 1) {
                // Lets go of the block without hiding it.
                marking = false;
                click();
            }
            return;
        }
        super.mouseClicked(mouseX, mouseY, button);
        search.mouseClicked(mouseX, mouseY, button);
        if (button != 0) {
            return;
        }
        Scope picked = scopeAt(mouseX, mouseY);
        if (picked != null) {
            if (picked != scope) {
                scope = picked;
                click();
                rebuild();
            }
            return;
        }
        int dragged = scrollbarDrag.press(
            mouseX,
            mouseY,
            contentRight - 3,
            gridTop,
            gridBottom,
            gridBottom - gridTop,
            contentHeight(),
            drawnScroll,
            maxScroll());
        if (dragged >= 0) {
            scroll = dragged;
            return;
        }
        int index = cardAt(mouseX, mouseY);
        if (index >= 0) {
            marking = true;
            anchorRow = markRow = index / columns;
            anchorColumn = markColumn = index % columns;
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
        if (state == 0) {
            scrollbarDrag.release();
            if (marking) {
                updateMark(mouseX, mouseY);
                List<Card> picked = marked();
                marking = false;
                hide(picked);
            }
        }
    }

    @Override
    public void handleMouseInput() {
        super.handleMouseInput();
        int wheel = Mouse.getEventDWheel();
        if (wheel != 0) {
            scroll -= Integer.signum(wheel) * (CARD_HEIGHT + GAP);
            clampScroll();
        }
    }

    @Override
    protected void keyTyped(char typedChar, int keyCode) {
        if (keyCode == Keyboard.KEY_ESCAPE) {
            if (marking) {
                marking = false;
            } else {
                close();
            }
            return;
        }
        if (keyCode == Keyboard.KEY_Z && isCtrlKeyDown()) {
            undoLast();
            return;
        }
        if (search.isFocused()) {
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
        if (marking) {
            // Held near the top or bottom of the grid, it scrolls on, faster the closer the mouse is to the edge.
            int mouseY = mouseY();
            int step = 0;
            if (mouseY < gridTop + EDGE) {
                step = -Math.max(4, (gridTop + EDGE - mouseY) * 2);
            } else if (mouseY > gridBottom - EDGE) {
                step = Math.max(4, (mouseY - gridBottom + EDGE) * 2);
            }
            if (step != 0) {
                scroll += Math.max(-CARD_HEIGHT, Math.min(CARD_HEIGHT, step));
                clampScroll();
            }
        }
    }

    // Hiding.

    private void hide(List<Card> picked) {
        if (picked.isEmpty()) {
            return;
        }
        Set<Integer> indices = new LinkedHashSet<>();
        for (Card card : picked) {
            indices.add(card.recipe);
        }
        List<HiddenStore.HiddenRecipe> hidden = HiddenStore.INSTANCE.hideRecipes(handler, indices);
        List<Card> gone = new ArrayList<>();
        for (Card card : picked) {
            if (HiddenStore.INSTANCE.isRecipeHidden(handler, card.recipe)) {
                card.hidden = true;
                gone.add(card);
            }
        }
        if (gone.isEmpty()) {
            return;
        }
        click();
        if (!hidden.isEmpty()) {
            undo.push(new Hiding(hidden, gone));
        }
        NEIExtend.LOG.info("Hid {} recipes of {} from the grid", gone.size(), HandlerCatalog.idOf(handler));
        showStatus(I18n.format("neiextend.grid.hidden", gone.size()), Theme.SUCCESS);
        rebuild();
    }

    private void undoLast() {
        Hiding last = undo.poll();
        if (last == null) {
            return;
        }
        HiddenStore.INSTANCE.showRecipes(last.recipes);
        for (Card card : last.cards) {
            card.hidden = false;
        }
        click();
        showStatus(I18n.format("neiextend.grid.restored", last.cards.size()), Theme.ACCENT);
        rebuild();
    }

    private void showStatus(String text, int color) {
        status = text;
        statusColor = color;
        statusUntil = System.currentTimeMillis() + STATUS_MS;
    }

    private void click() {
        mc.getSoundHandler()
            .playSound(PositionedSoundRecord.func_147674_a(new ResourceLocation("gui.button.press"), 1.0F));
    }

    /** Back to the recipe screen, looked up again when recipes were hidden or shown. */
    private void close() {
        mc.displayGuiScreen(parent);
        if (HiddenStore.INSTANCE.revision() != revisionAtOpen) {
            RecipeScreenHooks.INSTANCE.refresh(parent);
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
        drawnScroll = (int) Math.round(shownScroll.update(scroll, 18));
        if (marking) {
            updateMark(mouseX, mouseY);
        }
        Theme.fill(0, 0, width, height, Theme.SCREEN_DIM);
        Theme.panel(left, top, right, bottom);
        Theme.fill(left + 1, top + WindowHeader.HEIGHT, right - 1, bottom - 1, Theme.CONTENT);
        WindowHeader.draw(
            fontRendererObj,
            left,
            top,
            right,
            right - WindowHeader.CLOSE_ROOM,
            Icons.TARGET,
            I18n.format("neiextend.grid.title", tabName),
            I18n.format("neiextend.grid.subtitle"),
            Theme.TEXT_MUTED,
            I18n.format("neiextend.grid.count", shown.size()));

        undoButton.enabled = !undo.isEmpty();
        undoButton.displayString = undo.isEmpty() ? I18n.format("neiextend.toast.undo")
            : I18n.format("neiextend.grid.undo", undo.peek().cards.size());
        search.drawTextBox();
        drawScopes(mouseX, mouseY);
        drawGrid(mouseX, mouseY);
        drawNote();
        super.drawScaled(mouseX, mouseY, partialTicks);

        if (marking) {
            int count = marked().size();
            List<String> lines = new ArrayList<>();
            List<Integer> colors = new ArrayList<>();
            lines.add(I18n.format("neiextend.grid.mark", count));
            colors.add(Theme.DANGER);
            lines.add(I18n.format("neiextend.grid.mark_text"));
            colors.add(Theme.TEXT_MUTED);
            Draw.tooltip(fontRendererObj, lines, colors, mouseX, mouseY, width, height);
        } else if (hoverStack != null) {
            List<String> lines = new ArrayList<>();
            List<Integer> colors = new ArrayList<>();
            lines.add(hoverStack.getDisplayName());
            colors.add(Theme.TEXT);
            lines.add(I18n.format("neiextend.grid.card_tip"));
            colors.add(Theme.TEXT_MUTED);
            Draw.tooltip(fontRendererObj, lines, colors, mouseX, mouseY, width, height);
        } else if (hoverText != null) {
            List<String> lines = new ArrayList<>();
            List<Integer> colors = new ArrayList<>();
            lines.add(hoverText);
            colors.add(Theme.TEXT);
            Draw.tooltip(fontRendererObj, lines, colors, mouseX, mouseY, width, height);
        }
    }

    private void drawGrid(int mouseX, int mouseY) {
        Theme.card(gridLeft - 4, gridTop - 4, contentRight + 1, gridBottom + 4);
        if (shown.isEmpty()) {
            String text = I18n.format(cards.isEmpty() ? "neiextend.grid.empty" : "neiextend.grid.empty_search");
            Theme.centered(
                fontRendererObj,
                text,
                (gridLeft + gridRight) / 2,
                (gridTop + gridBottom) / 2 - 4,
                Theme.TEXT_MUTED);
            return;
        }
        int hovered = marking ? -1 : cardAt(mouseX, mouseY);
        int firstRow = Math.max(0, drawnScroll / (CARD_HEIGHT + GAP));
        int lastRow = Math.min(rows() - 1, (drawnScroll + gridBottom - gridTop) / (CARD_HEIGHT + GAP));
        Theme.clip(gridLeft - 3, gridTop, contentRight, gridBottom);
        for (int row = firstRow; row <= lastRow; row++) {
            for (int column = 0; column < columns; column++) {
                int index = row * columns + column;
                if (index >= shown.size()) {
                    break;
                }
                Card card = shown.get(index);
                drawCard(card, cardX(column), cardY(row), index == hovered, isMarked(index), mouseX, mouseY);
            }
        }
        if (marking) {
            // The frame of the whole block, over the cards.
            int x0 = cardX(Math.min(anchorColumn, markColumn)) - 2;
            int x1 = cardX(Math.max(anchorColumn, markColumn)) + cardWidth + 2;
            int y0 = cardY(Math.min(anchorRow, markRow)) - 2;
            int y1 = cardY(Math.max(anchorRow, markRow)) + CARD_HEIGHT + 2;
            Theme.outline(x0, y0, x1, y1, Theme.DANGER);
        }
        Theme.unclip();
        if (maxScroll() > 0) {
            boolean lit = Theme.inside(mouseX, mouseY, contentRight - 6, gridTop, contentRight + 2, gridBottom);
            Theme.scrollbar(
                contentRight - 3,
                gridTop,
                gridBottom,
                gridBottom - gridTop,
                contentHeight(),
                drawnScroll / (double) maxScroll(),
                lit);
        }
    }

    private void drawCard(Card card, int x, int y, boolean hovered, boolean marked, int mouseX, int mouseY) {
        card.load(handler);
        double lit = card.light.update(hovered ? 1 : 0, 22);
        int x1 = x + cardWidth, y1 = y + CARD_HEIGHT;
        int background = marked ? 0xFF3A1A20 : Theme.blend(Theme.BOX, Theme.CONTROL_HOVER, lit);
        int border = marked ? Theme.DANGER : Theme.blend(Theme.BOX_BORDER, Theme.DANGER, lit * 0.8);
        Theme.roundBox(x, y, x1, y1, background, border);
        Theme.fill(x + 2, y + 1, x1 - 2, y + 2, Theme.SHEEN);

        // The ingredients, three by three.
        int shownIngredients = Math.min(card.ingredients.size(), MAX_INGREDIENTS);
        boolean more = card.ingredients.size() > MAX_INGREDIENTS;
        if (more) {
            shownIngredients = MAX_INGREDIENTS - 1;
        }
        int gridX = x + 4, gridY = y + 4;
        for (int i = 0; i < shownIngredients; i++) {
            ItemStack ingredient = card.ingredients.get(i);
            int ix = gridX + i % 3 * INGREDIENT, iy = gridY + i / 3 * INGREDIENT;
            drawStack(ingredient, ix, iy, 12, ingredient.stackSize);
            if (!marking && Theme.inside(mouseX, mouseY, ix, iy, ix + INGREDIENT, iy + INGREDIENT)) {
                hoverStack = ingredient;
            }
        }
        if (more) {
            String rest = "+" + (card.ingredients.size() - shownIngredients);
            int ix = gridX + 2 * INGREDIENT, iy = gridY + 2 * INGREDIENT;
            Theme.centered(fontRendererObj, rest, ix + 6, iy + 2, Theme.TEXT_MUTED);
        }

        // The arrow, and what it makes on the right.
        int arrowX = gridX + 3 * INGREDIENT + 3;
        Icons.draw(Icons.SMALL_ARROW, arrowX, y + (CARD_HEIGHT - 7) / 2, Theme.TEXT_DISABLED);
        int boxLeft = arrowX + 10, boxSize = 22;
        int boxX = boxLeft + Math.max(0, (x1 - 4 - boxLeft - boxSize) / 2), boxY = y + (CARD_HEIGHT - boxSize) / 2;
        Theme.roundBox(boxX, boxY, boxX + boxSize, boxY + boxSize, 0xFF0A1424, Theme.BOX_BORDER);
        if (card.result != null) {
            drawStack(card.result, boxX + 3, boxY + 3, 16, card.result.stackSize);
            if (!marking && Theme.inside(mouseX, mouseY, boxX, boxY, boxX + boxSize, boxY + boxSize)) {
                hoverStack = card.result;
            }
        } else {
            Theme.centered(fontRendererObj, "?", boxX + boxSize / 2, boxY + 7, Theme.WARN);
        }

        if (marked) {
            Theme.fill(x + 1, y + 1, x1 - 1, y1 - 1, 0x50E5534B);
            Icons.draw(Icons.SMALL_EYE_OFF, x1 - 10, y + 3, Theme.TEXT);
        } else if (hovered) {
            Theme.fill(x + 1, y + 1, x1 - 1, y1 - 1, (int) Math.round(0x30 * lit) << 24 | 0xE5534B);
            if (hoverStack == null) {
                hoverText = card.name;
            }
        }
    }

    /** An item with how many of it there are in its corner, as in the inventory. */
    private void drawStack(ItemStack stack, int x, int y, int size, int count) {
        Draw.item(stack, x, y, size);
        GL11.glDisable(GL11.GL_DEPTH_TEST);
        if (count > 1) {
            String text = String.valueOf(count);
            GL11.glPushMatrix();
            if (size < 16) {
                // Small items get small numbers, so they still show the item.
                GL11.glTranslatef(x + size + 1, y + size - 4, 0f);
                GL11.glScalef(0.6f, 0.6f, 1f);
                fontRendererObj.drawStringWithShadow(text, -fontRendererObj.getStringWidth(text), -1, Theme.TEXT);
            } else {
                int textX = x + size + 1 - fontRendererObj.getStringWidth(text);
                fontRendererObj.drawStringWithShadow(text, textX, y + size - 7, Theme.TEXT);
            }
            GL11.glPopMatrix();
        }
    }

    /** At the bottom: what was just done, or how many recipes there are and how to hide them. */
    private void drawNote() {
        int y = contentBottom - 9;
        if (status != null && System.currentTimeMillis() < statusUntil) {
            Icons.draw(Icons.SMALL_CHECK, contentLeft + 1, y, statusColor);
            fontRendererObj.drawString(status, contentLeft + 11, y, statusColor);
            return;
        }
        String count = I18n.format("neiextend.grid.note", shown.size(), cards.size());
        fontRendererObj.drawString(count, contentLeft + 1, y, Theme.TEXT_DISABLED);
        String hint = I18n.format("neiextend.grid.hint");
        int room = contentRight - contentLeft - fontRendererObj.getStringWidth(count) - 24;
        hint = Theme.ellipsize(fontRendererObj, hint, room);
        int hintWidth = fontRendererObj.getStringWidth(hint);
        Icons.draw(Icons.SMALL_MOUSE, contentRight - hintWidth - 12, y, Theme.TEXT_DISABLED);
        fontRendererObj.drawString(hint, contentRight - hintWidth, y, Theme.TEXT_DISABLED);
    }
}
