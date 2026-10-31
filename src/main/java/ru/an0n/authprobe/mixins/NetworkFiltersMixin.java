package ru.an0n.authprobe.mixins;

import io.netty.channel.ChannelPipeline;
import net.minecraft.network.Connection;
import net.minecraftforge.network.filters.NetworkFilters;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Works around a Forge bug that crashes the client on servers which send a second login packet
 * on the same connection (limbo or authorisation proxies that transfer the player after a
 * correct password).
 *
 * <p>{@code NetworkFilters.injectIfNecessary} adds {@code forge:vanilla_filter} without checking
 * whether it is already in the pipeline, so the second login throws
 * {@code Duplicate handler name: forge:vanilla_filter}. The filters are already installed by the
 * first call, so skipping the repeat is safe.
 *
 * @author AN0N
 */
@Mixin(value = NetworkFilters.class, remap = false)
public final class NetworkFiltersMixin {
    /**
     * Skips a repeated filter injection on the same channel.
     *
     * @param manager Connection being set up
     * @param ci      Callback info
     */
    @Inject(method = "injectIfNecessary", at = @At("HEAD"), cancellable = true, remap = false)
    private static void authprobe$skipAlreadyInjected(Connection manager, CallbackInfo ci) {
        if (manager == null) return;
        ChannelPipeline pipeline = manager.channel().pipeline();
        if (pipeline.get("forge:vanilla_filter") != null) {
            ci.cancel();
        }
    }
}