package net.quiwh.cwiworldgen.client;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.quiwh.cwiworldgen.config.CurrentConfig;
import net.quiwh.cwiworldgen.hydrology.current.CurrentColumn;
import net.quiwh.cwiworldgen.hydrology.current.CurrentFieldPayload;
import net.quiwh.cwiworldgen.hydrology.current.CurrentPresentation;
import net.quiwh.cwiworldgen.hydrology.current.CurrentPresentation.Kind;
import net.quiwh.cwiworldgen.hydrology.current.RiverCurrents;
import net.quiwh.cwiworldgen.hydrology.current.RuntimeCurrentSampler;

/** Client-only, budgeted water audio over synchronized fields and actual loaded water. */
@EventBusSubscriber(modid = "cwi_worldgen", bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class ClientRiverEffects {
    private ClientRiverEffects() {}
    private static ClientLevel currentLevel;
    private static int scanCursor, ticks;
    private static final int SCANS_PER_TICK = 64;
    private static final int RADIUS = 28;
    private static final LinkedHashMap<Long, WaterSite> sites = new LinkedHashMap<>();
    private static final List<RiverWaterSound> sounds = new ArrayList<>();
    private record WaterSite(BlockPos crest, Kind kind, int seen) {}

    @SubscribeEvent public static void setup(FMLClientSetupEvent event) {
        event.enqueueWork(() -> {
            RiverCurrents.installClientReceiver(ClientRiverEffects::receive);
            RiverCurrents.installClientPolicyReceiver(ClientRiverEffects::clear);
            NeoForge.EVENT_BUS.addListener(ClientRiverEffects::tick);
            NeoForge.EVENT_BUS.addListener(ClientRiverEffects::logout);
            NeoForge.EVENT_BUS.addListener(ClientRiverEffects::unload);
        });
    }

    private static boolean enterLevel() {
        ClientLevel level = Minecraft.getInstance().level;
        if (currentLevel != level) {
            clear();
            currentLevel = level;
            if (level != null) RuntimeCurrentSampler.CLIENT_FIELDS.enter(level.dimension().location().toString());
        }
        return level != null;
    }

    private static void receive(CurrentFieldPayload packet) {
        if (!enterLevel()) return;
        RuntimeCurrentSampler.CLIENT_FIELDS.apply(packet.dimension().toString(), packet.generation(),
                packet.revision(), packet.chunkPos(), packet.field());
    }

    private static void logout(ClientPlayerNetworkEvent.LoggingOut event) { clear(); RiverCurrents.clearClientPolicy(); }
    private static void unload(LevelEvent.Unload event) {
        if (event.getLevel() == currentLevel) clear();
    }
    public static void clear() {
        for (RiverWaterSound sound : sounds) sound.finish();
        sounds.clear();
        sites.clear();
        RuntimeCurrentSampler.CLIENT_FIELDS.clear();
        currentLevel = null;
        scanCursor = ticks = 0;
    }

    private static void tick(ClientTickEvent.Post event) {
        if (!enterLevel()) return;
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.isPaused() || minecraft.player == null) return;
        ticks++;
        if (!RiverCurrents.clientPolicy().enabled() || (!audioEnabled(Kind.WATERFALL) && !audioEnabled(Kind.STREAM))) {
            sites.clear();
            for (RiverWaterSound sound : sounds) sound.finish();
            sounds.clear();
            return;
        }
        int playerChunkX = minecraft.player.getBlockX() >> 4;
        int playerChunkZ = minecraft.player.getBlockZ() >> 4;
        // A fixed 5x5-chunk window and fixed number of column lookups; absent chunks remain absent.
        for (int sample = 0; sample < SCANS_PER_TICK; sample++) {
            int index = scanCursor;
            scanCursor = (scanCursor + 1) % (25 * 256);
            int chunkIndex = index / 256, local = index & 255;
            int chunkX = playerChunkX + chunkIndex % 5 - 2;
            int chunkZ = playerChunkZ + chunkIndex / 5 - 2;
            var field = RuntimeCurrentSampler.CLIENT_FIELDS.get(ChunkPos.asLong(chunkX, chunkZ));
            if (field == null) continue;
            for (int layer = 0; layer < field.layerCount(); layer++) {
                CurrentColumn candidate = CurrentColumn.unpack(field.packed(layer, local));
                if (!candidate.plannedWater()) continue;
                BlockPos crest = new BlockPos(chunkX * 16 + (local & 15), candidate.maxY(), chunkZ * 16 + (local >> 4));
                if (minecraft.player.distanceToSqr(crest.getX() + .5, crest.getY() + .5, crest.getZ() + .5) > RADIUS * RADIUS) continue;
                // Select the final Y layer, rather than retaining hidden natural drop metadata.
                CurrentColumn column = exposedColumn(currentLevel, crest);
                Kind kind = CurrentPresentation.kind(column);
                if (kind == Kind.NONE) continue;
                remember(new WaterSite(crest, kind, ticks));
            }
        }
        var iterator = sites.entrySet().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            WaterSite site = entry.getValue();
            CurrentColumn column = exposedColumn(currentLevel, site.crest());
            Kind kind = CurrentPresentation.kind(column);
            if (ticks - site.seen() > 120 || kind == Kind.NONE
                    || minecraft.player.distanceToSqr(site.crest().getX() + .5, site.crest().getY() + .5,
                            site.crest().getZ() + .5) > 32 * 32) iterator.remove();
            else entry.setValue(new WaterSite(site.crest(), kind, site.seen()));
        }
        if (ticks % 20 == 0) updateSounds();
    }

    private static void remember(WaterSite site) {
        sites.put(site.crest().asLong(), site);
        while (sites.size() > 64) {
            // Keep waterfall sites available when a broad gentle surface fills the shared cache.
            Long victim = sites.entrySet().stream().filter(entry -> entry.getValue().kind() == Kind.STREAM)
                    .map(java.util.Map.Entry::getKey).findFirst().orElse(sites.keySet().iterator().next());
            sites.remove(victim);
        }
    }

    public static boolean wetDrop(ClientLevel level, BlockPos crest) { return wetSite(level, crest, Kind.WATERFALL); }
    public static boolean wetSite(ClientLevel level, BlockPos crest, Kind kind) {
        return kind != Kind.NONE && CurrentPresentation.kind(exposedColumn(level, crest)) == kind;
    }
    private static CurrentColumn exposedColumn(ClientLevel level, BlockPos crest) {
        if (level == null || !level.dimension().location().toString().equals(RuntimeCurrentSampler.CLIENT_FIELDS.dimension())) return CurrentColumn.EMPTY;
        var field = RuntimeCurrentSampler.CLIENT_FIELDS.get(ChunkPos.asLong(crest.getX() >> 4, crest.getZ() >> 4));
        var chunk = RuntimeCurrentSampler.loadedChunk(level, crest.getX() >> 4, crest.getZ() >> 4);
        if (chunk == null || !chunk.getBlockState(crest.above()).isAir()) return CurrentColumn.EMPTY;
        var column = RuntimeCurrentSampler.sample(field, chunk, crest);
        return CurrentPresentation.kind(column) == Kind.NONE ? CurrentColumn.EMPTY : column;
    }

    private static void updateSounds() {
        sounds.removeIf(RiverWaterSound::isStopped);
        sounds.removeIf(sound -> {
            if (audioEnabled(sound.kind()) && wetSite(currentLevel, sound.crest(), sound.kind())) return false;
            sound.finish();return true;
        });
        int limit = CurrentPresentation.voices(CurrentConfig.VOICES.get(), audioEnabled(Kind.WATERFALL), audioEnabled(Kind.STREAM));
        while (sounds.size() > limit) sounds.remove(sounds.size() - 1).finish();
        var player = Minecraft.getInstance().player;
        if (player == null) return;
        var nearest = sites.values().stream().filter(site -> audioEnabled(site.kind()))
                .sorted(Comparator.comparingInt((WaterSite site) -> site.kind() == Kind.WATERFALL ? 0 : 1)
                        .thenComparingDouble(site -> player.distanceToSqr(site.crest().getX(), site.crest().getY(), site.crest().getZ()))).toList();
        for (WaterSite site : nearest) {
            if (limit == 0) break;
            if (player.distanceToSqr(site.crest().getX(), site.crest().getY(), site.crest().getZ())
                    > (site.kind() == Kind.STREAM ? 18 * 18 : 32 * 32)) continue;
            if (site.kind() == Kind.WATERFALL) sounds.removeIf(sound -> {
                if (sound.kind() != Kind.STREAM || sound.crest().distSqr(site.crest()) >= 12 * 12) return false;
                sound.finish();return true;
            });
            if (sounds.stream().anyMatch(sound -> sound.crest().distSqr(site.crest()) < 12 * 12)) continue;
            if (sounds.size() >= limit) continue;
            RiverWaterSound sound = new RiverWaterSound(currentLevel, site.crest(), site.kind());
            sounds.add(sound);
            Minecraft.getInstance().getSoundManager().play(sound);
        }
    }
    static boolean audioEnabled(Kind kind) {
        return kind == Kind.WATERFALL ? CurrentConfig.AUDIO.get() && CurrentConfig.VOLUME.get() > 0
                : kind == Kind.STREAM && CurrentConfig.STREAM_AUDIO.get() && CurrentConfig.STREAM_VOLUME.get() > 0;
    }
}
