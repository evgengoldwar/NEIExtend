package neiextend.client;

import java.io.File;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.item.ItemStack;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import codechicken.nei.recipe.IRecipeHandler;
import codechicken.nei.recipe.Recipe.RecipeId;
import codechicken.nei.recipe.StackInfo;
import neiextend.NEIExtend;

/**
 * What the player chose to hide, kept in {@code config/neiextend.json}: whole recipe tabs (by the id of their
 * handler) and single recipes (by their handler and NEI's id of the recipe: its ingredients and result). It also keeps
 * the tabs the player put first, in their order.
 * <p>
 * NEI looks recipes up on several threads at once, so the lists are never changed in place: a change makes new ones
 * and swaps them in, and the lookups read whichever ones are current.
 */
public final class HiddenStore {

    public static final HiddenStore INSTANCE = new HiddenStore();

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting()
        .disableHtmlEscaping()
        .create();

    /** A tab hidden or put first, with what it was called then: shown even when its mod is gone. */
    public static final class SavedTab {

        public final String id;
        public final String name;
        public final String mod;
        public final long time;

        SavedTab(String id, String name, String mod, long time) {
            this.id = id;
            this.name = name;
            this.mod = mod;
            this.time = time;
        }
    }

    /** A hidden recipe of one tab. */
    public static final class HiddenRecipe {

        public final String handlerId;
        /** The name of its tab when it was hidden. */
        public final String tab;
        public final long time;
        /** NEI's id of the recipe, null when it could not be read back (its items are gone). */
        public final RecipeId recipeId;
        /** The recipe as stored, written back as it is when it can't be read. */
        final JsonObject raw;

        HiddenRecipe(String handlerId, String tab, long time, RecipeId recipeId, JsonObject raw) {
            this.handlerId = handlerId;
            this.tab = tab;
            this.time = time;
            this.recipeId = recipeId;
            this.raw = raw;
        }

        public ItemStack result() {
            return recipeId == null ? null : recipeId.getResult();
        }

        public List<ItemStack> ingredients() {
            return recipeId == null ? Collections.emptyList() : recipeId.getIngredients();
        }

        boolean matches(RecipeId other) {
            if (recipeId == null || !recipeId.equals(other)) {
                return false;
            }
            // NEI's id leaves the result out for shapeless recipes: two recipes from the same items can make
            // different things, and only the one picked is hidden.
            ItemStack mine = recipeId.getResult(), theirs = other.getResult();
            return mine == null || theirs == null || StackInfo.equalItemAndNBT(mine, theirs, true);
        }
    }

    private File file;

    private volatile Map<String, SavedTab> tabs = Collections.emptyMap();
    /** The tabs put first, in their order, and where each one is in it. */
    private volatile List<SavedTab> pinned = Collections.emptyList();
    private volatile Map<String, Integer> pinPositions = Collections.emptyMap();
    private volatile List<HiddenRecipe> recipes = Collections.emptyList();
    private volatile Map<String, List<HiddenRecipe>> recipesByHandler = Collections.emptyMap();
    /** Counts the changes, so a screen can tell its recipes are out of date. */
    private volatile int revision;

    private HiddenStore() {}

    public void setFile(File file) {
        this.file = file;
    }

    public File getFile() {
        return file;
    }

    public int revision() {
        return revision;
    }

    // Reading, from any thread.

    public boolean isTabHidden(String handlerId) {
        return handlerId != null && tabs.containsKey(handlerId);
    }

    public boolean isTabHidden(IRecipeHandler handler) {
        return isTabHidden(HandlerCatalog.idOf(handler));
    }

    public List<SavedTab> tabs() {
        return new ArrayList<>(tabs.values());
    }

    public int tabCount() {
        return tabs.size();
    }

    /** Where the tab is among the ones put first, or -1. */
    public int pinPosition(String handlerId) {
        Integer position = handlerId == null ? null : pinPositions.get(handlerId);
        return position == null ? -1 : position;
    }

    public int pinPosition(IRecipeHandler handler) {
        return pinPosition(HandlerCatalog.idOf(handler));
    }

    public List<SavedTab> pinned() {
        return pinned;
    }

    public List<HiddenRecipe> recipes() {
        return recipes;
    }

