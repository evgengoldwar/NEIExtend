package neiextend.client;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraftforge.oredict.OreDictionary;

import codechicken.nei.PositionedStack;
import codechicken.nei.recipe.IRecipeHandler;
import codechicken.nei.recipe.TemplateRecipeHandler;
import codechicken.nei.recipe.TemplateRecipeHandler.CachedRecipe;

/**
 * Recipes of one tab that differ only by their material (ingotIron -> 9 nuggetIron, ingotGold -> 9 nuggetGold...)
 * shown as one recipe, which shows each of them in turn. It needs nothing from NEI's newer recipe groups: the
 * recipes are put together in the tab's own list, one place for each group, and the recipe in that place is swapped
 * for the next one of its group, so the tab draws a real recipe each time (GregTech's power and time with it).
 * <p>
 * Recipes have the same shape when their stacks are at the same places, in the same amounts, of the same items, but
 * for the ones named in the ore dictionary, whose names are compared without the recipe's material: the end of the
 * names all its named stacks share (ingot|Iron, nugget|Iron). Only tabs on NEI's template are grouped.
 */
public final class RecipeGroups {

    /** Recipes shown in one place of a tab, in turn. */
    public static final class Group {

        final List<CachedRecipe> members;
        /** Where the group is in the tab's list, and which of its recipes is there now. */
        final int index;
        int shown;
        /** The stacks of each recipe of the group at a place of a slot, made when asked. */
        private final Map<String, List<ItemStack>> slots = new HashMap<>();

        Group(List<CachedRecipe> members, int index) {
            this.members = members;
            this.index = index;
        }

        public int size() {
            return members.size();
        }

        public int shown() {
            return shown;
        }

        /** Where the group is in the tab's list. */
        public int index() {
            return index;
        }

        /** What the slot at the place holds in each recipe of the group, once each. */
        public List<ItemStack> slot(int relx, int rely) {
            return slots.computeIfAbsent(relx + "," + rely, k -> {
                List<ItemStack> stacks = new ArrayList<>();
                for (CachedRecipe member : members) {
                    for (PositionedStack pStack : stacksOf(member)) {
                        if (pStack.relx == relx && pStack.rely == rely
                            && pStack.items != null
                            && pStack.items.length > 0) {
                            ItemStack stack = pStack.items[0];
                            if (stacks.stream()
                                .noneMatch(other -> ItemStack.areItemStacksEqual(other, stack))) {
                                stacks.add(stack);
                            }
                            break;
                        }
                    }
                }
                return stacks;
            });
        }
    }

    /** A grouped tab: its recipes as they were, and its groups by their place in its list. */
    private static final class Grouped {

        final List<CachedRecipe> original;
        final Map<Integer, Group> groups;

        Grouped(List<CachedRecipe> original, Map<Integer, Group> groups) {
            this.original = original;
            this.groups = groups;
        }
    }

    private static final Map<IRecipeHandler, Grouped> GROUPED = Collections.synchronizedMap(new WeakHashMap<>());

    private RecipeGroups() {}

    /** Puts the recipes of the tab that differ only by material together, when that is on. Any thread. */
    public static void group(IRecipeHandler handler) {
        if (!HiddenStore.INSTANCE.groupRecipes() || !(handler instanceof TemplateRecipeHandler)
            || GROUPED.containsKey(handler)) {
            return;
        }
        TemplateRecipeHandler template = (TemplateRecipeHandler) handler;
        List<CachedRecipe> recipes = template.arecipes;
        if (recipes == null || recipes.size() < 2) {
            return;
        }
        // Recipes by their shape, in the order of the tab; hidden ones stay alone, for NEI to leave out.
        Map<String, List<Integer>> byShape = new LinkedHashMap<>();
        for (int i = 0; i < recipes.size(); i++) {
            String shape = HiddenStore.INSTANCE.isRecipeHidden(template, i) ? null : shapeOf(recipes.get(i));
            byShape.computeIfAbsent(shape != null ? shape : "#" + i, k -> new ArrayList<>())
                .add(i);
        }
        if (byShape.size() == recipes.size()) {
            return;
        }
        List<CachedRecipe> original = new ArrayList<>(recipes);
        List<CachedRecipe> placed = new ArrayList<>();
        Map<Integer, Group> groups = new HashMap<>();
        for (List<Integer> indices : byShape.values()) {
            if (indices.size() > 1) {
                List<CachedRecipe> members = new ArrayList<>();
                for (int i : indices) {
                    members.add(original.get(i));
                }
                groups.put(placed.size(), new Group(members, placed.size()));
            }
            placed.add(original.get(indices.get(0)));
        }
        synchronized (recipes) {
            recipes.clear();
            recipes.addAll(placed);
        }
        GROUPED.put(handler, new Grouped(original, groups));
    }

    /** Puts the tab's recipes back as they were. */
    public static void ungroup(IRecipeHandler handler) {
        Grouped grouped = GROUPED.remove(handler);
        if (grouped != null && handler instanceof TemplateRecipeHandler) {
            List<CachedRecipe> recipes = ((TemplateRecipeHandler) handler).arecipes;
            synchronized (recipes) {
                recipes.clear();
                recipes.addAll(grouped.original);
            }
        }
    }

