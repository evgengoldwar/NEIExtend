package neiextend.client;

import java.io.File;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
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
 * handler) and single recipes (by their handler and NEI's id of the recipe: its ingredients and result).
 * <p>
 * NEI looks recipes up on several threads at once, so the lists are never changed in place: a change makes new ones
 * and swaps them in, and the lookups read whichever ones are current.
 */
public final class HiddenStore {

    public static final HiddenStore INSTANCE = new HiddenStore();

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting()
        .disableHtmlEscaping()
        .create();

    /** A hidden tab, with what it was called when it was hidden: shown even when its mod is gone. */
    public static final class HiddenTab {

        public final String id;
        public final String name;
        public final String mod;
        public final long time;

        HiddenTab(String id, String name, String mod, long time) {
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

    private volatile Map<String, HiddenTab> tabs = Collections.emptyMap();
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

    public List<HiddenTab> tabs() {
        return new ArrayList<>(tabs.values());
    }

    public int tabCount() {
        return tabs.size();
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
        Map<String, HiddenTab> next = new LinkedHashMap<>(tabs);
        next.put(id, new HiddenTab(id, HandlerCatalog.nameOf(handler), HandlerCatalog.modOf(handler), now()));
        tabs = Collections.unmodifiableMap(next);
        changed();
    }

    public synchronized void showTab(String id) {
        if (!tabs.containsKey(id)) {
            return;
        }
        Map<String, HiddenTab> next = new LinkedHashMap<>(tabs);
        next.remove(id);
        tabs = Collections.unmodifiableMap(next);
        changed();
    }

    public synchronized void showAllTabs() {
        if (tabs.isEmpty()) {
            return;
        }
        tabs = Collections.emptyMap();
        changed();
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
            Map<String, HiddenTab> loadedTabs = new LinkedHashMap<>();
            for (JsonElement element : array(root, "hiddenTabs")) {
                JsonObject tab = element.getAsJsonObject();
                String id = string(tab, "id", null);
                if (id != null) {
                    loadedTabs.put(
                        id,
                        new HiddenTab(id, string(tab, "name", id), string(tab, "mod", "?"), number(tab, "time")));
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
            revision++;
            NEIExtend.LOG.info("Loaded {} hidden tabs and {} hidden recipes", loadedTabs.size(), loadedRecipes.size());
        } catch (Exception e) {
            NEIExtend.LOG.error("Could not read {}; nothing is hidden", file, e);
        }
    }

    private void save() {
        if (file == null) {
            return;
        }
        JsonObject root = new JsonObject();
        JsonArray tabArray = new JsonArray();
        for (HiddenTab tab : tabs.values()) {
            JsonObject json = new JsonObject();
            json.addProperty("id", tab.id);
            json.addProperty("name", tab.name);
            json.addProperty("mod", tab.mod);
            json.addProperty("time", tab.time);
            tabArray.add(json);
        }
        root.add("hiddenTabs", tabArray);
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