    public boolean hasHiddenRecipes() {
        return !recipes.isEmpty();
    }

    public boolean hasHiddenRecipes(IRecipeHandler handler) {
        return recipesByHandler.containsKey(HandlerCatalog.idOf(handler));
    }

    /** Whether the recipe of the handler is one the player hid. */
    public boolean isRecipeHidden(IRecipeHandler handler, int recipeIndex) {
        List<HiddenRecipe> hidden = recipesByHandler.get(HandlerCatalog.idOf(handler));
        if (hidden == null) {
            return false;
        }
        RecipeId id;
        try {
            id = RecipeId.of(handler, recipeIndex);
        } catch (Throwable t) {
            return false;
        }
        for (HiddenRecipe recipe : hidden) {
            if (recipe.matches(id)) {
                return true;
            }
        }
        return false;
    }

    // Changing, on the client thread.

    public synchronized void hideTab(IRecipeHandler handler) {
        String id = HandlerCatalog.idOf(handler);
        if (id == null || tabs.containsKey(id)) {
            return;
        }
        Map<String, SavedTab> next = new LinkedHashMap<>(tabs);
        next.put(id, new SavedTab(id, HandlerCatalog.nameOf(handler), HandlerCatalog.modOf(handler), now()));
        tabs = Collections.unmodifiableMap(next);
        changed();
    }

    public synchronized void showTab(String id) {
        if (!tabs.containsKey(id)) {
            return;
        }
        Map<String, SavedTab> next = new LinkedHashMap<>(tabs);
        next.remove(id);
        tabs = Collections.unmodifiableMap(next);
        changed();
    }

    /** Hides the tabs at once (all of a mod), saved once. */
    public synchronized void hideTabs(List<IRecipeHandler> handlers) {
        Map<String, SavedTab> next = new LinkedHashMap<>(tabs);
        for (IRecipeHandler handler : handlers) {
            String id = HandlerCatalog.idOf(handler);
            if (id != null && !next.containsKey(id)) {
                next.put(id, new SavedTab(id, HandlerCatalog.nameOf(handler), HandlerCatalog.modOf(handler), now()));
            }
        }
        if (next.size() != tabs.size()) {
            tabs = Collections.unmodifiableMap(next);
            changed();
        }
    }

    /** Shows the tabs again at once, saved once. */
    public synchronized void showTabs(Collection<String> ids) {
        Map<String, SavedTab> next = new LinkedHashMap<>(tabs);
        next.keySet()
            .removeAll(ids);
        if (next.size() != tabs.size()) {
            tabs = Collections.unmodifiableMap(next);
            changed();
        }
    }

    public synchronized void showAllTabs() {
        if (tabs.isEmpty()) {
            return;
        }
        tabs = Collections.emptyMap();
        changed();
    }

    /** Puts the tab first of all ({@code position} 0), last of the ones put first (-1), or anywhere between. */
    public synchronized void pin(IRecipeHandler handler, int position) {
        String id = HandlerCatalog.idOf(handler);
        if (id == null) {
            return;
        }
        List<SavedTab> next = new ArrayList<>(pinned);
        next.removeIf(tab -> tab.id.equals(id));
        SavedTab tab = new SavedTab(id, HandlerCatalog.nameOf(handler), HandlerCatalog.modOf(handler), now());
        next.add(position < 0 || position > next.size() ? next.size() : position, tab);
        setPinned(next);
        changed();
    }

    /** Puts the tabs first, after the ones already first, in the order given; saved once. */
    public synchronized void pinAll(List<IRecipeHandler> handlers) {
        List<SavedTab> next = new ArrayList<>(pinned);
        boolean added = false;
        for (IRecipeHandler handler : handlers) {
            String id = HandlerCatalog.idOf(handler);
            if (id != null && pinPosition(id) < 0
                && next.stream()
                    .noneMatch(tab -> tab.id.equals(id))) {
                next.add(new SavedTab(id, HandlerCatalog.nameOf(handler), HandlerCatalog.modOf(handler), now()));
                added = true;
            }
        }
        if (added) {
            setPinned(next);
            changed();
        }
    }

    /** Takes the tabs out of the ones put first at once, saved once. */
    public synchronized void unpin(Collection<String> ids) {
        List<SavedTab> next = new ArrayList<>(pinned);
        if (next.removeIf(tab -> ids.contains(tab.id))) {
            setPinned(next);
            changed();
        }
    }