    /** The group shown at the place of the tab's list, or null. */
    public static Group groupAt(IRecipeHandler handler, int index) {
        Grouped grouped = handler == null ? null : GROUPED.get(handler);
        return grouped == null ? null : grouped.groups.get(index);
    }

    /** Every group of the tab. */
    public static List<Group> groupsOf(IRecipeHandler handler) {
        Grouped grouped = handler == null ? null : GROUPED.get(handler);
        return grouped == null ? Collections.emptyList() : new ArrayList<>(grouped.groups.values());
    }

    /** Shows the next recipe of the group (back for a negative step), leaving out hidden ones. */
    public static void step(IRecipeHandler handler, Group group, int step) {
        List<CachedRecipe> recipes = ((TemplateRecipeHandler) handler).arecipes;
        int size = group.members.size();
        for (int tries = 0; tries < size; tries++) {
            group.shown = Math.floorMod(group.shown + Integer.signum(step), size);
            recipes.set(group.index, group.members.get(group.shown));
            if (!HiddenStore.INSTANCE.isRecipeHidden(handler, group.index)) {
                return;
            }
        }
    }

    /** When the recipe shown at the place was just hidden, shows another of its group instead. */
    public static void skipHidden(IRecipeHandler handler, int index) {
        Group group = groupAt(handler, index);
        if (group != null && HiddenStore.INSTANCE.isRecipeHidden(handler, index)) {
            step(handler, group, 1);
        }
    }

    private static List<PositionedStack> stacksOf(CachedRecipe recipe) {
        List<PositionedStack> stacks = new ArrayList<>();
        try {
            stacks.addAll(recipe.getIngredients());
            stacks.addAll(recipe.getOtherStacks());
            if (recipe.getResult() != null) {
                stacks.add(recipe.getResult());
            }
        } catch (Throwable ignored) {
            // A recipe that can't list its stacks is shown alone.
        }
        return stacks;
    }

    /**
     * The shape of the recipe: each stack's place, amount and item, the ore names without the recipe's material. Null
     * when it has no named stacks, or they don't share a material: such a recipe stays alone.
     */
    private static String shapeOf(CachedRecipe recipe) {
        List<PositionedStack> stacks = stacksOf(recipe);
        if (stacks.isEmpty()) {
            return null;
        }
        List<List<String>> names = new ArrayList<>();
        for (PositionedStack pStack : stacks) {
            names.add(oreNames(pStack));
        }
        String material = material(names);
        if (material == null) {
            return null;
        }
        List<String> parts = new ArrayList<>();
        for (int i = 0; i < stacks.size(); i++) {
            PositionedStack pStack = stacks.get(i);
            ItemStack stack = pStack.items == null || pStack.items.length == 0 ? pStack.item : pStack.items[0];
            if (stack == null) {
                continue;
            }
            String what = null;
            for (String name : names.get(i)) {
                if (name.endsWith(material) && name.length() > material.length()) {
                    what = "ore:" + name.substring(0, name.length() - material.length());
                    break;
                }
            }
            if (what == null) {
                what = Item.getIdFromItem(stack.getItem()) + ":"
                    + stack.getItemDamage()
                    + (stack.hasTagCompound() ? "#" + stack.getTagCompound()
                        .hashCode() : "");
            }
            parts.add(pStack.relx + "," + pStack.rely + "x" + stack.stackSize + "=" + what);
        }
        Collections.sort(parts);
        return String.join(";", parts);
    }

    private static List<String> oreNames(PositionedStack pStack) {
        List<String> names = new ArrayList<>();
        ItemStack stack = pStack.items == null || pStack.items.length == 0 ? pStack.item : pStack.items[0];
        if (stack == null || stack.getItem() == null) {
            return names;
        }
        try {
            for (int id : OreDictionary.getOreIDs(stack)) {
                names.add(OreDictionary.getOreName(id));
            }
        } catch (Throwable ignored) {
            // No ore names for it.
        }
        return names;
    }

    /**
     * The longest end of a name every named stack shares, starting with a capital and leaving a prefix in front:
     * "Iron" for ingotIron and nuggetIron, "WroughtIron" for ingotWroughtIron and dustWroughtIron. Null for none.
     */
    private static String material(List<List<String>> names) {
        List<String> first = null;
        for (List<String> stackNames : names) {
            if (!stackNames.isEmpty()) {
                first = stackNames;
                break;
            }
        }
        if (first == null) {
            return null;
        }
        String best = null;
        for (String name : first) {
            for (int at = 1; at < name.length(); at++) {
                if (!Character.isUpperCase(name.charAt(at))) {
                    continue;
                }
                String candidate = name.substring(at);
                if ((best == null || candidate.length() > best.length()) && sharedByAll(names, candidate)) {
                    best = candidate;
                }
            }
        }
        return best;
    }

    private static boolean sharedByAll(List<List<String>> names, String material) {
        for (List<String> stackNames : names) {
            if (stackNames.isEmpty()) {
                continue;
            }
            boolean found = false;
            for (String name : stackNames) {
                if (name.endsWith(material) && name.length() > material.length()) {
                    found = true;
                    break;
                }
            }
            if (!found) {
                return false;
            }
        }
        return true;
    }
}
