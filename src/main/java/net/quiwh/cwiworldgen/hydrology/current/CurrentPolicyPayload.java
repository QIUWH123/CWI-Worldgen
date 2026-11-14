package net.quiwh.cwiworldgen.hydrology.current;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.quiwh.cwiworldgen.config.CurrentConfig;

/** Explicit play-stage resynchronization; NeoForge's config sync runs only at login. */
public record CurrentPolicyPayload(long revision, CurrentGameplayPolicy policy) implements CustomPacketPayload {
    public static final Type<CurrentPolicyPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath("cwi_worldgen", "current_policy"));
    public static final StreamCodec<RegistryFriendlyByteBuf, CurrentPolicyPayload> STREAM_CODEC = StreamCodec.of(
            (buffer, payload) -> {
                buffer.writeVarLong(payload.revision());
                var policy = payload.policy();
                buffer.writeBoolean(policy.enabled());
                buffer.writeDouble(policy.playerStrength());
                buffer.writeDouble(policy.boatStrength());
                buffer.writeDouble(policy.itemDriftSpeed());
                buffer.writeBoolean(policy.aquaticHold());
                buffer.writeBoolean(policy.boatSurface());
            }, buffer -> new CurrentPolicyPayload(buffer.readVarLong(), new CurrentGameplayPolicy(
                    buffer.readBoolean(), buffer.readDouble(), buffer.readDouble(), buffer.readDouble(),
                    buffer.readBoolean(), buffer.readBoolean())));

    public CurrentPolicyPayload {
        if (revision <= 0 || policy == null) throw new IllegalArgumentException("Invalid current policy header");
    }
    public static CurrentPolicyPayload capture(long revision) {
        return new CurrentPolicyPayload(revision, new CurrentGameplayPolicy(CurrentConfig.ENABLED.get(),
                CurrentConfig.PLAYER_STRENGTH.get(), CurrentConfig.BOAT_STRENGTH.get(), CurrentConfig.ITEM_DRIFT_SPEED.get(),
                CurrentConfig.AQUATIC_HOLD.get(), CurrentConfig.BOAT_SURFACE.get()));
    }
    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