    public synchronized void unpin(String id) {
        List<SavedTab> next = new ArrayList<>(pinned);
        if (next.removeIf(tab -> tab.id.equals(id))) {
            setPinned(next);
            changed();
        }
    }

    /** Moves a tab put first to another place among them. */
    public synchronized void movePin(String id, int position) {
        int from = pinPosition(id);
        if (from < 0) {
            return;
        }
        List<SavedTab> next = new ArrayList<>(pinned);
        SavedTab tab = next.remove(from);
        int to = Math.max(0, Math.min(position, next.size()));
        if (to == from) {
            return;
        }
        next.add(to, tab);
        setPinned(next);
        changed();
    }

    public synchronized void unpinAll() {
        if (pinned.isEmpty()) {
            return;
        }
        setPinned(new ArrayList<>());
        changed();
    }

    private void setPinned(List<SavedTab> next) {
        Map<String, Integer> positions = new HashMap<>();
        for (int i = 0; i < next.size(); i++) {
            positions.put(next.get(i).id, i);
        }
        pinPositions = positions;
        pinned = Collections.unmodifiableList(next);
    }

    /** Hides the recipe; returns what was hidden (for taking it back), or null when it can't be told apart. */
    public synchronized HiddenRecipe hideRecipe(IRecipeHandler handler, int recipeIndex) {
        RecipeId id;
        try {
            id = RecipeId.of(handler, recipeIndex);
        } catch (Throwable t) {
            NEIExtend.LOG.warn("Could not make an id for recipe {} of {}", recipeIndex, handler, t);
            return null;
        }
        if (isRecipeHidden(handler, recipeIndex)) {
            return null;
        }
        HiddenRecipe recipe = new HiddenRecipe(
            HandlerCatalog.idOf(handler),
            HandlerCatalog.nameOf(handler),
            now(),
            id,
            id.toJsonObject());
        List<HiddenRecipe> next = new ArrayList<>(recipes);
        next.add(recipe);
        setRecipes(next);
        changed();
        return recipe;
    }

    public synchronized void showRecipe(HiddenRecipe recipe) {
        List<HiddenRecipe> next = new ArrayList<>(recipes);
        if (next.remove(recipe)) {
            setRecipes(next);
            changed();
        }
    }

    /** Shows the recipes again, all in one change. */
    public synchronized void showRecipes(Collection<HiddenRecipe> shown) {
        List<HiddenRecipe> next = new ArrayList<>(recipes);
        if (next.removeAll(new HashSet<>(shown))) {
            setRecipes(next);
            changed();
        }
    }

    /** Hides a recipe shown again (from the list of the hidden ones) once more, in its place among them. */
    public synchronized void hideAgain(HiddenRecipe recipe) {
        if (recipes.contains(recipe)) {
            return;
        }
        List<HiddenRecipe> next = new ArrayList<>(recipes);
        int at = 0;
        while (at < next.size() && next.get(at).time <= recipe.time) {
            at++;
        }
        next.add(at, recipe);
        setRecipes(next);
        changed();
    }

    public synchronized void showAllRecipes() {
        if (recipes.isEmpty()) {
            return;
        }
        setRecipes(new ArrayList<>());
        changed();
    }

    private void setRecipes(List<HiddenRecipe> next) {
        Map<String, List<HiddenRecipe>> byHandler = new HashMap<>();
        for (HiddenRecipe recipe : next) {
            byHandler.computeIfAbsent(recipe.handlerId, k -> new ArrayList<>())
                .add(recipe);
        }
        recipesByHandler = byHandler;
        recipes = Collections.unmodifiableList(next);
    }

    private void changed() {
        revision++;
        save();
    }

    private static long now() {
        return System.currentTimeMillis();
    }

    // The file.

