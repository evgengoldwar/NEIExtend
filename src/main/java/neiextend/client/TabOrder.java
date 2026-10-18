package neiextend.client;

import java.util.Comparator;

import codechicken.nei.recipe.IRecipeHandler;

/** The order of the tabs on NEI's screens: the ones the player put first, in their order, then NEI's own order. */
public final class TabOrder {

    private TabOrder() {}

    public static <T extends IRecipeHandler> Comparator<T> around(Comparator<? super T> neiOrder) {
        return (a, b) -> {
            int first = HiddenStore.INSTANCE.pinPosition(a), second = HiddenStore.INSTANCE.pinPosition(b);
            if (first < 0 && second < 0) {
                return neiOrder.compare(a, b);
            }
            if (first < 0) {
                return 1;
            }
            return second < 0 ? -1 : Integer.compare(first, second);
        };
    }
}
