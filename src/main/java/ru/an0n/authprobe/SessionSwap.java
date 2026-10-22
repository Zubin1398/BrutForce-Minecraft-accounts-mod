package ru.an0n.authprobe;

import com.mojang.authlib.minecraft.UserApiService;
import com.mojang.authlib.properties.PropertyMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.User;
import net.minecraft.client.gui.screens.social.PlayerSocialManager;
import net.minecraft.client.multiplayer.ProfileKeyPairManager;
import net.minecraft.client.multiplayer.chat.report.ReportEnvironment;
import net.minecraft.client.multiplayer.chat.report.ReportingContext;
import net.minecraft.client.telemetry.ClientTelemetryManager;
import ru.an0n.authprobe.mixins.MinecraftAccessor;

import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;

/**
 * Rewrites the running client session into an offline profile for an arbitrary nickname.
 * The login handshake reads {@link Minecraft#getUser()}, so this is the whole trick that
 * lets the probe join as a nickname it was not launched with.
 *
 * @author AN0N
 */
public final class SessionSwap {
    /**
     * Token placeholder. Offline-mode servers never validate it.
     */
    private static final String OFFLINE_TOKEN = "0";

    /**
     * An instance of this class cannot be created.
     *
     * @throws AssertionError Always
     */
    private SessionSwap() {
        throw new AssertionError("No instances.");
    }

    /**
     * Swaps the client into an offline session for the nickname.
     * Must run on the client thread.
     *
     * @param nick Target nickname
     */
    public static void toOffline(String nick) {
        Minecraft minecraft = Minecraft.getInstance();

        // Build the offline user. The UUID matches what an offline-mode server derives from the nick.
        UUID uuid = UUID.nameUUIDFromBytes(("OfflinePlayer:" + nick).getBytes(StandardCharsets.UTF_8));
        User user = new User(nick, uuid.toString(), OFFLINE_TOKEN, Optional.empty(), Optional.empty(), User.Type.LEGACY);

        // Build the dependent services against the offline API service, so nothing calls Mojang.
        UserApiService api = UserApiService.OFFLINE;
        PlayerSocialManager social = new PlayerSocialManager(minecraft, api);
        ClientTelemetryManager telemetry = new ClientTelemetryManager(minecraft, api, user);
        ProfileKeyPairManager keys = ProfileKeyPairManager.create(api, user, minecraft.gameDirectory.toPath());
        ReportingContext reporting = ReportingContext.create(ReportEnvironment.local(), api);

        // Flush everything into the client.
        PropertyMap properties = new PropertyMap();
        MinecraftAccessor accessor = (MinecraftAccessor) minecraft;
        accessor.authprobe$user(user);
        accessor.authprobe$userApiService(api);
        accessor.authprobe$profileProperties(properties);
        accessor.authprobe$playerSocialManager(social);
        accessor.authprobe$telemetryManager(telemetry);
        accessor.authprobe$profileKeyPairManager(keys);
        accessor.authprobe$reportingContext(reporting);
        minecraft.updateTitle();

        AuthProbe.logger().info("AuthProbe: session swapped to offline nick '{}' ({}).", nick, uuid);
    }
}