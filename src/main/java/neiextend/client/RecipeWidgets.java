package neiextend.client;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

import codechicken.nei.Widget;
import codechicken.nei.recipe.RecipeHandlerRef;

/**
 * The recipe a widget of NEI's recipe screen shows. NEI's class for those widgets changed between its versions
 * ({@code codechicken.nei.recipe.NEIRecipeWidget}, then {@code codechicken.nei.recipe.widget.RecipeWidget}); both
 * tell their recipe by {@code getRecipeHandlerRef()}, so the method is found by its name instead of naming either
 * class, and the mod runs with both.
 */
public final class RecipeWidgets {

    /** The method of each class of widget, or null for widgets that show no recipe. Client thread only. */
    private static final Map<Class<?>, Method> METHODS = new HashMap<>();

    private RecipeWidgets() {}

    /** The recipe the widget shows, or null when it shows none. */
    public static RecipeHandlerRef refOf(Widget widget) {
        if (widget == null) {
            return null;
        }
        Method method = METHODS.computeIfAbsent(widget.getClass(), RecipeWidgets::find);
        if (method == null) {
            return null;
        }
        try {
            Object ref = method.invoke(widget);
            return ref instanceof RecipeHandlerRef ? (RecipeHandlerRef) ref : null;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    private static Method find(Class<?> type) {
        try {
            Method method = type.getMethod("getRecipeHandlerRef");
            return RecipeHandlerRef.class.isAssignableFrom(method.getReturnType()) ? method : null;
        } catch (NoSuchMethodException e) {
            return null;
        }
    }
}
