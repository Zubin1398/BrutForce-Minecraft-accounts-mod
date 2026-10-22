package ru.an0n.authprobe.mixins;

import com.mojang.authlib.minecraft.UserApiService;
import com.mojang.authlib.properties.PropertyMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.User;
import net.minecraft.client.gui.screens.social.PlayerSocialManager;
import net.minecraft.client.multiplayer.ProfileKeyPairManager;
import net.minecraft.client.multiplayer.chat.report.ReportingContext;
import net.minecraft.client.telemetry.ClientTelemetryManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Mixin accessor for rewriting the client session at runtime.
 * The client-side login handshake reads {@link Minecraft#getUser()}, so swapping it
 * is what lets the probe join as an arbitrary nickname.
 *
 * @author AN0N
 */
@SuppressWarnings("DollarSignInName") // <- Mixin.
@Mixin(Minecraft.class)
public interface MinecraftAccessor {
    /**
     * Sets the game user.
     *
     * @param user New user
     */
    @Accessor("user")
    @Mutable
    void authprobe$user(User user);

    /**
     * Sets the user API service.
     *
     * @param service New user API service
     */
    @Accessor("userApiService")
    @Mutable
    void authprobe$userApiService(UserApiService service);

    /**
     * Sets the profile properties.
     *
     * @param map New property map
     */
    @Accessor("profileProperties")
    @Mutable
    void authprobe$profileProperties(PropertyMap map);

    /**
     * Sets the player social manager.
     *
     * @param manager New player social manager
     */
    @Accessor("playerSocialManager")
    @Mutable
    void authprobe$playerSocialManager(PlayerSocialManager manager);

    /**
     * Sets the telemetry manager.
     *
     * @param manager New client telemetry manager
     */
    @Accessor("telemetryManager")
    @Mutable
    void authprobe$telemetryManager(ClientTelemetryManager manager);

    /**
     * Sets the profile key pair manager.
     *
     * @param manager New profile key pair manager
     */
    @Accessor("profileKeyPairManager")
    @Mutable
    void authprobe$profileKeyPairManager(ProfileKeyPairManager manager);

    /**
     * Sets the reporting context.
     *
     * @param context New reporting context
     */
    @Accessor("reportingContext")
    @Mutable
    void authprobe$reportingContext(ReportingContext context);
}