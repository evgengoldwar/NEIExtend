package neiextend.client.gui.ui;

/** Minimal pixel icons, drawn with rectangles in the theme's colors (the same style as LagFinder's). */
public final class Icons {

    private Icons() {}

    /** The mod's mark: an eye with a line through it, things taken out of sight. */
    public static final String[] LOGO = { "#..........", ".#..###....", "..##...##..", ".#.#.#...#.", "#...###...#",
        ".#...#.#.#.", "..##...##..", "....###..#.", "..........#" };

    /** Recipe tabs: three tabs over a page. */
    public static final String[] TABS = { "###.###.###", "#.#.#.#.#.#", "###########", "#.........#", "#.........#",
        "#.........#", "#.........#", "###########" };

    /** Recipes: a crafting grid with an arrow to its result. */
    public static final String[] RECIPES = { "###.......", "#.#.......", "###...#...", ".......#.#", "###.#######",
        "#.#....#.#", "###...#...", "...........", "###......." };

    /** Help: a "?" in a circle. */
    public static final String[] HELP = { "..#####..", ".#.....#.", "#..###..#", "#.....#.#", "#....#..#", "#...#...#",
        "#.......#", ".#..#..#.", "..#####.." };

    public static final String[] CLOSE = { // a cross
        "##.....##", ".##...##.", "..##.##..", "...###...", "...###...", "..##.##..", ".##...##.", "##.....##" };

    /** Settings and the mod's window: a cog. */
    public static final String[] GEAR = { "....#....", ".#.###.#.", "..#####..", ".##...##.", "###...###", ".##...##.",
        "..#####..", ".#.###.#.", "....#...." };

    /** Hiding a tab: an eye struck through. */
    public static final String[] EYE_OFF = { "#........", ".#.###...", "..#...#..", ".#.#.#.#.", "#...#...#",
        ".#..##.#.", "..#...##.", "...###..#", "........." };

    /** Picking recipes to hide: a crosshair. */
    public static final String[] TARGET = { "....#....", "..#####..", ".#..#..#.", ".#.....#.", "###.#.###",
        ".#.....#.", ".#..#..#.", "..#####..", "....#...." };

    /** Putting a tab first: a star. */
    public static final String[] STAR = { "....#....", "...###...", "...###...", "#########", ".#######.", "..#####..",
        "..#####..", ".###.###.", ".##...##." };

    /** The order of the tabs: lines of a list, the first one raised. */
    public static final String[] ORDER = { "..#......", ".###.####", "#####....", "..#..####", "..#......", "..#..####",
        "..#......", "..#..####", "........." };

    /** Grouping recipes: cards stacked on each other. */
    public static final String[] GROUP = { "....#####", "....#...#", "..#####.#", "..#...#.#", "#####.###", "#...#.#..",
        "#...###..", "#...#....", "#####...." };

    // Small icons (7x7).
    public static final String[] SMALL_STAR = { "...#...", "..###..", "#######", ".#####.", "..###..", ".##.##.",
        ".#...#." };
    public static final String[] SMALL_STAR_EMPTY = { "...#...", "..#.#..", "##...##", ".#...#.", "..#.#..", ".#.#.#.",
        ".#...#." };
    public static final String[] SMALL_UP = { "...#...", "..###..", ".#####.", "#######", "..###..", "..###..",
        "..###.." };
    public static final String[] SMALL_DOWN = { "..###..", "..###..", "..###..", "#######", ".#####.", "..###..",
        "...#..." };
    public static final String[] SMALL_CLEAR = { "#.....#", ".#...#.", "..#.#..", "...#...", "..#.#..", ".#...#.",
        "#.....#" };
    public static final String[] SMALL_SEARCH = { ".###...", "#...#..", "#...#..", "#...#..", ".###...", "....##.",
        ".....##" };
    public static final String[] SMALL_EYE = { ".......", "..###..", ".#...#.", "#..#..#", ".#...#.", "..###..",
        "......." };
    public static final String[] SMALL_EYE_OFF = { "#......", ".####..", ".##..#.", "#..#..#", ".#..##.", "..####.",
        "......#" };
    public static final String[] SMALL_CHECK = { "......#", ".....##", "#...##.", "##.##..", ".###...", "..#....",
        "......." };
    public static final String[] SMALL_UNDO = { "..#....", ".##....", "#######", ".##...#", "..#...#", "......#",
        "..####." };
    public static final String[] SMALL_TRASH = { "..###..", "#######", ".#####.", ".#.#.#.", ".#.#.#.", ".#.#.#.",
        ".#####." };
    public static final String[] SMALL_FOLDER = { "###....", "#######", "#.....#", "#.....#", "#.....#", "#.....#",
        "#######" };
    public static final String[] SMALL_KEY = { ".###...", "#...#..", "#...###", "#...#.#", ".###..#", ".......",
        "......." };
    public static final String[] SMALL_MOUSE = { ".#####.", "#..#..#", "#..#..#", "#######", "#.....#", "#.....#",
        ".#####." };
    public static final String[] SMALL_WARN = { "...#...", "..###..", "..#.#..", ".##.##.", ".##.##.", "#######",
        "###.###" };
    public static final String[] SMALL_ARROW = { "...#...", "...##..", "#######", "...##..", "...#...", ".......",
        "......." };

    public static int width(String[] icon) {
        int width = 0;
        for (String row : icon) {
            width = Math.max(width, row.length());
        }
        return width;
    }

    /** Draws the icon with its top left corner at (x, y), one GUI pixel per '#', merging runs into rectangles. */
    public static void draw(String[] icon, int x, int y, int color) {
        for (int row = 0; row < icon.length; row++) {
            String line = icon[row];
            int start = -1;
            for (int col = 0; col <= line.length(); col++) {
                boolean filled = col < line.length() && line.charAt(col) == '#';
                if (filled && start < 0) {
                    start = col;
                } else if (!filled && start >= 0) {
                    Theme.fill(x + start, y + row, x + col, y + row + 1, color);
                    start = -1;
                }
            }
        }
    }

    /** Draws the icon centered in the box. */
    public static void drawCentered(String[] icon, int x0, int y0, int x1, int y1, int color) {
        draw(icon, x0 + (x1 - x0 - width(icon)) / 2, y0 + (y1 - y0 - icon.length) / 2, color);
    }
}
