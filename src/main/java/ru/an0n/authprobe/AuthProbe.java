package ru.an0n.authprobe;

import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.Mod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * AuthProbe mod entrypoint.
 *
 * @author AN0N
 */
@Mod(AuthProbe.MOD_ID)
public final class AuthProbe {
    /**
     * Mod id.
     */
    public static final String MOD_ID = "authprobe";

    /**
     * Logger for this class.
     */
    private static final Logger LOGGER = LoggerFactory.getLogger("AuthProbe");

    /**
     * Creates a new mod.
     */
    public AuthProbe() {
        ProbeConfig.INSTANCE.load();
        ChatWatcher.setEnabled(ProbeConfig.INSTANCE.modEnabled());
        MinecraftForge.EVENT_BUS.register(new ForgeEvents());
        LOGGER.info("AuthProbe: loading...");
    }

    /**
     * Gets the mod logger.
     *
     * @return Logger
     */
    public static Logger logger() {
        return LOGGER;
    }
}