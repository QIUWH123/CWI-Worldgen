package net.quiwh.cwiworldgen.search;

import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.DynamicCommandExceptionType;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.arguments.ResourceOrTagArgument;
import net.minecraft.commands.arguments.ResourceOrTagKeyArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderSet;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.OnDatapackSyncEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.quiwh.cwiworldgen.CwiWorldgen;
import net.quiwh.cwiworldgen.biome.CwiBiomeSearch;
import net.quiwh.cwiworldgen.landscape.LandscapeSearch;
import net.quiwh.cwiworldgen.landscape.LandscapeSurvey;
import net.quiwh.cwiworldgen.landscape.LandscapeType;
import net.quiwh.cwiworldgen.terrain.TerrainRandomState;
import net.quiwh.cwiworldgen.terrain.reference.ReferenceTerrain;

/** Owns operator searches; live biome sampling stays on the server thread. */
public final class SearchService {
    private static final DynamicCommandExceptionType ERROR = new DynamicCommandExceptionType(
            message -> Component.literal(message.toString()));
    private static final Map<MinecraftServer, SearchService> SERVERS = new IdentityHashMap<>();
    // Separate from WORLD_GEN tile workers: waiting on a terrain tile must not starve its producer.
    private static final ThreadPoolExecutor TERRAIN_WORKER = new ThreadPoolExecutor(1, 1, 30, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(4), work -> {
                Thread thread = new Thread(work, "CWI Search Terrain");
                thread.setDaemon(true);
                thread.setPriority(Thread.NORM_PRIORITY - 1);
                return thread;
            });
    private static final long TICK_SLICE = TimeUnit.MILLISECONDS.toNanos(2);
    private static final long PROGRESS_INTERVAL = TimeUnit.SECONDS.toNanos(5);
    private final SearchLedger<Owner, Outcome> ledger = new SearchLedger<>(5, 128);
    private final Map<Integer, Task> tasks = new LinkedHashMap<>();
    private final LinkedHashMap<CacheKey, Outcome> cache = new LinkedHashMap<>(64, .75F, true);
    private int searchTurn;

    private record Owner(UUID entity, String name, String dimension, BlockPos position) {}
    private record CacheKey(ServerLevel level, Object generator, String kind, String target, BlockPos origin, int radius) {}
    private record Outcome(Component response, BlockPos position, int distance, boolean cacheable) {}

    private SearchService() {}

    public static void registerEvents() {
        NeoForge.EVENT_BUS.addListener((ServerTickEvent.Post event) -> {
            SearchService service = SERVERS.get(event.getServer());
            if (service != null) service.tick();
        });
        NeoForge.EVENT_BUS.addListener((LevelEvent.Unload event) -> {
            if (event.getLevel() instanceof ServerLevel level) reset(level.getServer(), "a level was unloaded");
        });
        NeoForge.EVENT_BUS.addListener((ServerStoppingEvent event) -> {
            SearchService service = SERVERS.remove(event.getServer());
            if (service != null) service.clear(null);
        });
        NeoForge.EVENT_BUS.addListener((OnDatapackSyncEvent event) -> {
            if (event.getPlayer() == null) reset(event.getPlayerList().getServer(), "world data was reloaded");
        });
        NeoForge.EVENT_BUS.addListener((PlayerEvent.PlayerLoggedOutEvent event) -> {
            if (event.getEntity() instanceof ServerPlayer player) retire(player, null);
        });
        NeoForge.EVENT_BUS.addListener((PlayerEvent.PlayerChangedDimensionEvent event) -> {
            if (event.getEntity() instanceof ServerPlayer player) retire(player, "you changed dimension");
        });
    }

    private static SearchService service(CommandSourceStack source) {
        return SERVERS.computeIfAbsent(source.getServer(), ignored -> new SearchService());
    }

    private static Owner owner(CommandSourceStack source) {
        if (source.getEntity() != null) return new Owner(source.getEntity().getUUID(), "", "", BlockPos.ZERO);
        return new Owner(null, source.getTextName(), source.getLevel().dimension().location().toString(),
                BlockPos.containing(source.getPosition()));
    }

    public static int startLandform(CommandSourceStack source, LandscapeType type, int radius) throws CommandSyntaxException {
        ReferenceTerrain terrain = ((TerrainRandomState) (Object) source.getLevel().getChunkSource().randomState()).cwiWorldgen$terrain();
        if (terrain == null) throw ERROR.create("This dimension does not use CWI terrain");
        BlockPos origin = checkedOrigin(source);
        SearchService service = service(source);
        String target = type == null ? "inspection" : type.id();
        Task task = service.add(source, type == null ? "Landform inspection" : type.title(),
                new CacheKey(source.getLevel(), terrain, "landform", target, origin, radius));
        task.terrain = terrain;
        task.landform = type;
        reply(source, "Search #" + task.id + ": " + (type == null ? "Inspecting landforms here…"
                : "Searching for " + type.title() + " within " + radius + " blocks…"));
        if (!service.cached(task)) {
            try { TERRAIN_WORKER.execute(task); }
            catch (RejectedExecutionException busy) {
                service.cancelTask(task);
                throw ERROR.create("Terrain search queue is full; try again after another search finishes");
            }
        }
        return 1;
    }

