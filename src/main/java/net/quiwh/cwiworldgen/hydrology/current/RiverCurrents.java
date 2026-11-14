package net.quiwh.cwiworldgen.hydrology.current;

import java.util.function.Consumer;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.vehicle.Boat;
import net.minecraft.world.entity.item.ItemEntity;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.neoforged.neoforge.event.level.ChunkWatchEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.tick.EntityTickEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.quiwh.cwiworldgen.config.CurrentConfig;

/** Common registration contains no client classes, assets or renderer references. */
public final class RiverCurrents {
    private RiverCurrents() {}
    private static Consumer<CurrentFieldPayload> clientReceiver = ignored -> {};
    private static Runnable clientPolicyChanged = () -> {};
    private static CurrentGameplayPolicy clientPolicy = CurrentGameplayPolicy.WAITING;
    private static long clientPolicyRevision;
    private static final AtomicBoolean POLICY_RELOAD = new AtomicBoolean();

    public static void register(IEventBus modBus) {
        modBus.addListener(RiverCurrents::registerPayloads);
        modBus.addListener(RiverCurrents::configurationReloaded);
        NeoForge.EVENT_BUS.addListener(RiverCurrents::sent);
        NeoForge.EVENT_BUS.addListener(RiverCurrents::unwatched);
        NeoForge.EVENT_BUS.addListener(RiverCurrents::entityTick);
        NeoForge.EVENT_BUS.addListener(RiverCurrents::serverTick);
        NeoForge.EVENT_BUS.addListener(RiverCurrents::chunkUnloaded);
        NeoForge.EVENT_BUS.addListener(RiverCurrents::levelUnloaded);
        NeoForge.EVENT_BUS.addListener(RiverCurrents::logout);
        NeoForge.EVENT_BUS.addListener(RiverCurrents::login);
        NeoForge.EVENT_BUS.addListener(RiverCurrents::changedDimension);
    }
    public static void installClientReceiver(Consumer<CurrentFieldPayload> receiver) { clientReceiver = receiver; }
    public static void installClientPolicyReceiver(Runnable receiver) { clientPolicyChanged = receiver; }
    public static CurrentGameplayPolicy clientPolicy() { return clientPolicy; }
    public static void clearClientPolicy() { clientPolicy = CurrentGameplayPolicy.WAITING; clientPolicyRevision = 0; }
    static void receivePolicy(CurrentPolicyPayload packet) {
        if (packet.revision() <= clientPolicyRevision) return;
        clientPolicy = packet.policy();
        clientPolicyRevision = packet.revision();
        clientPolicyChanged.run();
    }
    static boolean currentFieldRevision(long revision) { return revision > clientPolicyRevision; }
    private static void registerPayloads(RegisterPayloadHandlersEvent event) {
        event.registrar(CurrentFieldPayload.PROTOCOL).playToClient(CurrentFieldPayload.TYPE,
                CurrentFieldPayload.STREAM_CODEC, (packet, context) -> {
                    if (currentFieldRevision(packet.revision())) clientReceiver.accept(packet);
                });
        event.registrar(CurrentFieldPayload.PROTOCOL).playToClient(CurrentPolicyPayload.TYPE,
                CurrentPolicyPayload.STREAM_CODEC, (packet, context) -> receivePolicy(packet));
    }
    private static void configurationReloaded(ModConfigEvent.Reloading event) {
        if (event.getConfig().getSpec() == CurrentConfig.SERVER) {
            // Reserve the client barrier on the server thread before renewing watches.
            // Reload callbacks can run before entity ticks or on a config watcher thread.
            POLICY_RELOAD.set(true);
        }
    }
    private static void sent(ChunkWatchEvent.Sent event) { ServerCurrentFields.sent(event.getPlayer(), event.getLevel(), event.getPos()); }
    private static void unwatched(ChunkWatchEvent.UnWatch event) { ServerCurrentFields.unwatched(event.getPlayer(), event.getLevel(), event.getPos()); }
    private static void serverTick(ServerTickEvent.Post event) {
        if (POLICY_RELOAD.getAndSet(false)) {
            var packet = CurrentPolicyPayload.capture(ServerCurrentFields.reserveRevision());
            ServerCurrentFields.invalidatePolicy();
            for (var player : event.getServer().getPlayerList().getPlayers()) PacketDistributor.sendToPlayer(player, packet);
        }
        ServerCurrentFields.flush();
    }
    private static void chunkUnloaded(ChunkEvent.Unload event) {
        if (event.getLevel() instanceof ServerLevel server) ServerCurrentFields.chunkUnloaded(server, event.getChunk().getPos());
    }
    private static void levelUnloaded(LevelEvent.Unload event) {
        if (event.getLevel() instanceof ServerLevel server) ServerCurrentFields.unloaded(server);
    }
    private static void logout(PlayerEvent.PlayerLoggedOutEvent event) { ServerCurrentFields.playerLeft(event.getEntity().getUUID()); }
    private static void login(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof net.minecraft.server.level.ServerPlayer player)
            PacketDistributor.sendToPlayer(player, CurrentPolicyPayload.capture(ServerCurrentFields.reserveRevision()));
    }
    private static void changedDimension(PlayerEvent.PlayerChangedDimensionEvent event) { ServerCurrentFields.playerLeft(event.getEntity().getUUID()); }

    public static void entityTick(EntityTickEvent.Post event) {
        var entity = event.getEntity();
        boolean player = entity instanceof Player;
        if (!CurrentMotion.owns(entity.level().isClientSide, player,
                player && ((Player) entity).isLocalPlayer(), entity instanceof Boat)
                || entity.isPassenger() || entity.isSpectator() || CurrentEntityPolicy.ignored(entity)
                || !entity.isPushedByFluid() && !CurrentEntityPolicy.drifts(entity)) return;
        CurrentColumn sample = RuntimeCurrentSampler.sample(entity);
        if (!sample.active()) return;
        var old = entity.getDeltaMovement();
        var next = entity instanceof ItemEntity || CurrentEntityPolicy.drifts(entity)
                ? CurrentMotion.drift(old.x, old.z, sample, CurrentEntityPolicy.multiplier(entity),
                        entity.level().isClientSide ? clientPolicy.itemDriftSpeed() : CurrentConfig.ITEM_DRIFT_SPEED.get())
                : CurrentMotion.push(old.x, old.z, sample, CurrentEntityPolicy.multiplier(entity));
        entity.setDeltaMovement(next.x(), old.y, next.z());
    }
}
