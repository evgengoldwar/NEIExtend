package neiextend.client;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import net.minecraft.util.EnumChatFormatting;

import codechicken.nei.recipe.GuiCraftingRecipe;
import codechicken.nei.recipe.GuiRecipeTab;
import codechicken.nei.recipe.GuiUsageRecipe;
import codechicken.nei.recipe.HandlerInfo;
import codechicken.nei.recipe.IRecipeHandler;

/** Every recipe tab NEI knows, with what the screens show about it: its name, its mod and its icon. */
public final class HandlerCatalog {

    /** One tab: the same handler often shows both recipes (R) and uses (U). */
    public static final class Entry {

        public final String id;
        public final String name;
        public final String mod;
        public final IRecipeHandler handler;
        public final HandlerInfo info;
        public boolean crafting, usage;
        /** Name, mod and id in lower case, for the search. */
        public final String searchText;

        Entry(IRecipeHandler handler) {
            this.handler = handler;
            this.id = idOf(handler);
            this.name = nameOf(handler);
            this.mod = modOf(handler);
            this.info = infoOf(handler);
            this.searchText = (name + "\n" + mod + "\n" + id).toLowerCase(Locale.ROOT);
        }
    }

    private HandlerCatalog() {}

    /** Every tab, by mod and then by name. */
    public static List<Entry> all() {
        Map<String, Entry> entries = new LinkedHashMap<>();
        add(entries, GuiCraftingRecipe.craftinghandlers, true);
        add(entries, GuiCraftingRecipe.serialCraftingHandlers, true);
        add(entries, GuiUsageRecipe.usagehandlers, false);
        add(entries, GuiUsageRecipe.serialUsageHandlers, false);
        List<Entry> list = new ArrayList<>(entries.values());
        list.sort(
            Comparator.comparing((Entry e) -> e.mod.toLowerCase(Locale.ROOT))
                .thenComparing(e -> e.name.toLowerCase(Locale.ROOT)));
        return list;
    }

    private static void add(Map<String, Entry> entries, List<? extends IRecipeHandler> handlers, boolean crafting) {
        List<IRecipeHandler> copy;
        synchronized (handlers) {
            copy = new ArrayList<>(handlers);
        }
        for (IRecipeHandler handler : copy) {
            String id = idOf(handler);
            if (id == null) {
                continue;
            }
            Entry entry = entries.get(id);
            if (entry == null) {
                entry = new Entry(handler);
                entries.put(id, entry);
            }
            if (crafting) {
                entry.crafting = true;
            } else {
                entry.usage = true;
            }
        }
    }

    /** The id a tab is hidden by: the handler's own id, the one NEI's hiddenhandlers.cfg also uses. */
    public static String idOf(IRecipeHandler handler) {
        if (handler == null) {
            return null;
        }
        try {
            String id = handler.getHandlerId();
            return id != null ? id
                : handler.getClass()
                    .getName();
        } catch (Throwable t) {
            return handler.getClass()
                .getName();
        }
    }

    public static String nameOf(IRecipeHandler handler) {
        String name = null;
        try {
            name = handler.getRecipeTabName();
            if (name == null || name.trim()
                .isEmpty()) {
                name = handler.getRecipeName();
            }
        } catch (Throwable ignored) {
            // A handler that can't tell its name without recipes.
        }
        if (name == null || name.trim()
            .isEmpty()) {
            name = handler.getClass()
                .getSimpleName();
        }
        String plain = EnumChatFormatting.getTextWithoutFormattingCodes(name);
        return (plain == null ? name : plain).trim();
    }

    public static HandlerInfo infoOf(IRecipeHandler handler) {
        try {
            return GuiRecipeTab.getHandlerInfo(handler);
        } catch (Throwable t) {
            return null;
        }
    }

    /** The mod of the tab as NEI knows it, or else the first part of the handler's package. */
    public static String modOf(IRecipeHandler handler) {
        HandlerInfo info = infoOf(handler);
        if (info != null && info != GuiRecipeTab.DEFAULT_HANDLER_INFO
            && info.getModName() != null
            && !"Unknown".equals(info.getModName())) {
            return info.getModName();
        }
        String className = handler.getClass()
            .getName();
        if (className.startsWith("codechicken.nei.")) {
            return "NotEnoughItems";
        }
        int dot = className.indexOf('.');
        return dot > 0 ? className.substring(0, dot) : "?";
    }
}