    public static int startBiome(CommandSourceStack source, ResourceOrTagArgument.Result<Biome> target) throws CommandSyntaxException {
        return startBiome(source, target, LandscapeSearch.DEFAULT_RADIUS);
    }

    public static int startBiome(CommandSourceStack source, ResourceOrTagArgument.Result<Biome> target, int radius) throws CommandSyntaxException {
        var biomeSource = source.getLevel().getChunkSource().getGenerator().getBiomeSource();
        BlockPos origin = checkedOrigin(source);
        SearchService service = service(source);
        Task task = service.add(source, "Biome " + target.asPrintable(),
                new CacheKey(source.getLevel(), biomeSource, "biome", target.asPrintable(), origin, radius));
        reply(source, "Search #" + task.id + ": Searching for biome " + target.asPrintable() + " within " + radius + " blocks…");
        if (service.cached(task)) return 1;
        try {
            task.query = CwiBiomeSearch.createWithinRadius(biomeSource, origin, radius, 32, 64, target,
                    source.getLevel().getChunkSource().randomState().sampler(), source.getLevel().getMinBuildHeight(),
                    source.getLevel().getMaxBuildHeight(), task.cancelled::get);
        } catch (RuntimeException preparationFailure) {
            service.cancelTask(task);
            CwiWorldgen.LOGGER.error("Could not prepare biome search #" + task.id, preparationFailure);
            throw ERROR.create("Could not prepare biome search; see the server log");
        }
        return 1;
    }

    public static int startStructure(CommandSourceStack source, ResourceOrTagKeyArgument.Result<Structure> target,
            int radius) throws CommandSyntaxException {
        var registry = source.getLevel().registryAccess().registryOrThrow(Registries.STRUCTURE);
        HolderSet<Structure> targets = target.unwrap().map(
                key -> registry.getHolder(key).map(holder -> (HolderSet<Structure>) HolderSet.direct(holder)),
                key -> registry.getTag(key).map(holders -> (HolderSet<Structure>) holders))
                .orElseThrow(() -> ERROR.create("Unknown structure or tag: " + target.asPrintable()));
        BlockPos origin = checkedOrigin(source);
        var generator = source.getLevel().getChunkSource().getGenerator();
        SearchService service = service(source);
        Task task = service.add(source, "Structure " + target.asPrintable(),
                new CacheKey(source.getLevel(), generator, "structure", target.asPrintable(), origin, radius));
        reply(source, "Search #" + task.id + ": Searching for structure " + target.asPrintable()
                + " within " + radius + " blocks…");
        if (service.cached(task)) return 1;
        try {
            task.structureQuery = StructureSearch.create(source.getLevel(), targets, origin, radius, task.cancelled::get);
        } catch (RuntimeException preparationFailure) {
            service.cancelTask(task);
            CwiWorldgen.LOGGER.error("Could not prepare structure search #" + task.id, preparationFailure);
            throw ERROR.create(preparationFailure instanceof IllegalArgumentException
                    ? preparationFailure.getMessage() : "Could not prepare structure search; see the server log");
        }
        return 1;
    }

    private Task add(CommandSourceStack source, String title, CacheKey key) throws CommandSyntaxException {
        Owner owner = owner(source);
        int id;
        try { id = ledger.start(owner); }
        catch (IllegalStateException limit) { throw ERROR.create(limit.getMessage()); }
        Task task = new Task(this, id, owner, source, title, key);
        tasks.put(id, task);
        return task;
    }

    private boolean cached(Task task) {
        Outcome previous = cache.get(task.key);
        if (previous == null) return false;
        task.cached = true;
        task.source.getServer().execute(() -> publish(task, previous));
        return true;
    }

    private static BlockPos checkedOrigin(CommandSourceStack source) throws CommandSyntaxException {
        BlockPos origin = BlockPos.containing(source.getPosition());
        if (Math.abs((long) origin.getX()) > LandscapeSearch.MAX_COORDINATE
                || Math.abs((long) origin.getZ()) > LandscapeSearch.MAX_COORDINATE)
            throw ERROR.create("The search origin is outside Minecraft's terrain bounds");
        return origin;
    }

