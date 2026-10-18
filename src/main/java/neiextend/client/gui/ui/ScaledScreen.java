package neiextend.client.gui.ui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.ScaledResolution;

import org.lwjgl.input.Mouse;
import org.lwjgl.opengl.GL11;

/**
 * Base of the mod's screens: they are laid out at their own scale instead of Minecraft's GUI scale, so they look the
 * same whatever that is set to. It is the largest whole scale that still leaves about 640x400 GUI pixels. The screen
 * slides softly into place when it opens. Subclasses draw in {@link #drawScaled}.
 */
public abstract class ScaledScreen extends GuiScreen {

    private static final int MIN_WIDTH = 640, MIN_HEIGHT = 400;
    /** How long a screen takes to slide into place when it opens, and how far it comes from (GUI pixels). */
    private static final long OPEN_MS = 180;
    private static final int OPEN_DISTANCE = 8;

    /** Scale the screen being drawn uses (screen pixels per GUI pixel), 0 when none of ours is drawing. */
    private static int activeFactor;
    /** How far down the screen being drawn is moved while it slides in (GUI pixels). */
    private static int activeOffset;

    private int appliedFactor;
    private long openedAt;

    /** Screen pixels per GUI pixel of the mod's screens. */
    public static int factor(Minecraft mc) {
        int fit = Math.min(mc.displayWidth / MIN_WIDTH, mc.displayHeight / MIN_HEIGHT);
        return Math.max(1, fit);
    }

    /**
     * Screen pixels per GUI pixel of what is being drawn right now: the mod's screen scale inside its screens,
     * Minecraft's GUI scale elsewhere (the recipe screen).
     */
    public static int currentFactor() {
        if (activeFactor > 0) {
            return activeFactor;
        }
        Minecraft mc = Minecraft.getMinecraft();
        return new ScaledResolution(mc, mc.displayWidth, mc.displayHeight).getScaleFactor();
    }

    /** How far down the screen being drawn is moved while it slides in, for drawing that uses screen pixels. */
    public static int currentOffset() {
        return activeOffset;
    }

    @Override
    public void setWorldAndResolution(Minecraft mc, int width, int height) {
        appliedFactor = factor(mc);
        super.setWorldAndResolution(
            mc,
            (mc.displayWidth + appliedFactor - 1) / appliedFactor,
            (mc.displayHeight + appliedFactor - 1) / appliedFactor);
    }

    /** The mouse in the screen's own GUI pixels. */
    protected int mouseX() {
        return Mouse.getX() * width / mc.displayWidth;
    }

    protected int mouseY() {
        return height - Mouse.getY() * height / mc.displayHeight - 1;
    }

    @Override
    public final void drawScreen(int mouseX, int mouseY, float partialTicks) {
        int expectedWidth = (mc.displayWidth + appliedFactor - 1) / Math.max(1, appliedFactor);
        if (factor(mc) != appliedFactor || width != expectedWidth) {
            // The window changed: lay the screen out again.
            setWorldAndResolution(mc, 0, 0);
        }
        int minecraftFactor = new ScaledResolution(mc, mc.displayWidth, mc.displayHeight).getScaleFactor();
        float scale = (float) appliedFactor / minecraftFactor;
        long now = System.currentTimeMillis();
        if (openedAt == 0) {
            openedAt = now;
        }
        // Eases out: fast at first, settling into place.
        double t = Math.min(1, (now - openedAt) / (double) OPEN_MS);
        int offset = (int) Math.round(Math.pow(1 - t, 3) * OPEN_DISTANCE);
        GL11.glPushMatrix();
        GL11.glScalef(scale, scale, 1f);
        if (offset > 0) {
            // The strip the screen leaves at the top while lower down is dimmed like the rest.
            Theme.fill(0, 0, width, offset, Theme.SCREEN_DIM);
            GL11.glTranslatef(0f, offset, 0f);
        }
        activeFactor = appliedFactor;
        activeOffset = offset;
        try {
            // Minecraft passes the mouse in its own GUI pixels; ours are different.
            drawScaled(mouseX(), mouseY() - offset, partialTicks);
        } finally {
            activeFactor = 0;
            activeOffset = 0;
            GL11.glPopMatrix();
        }
    }

    /** Draws the screen in its own GUI pixels; the default draws the buttons. */
    public void drawScaled(int mouseX, int mouseY, float partialTicks) {
        super.drawScreen(mouseX, mouseY, partialTicks);
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }
}
