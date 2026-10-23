package ru.an0n.authprobe;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.client.settings.KeyConflictContext;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.lwjgl.glfw.GLFW;

/**
 * Start macro keybind. The key is assigned in Options → Controls → AuthProbe.
 * Pressing it in-game runs the same logic as {@code .start} / {@code .go}.
 *
 * @author AN0N
 */
@Mod.EventBusSubscriber(modid = AuthProbe.MOD_ID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD)
public final class KeyBindings {
    /**
     * Start/resume macro. Unbound by default so it never fires by accident.
     */
    public static final KeyMapping START_MACRO = new KeyMapping(
            "key.authprobe.start_macro",
            KeyConflictContext.IN_GAME,
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_UNKNOWN,
            "key.categories.authprobe"
    );

    /**
     * An instance of this class cannot be created.
     *
     * @throws AssertionError Always
     */
    private KeyBindings() {
        throw new AssertionError("No instances.");
    }

    /**
     * Registers the key mapping with Forge.
     *
     * @param event Key mapping registration event
     */
    @SubscribeEvent
    public static void onRegister(RegisterKeyMappingsEvent event) {
        event.register(START_MACRO);
    }

    /**
     * Drains the macro key and starts probing when both the mod and the macro are enabled.
     * Called once per client tick from {@link ForgeEvents}.
     */
    public static void handleMacro() {
        boolean pressed = false;
        while (START_MACRO.consumeClick()) {
            pressed = true;
        }
        if (!pressed) return;
        if (!ProbeConfig.INSTANCE.modEnabled() || !ProbeConfig.INSTANCE.macroEnabled()) return;
        if (Minecraft.getInstance().screen != null) return;
        BotController.begin();
    }
}