    private void tick() {
        long started = System.nanoTime();
        // Landform work runs separately, so report it before the tick-query early return.
        for (Task task : tasks.values()) {
            if (task.cached || task.cancelled.get() || started - task.lastProgress < PROGRESS_INTERVAL) continue;
            task.lastProgress = started;
            String state = task.query == null && task.structureQuery == null && !task.running ? "queued" : "running";
            Component message = Component.literal("Search #" + task.id + ": Searching for " + task.title
                    + " within " + task.key.radius + " blocks… " + task.samples.get() + " candidates considered (" + state + ").");
            if (task.source.getEntity() instanceof ServerPlayer player) {
                player.displayClientMessage(message, true);
                task.progressDisplayed = true;
            } else task.source.sendSuccess(() -> message, false);
        }
        var waiting = tasks.values().stream()
                .filter(task -> (task.query != null || task.structureQuery != null) && !task.cached).toList();
        if (waiting.isEmpty()) return;
        int first = Math.floorMod(searchTurn++, waiting.size());
        int count = 0;
        // One pass also prevents spinning on incomplete chunk/ring futures.
        while (count < waiting.size() && System.nanoTime() - started < TICK_SLICE) {
            Task task = waiting.get((first + count++) % waiting.size());
            if (!tasks.containsKey(task.id)) continue;
            if (!task.valid()) { cancelTask(task); continue; }
            long left = TICK_SLICE - (System.nanoTime() - started);
            if (left <= 0) break;
            try {
                boolean complete = task.query != null
                        ? task.query.step(256, Math.min(left, 500_000))
                        : task.structureQuery.step(256, Math.min(left, 500_000));
                long checked = task.query != null ? task.query.samples() : task.structureQuery.samples();
                task.samples.set((int) Math.min(Integer.MAX_VALUE, checked));
                if (!complete) continue;
                boolean cancelled = task.query != null ? task.query.cancelled() : task.structureQuery.cancelled();
                if (cancelled) { cancelTask(task); continue; }
                var found = task.query != null ? task.query.result() : task.structureQuery.result();
                String kind = task.query != null ? "biome" : "structure";
                if (found == null) {
                    // Structure presence can change through later world edits; never cache its absence.
                    publish(task, new Outcome(Component.literal("No matching " + kind + " was found within "
                            + task.key.radius + " blocks on the completed search."), null, 0, task.query != null));
                } else {
                    BlockPos pos = found.getFirst();
                    long dx = (long) pos.getX() - task.key.origin.getX(), dz = (long) pos.getZ() - task.key.origin.getZ();
                    int distance = (int) Math.floor(Math.sqrt((double) dx * dx + (double) dz * dz));
                    String name = found.getSecond().unwrapKey().map(key -> key.location().toString()).orElse(task.key.target);
                    publish(task, found(task, kind + " " + name, pos, distance));
                }
            } catch (RuntimeException failure) { failed(task, failure); }
        }
    }

    private void publish(Task task, Outcome outcome) {
        if (!task.valid()) { cancelTask(task); return; }
        if (!ledger.finish(task.id, outcome)) return;
        tasks.remove(task.id, task);
        task.clearProgress();
        if (task.structureQuery != null) task.structureQuery.close();
        if (outcome.cacheable && task.structureQuery == null) {
            cache.put(task.key, outcome);
            while (cache.size() > 64) cache.remove(cache.keySet().iterator().next());
        }
        Component message = Component.literal("Search #" + task.id + (task.cached ? " (cached): " : ": ")).append(outcome.response);
        task.source.sendSuccess(() -> message, false);
        CwiWorldgen.LOGGER.info("Search #{} {}: {} candidates, {} ms{}", task.id, task.title,
                task.query == null ? task.samples.get() : task.query.samples(),
                (System.nanoTime() - task.started) / 1_000_000, task.cached ? " (cached)" : "");
    }