    public synchronized void load() {
        if (file == null || !file.isFile()) {
            return;
        }
        try (Reader reader = Files.newBufferedReader(file.toPath(), StandardCharsets.UTF_8)) {
            JsonObject root = new JsonParser().parse(reader)
                .getAsJsonObject();
            Map<String, SavedTab> loadedTabs = new LinkedHashMap<>();
            for (JsonElement element : array(root, "hiddenTabs")) {
                JsonObject tab = element.getAsJsonObject();
                String id = string(tab, "id", null);
                if (id != null) {
                    loadedTabs.put(
                        id,
                        new SavedTab(id, string(tab, "name", id), string(tab, "mod", "?"), number(tab, "time")));
                }
            }
            List<SavedTab> loadedPinned = new ArrayList<>();
            for (JsonElement element : array(root, "firstTabs")) {
                JsonObject tab = element.getAsJsonObject();
                String id = string(tab, "id", null);
                if (id != null) {
                    loadedPinned
                        .add(new SavedTab(id, string(tab, "name", id), string(tab, "mod", "?"), number(tab, "time")));
                }
            }
            List<HiddenRecipe> loadedRecipes = new ArrayList<>();
            for (JsonElement element : array(root, "hiddenRecipes")) {
                JsonObject entry = element.getAsJsonObject();
                String handlerId = string(entry, "handler", null);
                JsonObject raw = entry.has("recipe") && entry.get("recipe")
                    .isJsonObject() ? entry.getAsJsonObject("recipe") : null;
                if (handlerId == null || raw == null) {
                    continue;
                }
                RecipeId id = null;
                try {
                    id = RecipeId.of(raw);
                    if (id.getHandlerName() == null) {
                        id = null;
                    }
                } catch (Throwable t) {
                    NEIExtend.LOG.warn("A hidden recipe of {} could not be read; it is kept as it is", handlerId);
                }
                loadedRecipes
                    .add(new HiddenRecipe(handlerId, string(entry, "tab", handlerId), number(entry, "time"), id, raw));
            }
            tabs = Collections.unmodifiableMap(loadedTabs);
            setRecipes(loadedRecipes);
            setPinned(loadedPinned);
            revision++;
            NEIExtend.LOG.info(
                "Loaded {} hidden tabs, {} hidden recipes and {} tabs put first",
                loadedTabs.size(),
                loadedRecipes.size(),
                loadedPinned.size());
        } catch (Exception e) {
            NEIExtend.LOG.error("Could not read {}; nothing is hidden", file, e);
        }
    }

    private void save() {
        if (file == null) {
            return;
        }
        JsonObject root = new JsonObject();
        root.add("hiddenTabs", tabArray(tabs.values()));
        root.add("firstTabs", tabArray(pinned));
        JsonArray recipeArray = new JsonArray();
        for (HiddenRecipe recipe : recipes) {
            JsonObject json = new JsonObject();
            json.addProperty("handler", recipe.handlerId);
            json.addProperty("tab", recipe.tab);
            json.addProperty("time", recipe.time);
            json.add("recipe", recipe.raw);
            recipeArray.add(json);
        }
        root.add("hiddenRecipes", recipeArray);
        try {
            File parent = file.getAbsoluteFile()
                .getParentFile();
            if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
                throw new IOException("Could not make " + parent);
            }
            File temp = new File(file.getPath() + ".tmp");
            try (Writer writer = Files.newBufferedWriter(temp.toPath(), StandardCharsets.UTF_8)) {
                GSON.toJson(root, writer);
            }
            Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            NEIExtend.LOG.error("Could not save {}", file, e);
        }
    }

    private static JsonArray tabArray(Iterable<SavedTab> list) {
        JsonArray array = new JsonArray();
        for (SavedTab tab : list) {
            JsonObject json = new JsonObject();
            json.addProperty("id", tab.id);
            json.addProperty("name", tab.name);
            json.addProperty("mod", tab.mod);
            json.addProperty("time", tab.time);
            array.add(json);
        }
        return array;
    }

    private static JsonArray array(JsonObject json, String key) {
        return json.has(key) && json.get(key)
            .isJsonArray() ? json.getAsJsonArray(key) : new JsonArray();
    }

    private static String string(JsonObject json, String key, String fallback) {
        return json.has(key) && json.get(key)
            .isJsonPrimitive() ? json.get(key)
                .getAsString() : fallback;
    }

    private static long number(JsonObject json, String key) {
        try {
            return json.has(key) ? json.get(key)
                .getAsLong() : 0;
        } catch (RuntimeException e) {
            return 0;
        }
    }
}
