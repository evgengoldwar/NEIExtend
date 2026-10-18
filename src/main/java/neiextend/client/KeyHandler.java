package neiextend.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.I18n;
import net.minecraft.client.settings.GameSettings;
import net.minecraft.client.settings.KeyBinding;

import org.lwjgl.input.Keyboard;

import cpw.mods.fml.client.registry.ClientRegistry;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.InputEvent;
import neiextend.client.gui.GuiNEIExtend;

/** The key that opens the mod's window from the game; not set at first, the window opens from NEI too. */
public final class KeyHandler {

    public static final KeyBinding OPEN = new KeyBinding(
        "key.neiextend.open",
        Keyboard.KEY_NONE,
        "key.categories.neiextend");

    public static void register() {
        ClientRegistry.registerKeyBinding(OPEN);
    }

    /** The key's name as the controls show it, or a note that it is not set. */
    public static String keyName() {
        return OPEN.getKeyCode() == Keyboard.KEY_NONE ? I18n.format("neiextend.key.none")
            : GameSettings.getKeyDisplayString(OPEN.getKeyCode());
    }

    @SubscribeEvent
    public void onKey(InputEvent.KeyInputEvent event) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.currentScreen == null && OPEN.isPressed()) {
            mc.displayGuiScreen(new GuiNEIExtend(null));
        }
    }
}