    private static Outcome found(Task task, String title, BlockPos pos, int distance) {
        String dimension = task.key.level.dimension().location().toString();
        var coordinates = Component.literal("[" + pos.getX() + ", " + pos.getY() + ", " + pos.getZ() + "]")
                .withStyle(style -> style.withColor(ChatFormatting.GREEN)
                        .withClickEvent(new ClickEvent(ClickEvent.Action.SUGGEST_COMMAND, "/execute in " + dimension
                                + " run tp @s " + pos.getX() + " " + pos.getY() + " " + pos.getZ()))
                        .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.literal("Suggest teleport command"))));
        return new Outcome(Component.literal("Found " + title + " at ").append(coordinates)
                .append(" (" + distance + " blocks away)."), pos, distance, true);
    }

    private void failed(Task task, RuntimeException failure) {
        CwiWorldgen.LOGGER.error("Search #" + task.id + " failed", failure);
        publish(task, new Outcome(Component.literal("Search failed; see the server log."), null, 0, false));
    }

    private void cancelTask(Task task) {
        cancelTask(task, null);
    }

    private void cancelTask(Task task, String reason) {
        task.cancelled.set(true);
        TERRAIN_WORKER.remove(task);
        if (task.structureQuery != null) task.structureQuery.close();
        tasks.remove(task.id, task);
        boolean retired = ledger.cancel(task.id);
        task.clearProgress();
        if (retired && reason != null)
            reply(task.source, "Search #" + task.id + ": Cancelled because " + reason + ". Run the locate command again.");
    }

    private static void reset(MinecraftServer server, String reason) {
        SearchService service = SERVERS.get(server);
        if (service != null) service.clear(reason);
    }

    private static void retire(ServerPlayer player, String reason) {
        SearchService service = SERVERS.get(player.getServer());
        if (service == null) return;
        for (Task task : service.tasks.values().stream().filter(task -> player.getUUID().equals(task.owner.entity)).toList())
            service.cancelTask(task, reason);
        service.ledger.forget(new Owner(player.getUUID(), "", "", BlockPos.ZERO));
    }

    private void clear(String reason) {
        for (Task task : tasks.values().stream().toList()) cancelTask(task, reason);
        ledger.clear();
        cache.clear();
    }

    private static void reply(CommandSourceStack source, String message) {
        source.sendSuccess(() -> Component.literal(message), false);
    }

    private static final class Task implements Runnable {
        private final SearchService service;
        private final int id;
        private final Owner owner;
        private final CommandSourceStack source;
        private final ServerLevel playerLevel;
        private final String title;
        private final CacheKey key;
        private final long started = System.nanoTime();
        private long lastProgress = started;
        private final AtomicBoolean cancelled = new AtomicBoolean();
        private final AtomicInteger samples = new AtomicInteger();
        private ReferenceTerrain terrain;
        private LandscapeType landform;
        private CwiBiomeSearch.Query query;
        private StructureSearch.Query structureQuery;
        private boolean cached;
        private boolean progressDisplayed;
        private volatile boolean running;

        private Task(SearchService service, int id, Owner owner, CommandSourceStack source, String title, CacheKey key) {
            this.service = service; this.id = id; this.owner = owner; this.source = source; this.title = title; this.key = key;
            playerLevel = source.getEntity() instanceof ServerPlayer player ? player.serverLevel() : null;
        }

        private boolean valid() {
            if (cancelled.get() || source.getServer().getLevel(key.level.dimension()) != key.level) return false;
            if (source.getEntity() instanceof ServerPlayer player)
                return source.getServer().getPlayerList().getPlayer(player.getUUID()) == player && player.serverLevel() == playerLevel;
            return source.getEntity() == null || !source.getEntity().isRemoved();
        }

        private void clearProgress() {
            if (progressDisplayed && source.getEntity() instanceof ServerPlayer player) {
                player.displayClientMessage(Component.empty(), true);
                progressDisplayed = false;
            }
        }

        @Override public void run() {
            if (cancelled.get()) return;
            running = true;
            Outcome outcome;
            try {
                var survey = new LandscapeSurvey(terrain, cancelled::get);
                if (landform == null) {
                    var types = survey.here(key.origin.getX(), key.origin.getY(), key.origin.getZ());
                    outcome = new Outcome(Component.literal(types.isEmpty() ? "No named landform at this position."
                            : "Landforms here: " + types.stream().map(LandscapeType::title)
                                    .collect(java.util.stream.Collectors.joining(", "))), null, 0, true);
                } else {
                    var result = survey.search(landform, key.origin.getX(), key.origin.getZ(), key.radius,
                            Integer.MAX_VALUE, Long.MAX_VALUE, cancelled::get, samples::set);
                    var point = result.candidate();
                    if (point != null) {
                        BlockPos pos = new BlockPos(point.x(), point.y(), point.z());
                        int distance = (int) Math.round(Math.hypot((double) point.x() - key.origin.getX(), (double) point.z() - key.origin.getZ()));
                        outcome = found(this, landform.title(), pos, distance);
                    } else {
                        boolean completed = result.reason() == LandscapeSearch.Reason.EXHAUSTED;
                        String message = completed ? "No " + landform.title() + " found on the completed candidate search within " + key.radius
                                + " blocks. Sampled coverage can miss small landforms."
                                : "Search stopped before completion (" + result.reason().name().toLowerCase(java.util.Locale.ROOT)
                                        + "); absence has not been established.";
                        outcome = new Outcome(Component.literal(message), null, 0, completed);
                    }
                }
            } catch (RuntimeException failure) {
                CwiWorldgen.LOGGER.error("Search #" + id + " failed", failure);
                outcome = new Outcome(Component.literal("Search failed; see the server log."), null, 0, false);
            }
            if (cancelled.get()) return;
            Outcome reply = outcome;
            source.getServer().execute(() -> service.publish(this, reply));
        }
    }
}
