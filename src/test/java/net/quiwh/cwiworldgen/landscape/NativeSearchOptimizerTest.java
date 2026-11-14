package net.quiwh.cwiworldgen.landscape;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.authlib.GameProfile;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CompletableFuture;
import net.minecraft.server.level.ChunkResult;
import net.minecraft.world.level.chunk.ChunkAccess;
import java.util.regex.Pattern;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.QuartPos;
import net.minecraft.core.Holder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureCheck;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.pieces.PiecesContainer;
import net.minecraft.world.level.levelgen.structure.placement.RandomSpreadStructurePlacement;
import net.minecraft.world.level.levelgen.structure.structures.BuriedTreasurePieces;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.scores.ScoreHolder;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.event.OnDatapackSyncEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.quiwh.cwiworldgen.CwiWorldgen;
import net.quiwh.cwiworldgen.biome.BoundedCaveBiomeSource;
import net.quiwh.cwiworldgen.terrain.TerrainRandomState;

/** Exact-distribution command checks, run only in a disposable native fixture. */
@GameTestHolder(CwiWorldgen.ID)
@PrefixGameTestTemplate(false)
public final class NativeSearchOptimizerTest {
    private static boolean lifecycleFinished;
    private static final Pattern JOB = Pattern.compile("(?i)Search #(\\d+)");
    private static final Pattern FOUND = Pattern.compile("Found (.+) at \\[(-?\\d+), (-?\\d+), (-?\\d+)\\] \\((\\d+) blocks away\\)\\.");

    @GameTest(template = "empty", templateNamespace = "cwi_worldgen", timeoutTicks = 100)
    public static void simplifiedGrammarKeepsVanillaCommands(GameTestHelper helper) throws Exception {
        var source = source(level(helper), new Sink(), Vec3.ZERO);
        var dispatcher = source.getServer().getCommands().getDispatcher();
        var locate = dispatcher.getRoot().getChild("locate");
        for (String branch : new String[]{"structure", "biome", "poi", "landform"})
            helper.assertTrue(locate != null && locate.getChild(branch) != null,
                    "Missing locate branch: " + branch);
        for (String branch : new String[]{"background", "status", "cancel", "result"})
            helper.assertTrue(locate.getChild(branch) == null, "Retired locate branch is still advertised: " + branch);
        String[] commands = {"locate biome cwi:forsaken_land", "locate biome #minecraft:is_ocean",
                "locate structure minecraft:village_plains", "locate structure #minecraft:village",
                "locate biome cwi:forsaken_land 64", "locate biome #minecraft:is_ocean 8192",
                "locate structure minecraft:village_plains 64", "locate structure #minecraft:village 8192",
                "locate poi minecraft:home", "locate landform river", "locate landform cwi_worldgen:waterfall 8192",
                "locate landform river 8193", "locate landform river 32768",
                "locate biome cwi:forsaken_land 8193", "locate biome #minecraft:is_ocean 32768",
                "locate structure minecraft:village_plains 8193", "locate structure #minecraft:village 32768",
                "cwiworldgen landform list", "cwiworldgen landform here"};
        for (String command : commands) {
            var parsed = dispatcher.parse(command, source);
            helper.assertTrue(!parsed.getReader().canRead() && parsed.getExceptions().isEmpty()
                            && parsed.getContext().getCommand() != null,
                    "Command fails to parse: " + command + " " + parsed.getExceptions());
        }
        for (String command : new String[]{"locate background biome cwi:forsaken_land", "locate status", "locate cancel",
                "locate result 1", "cwiworldgen landform status", "cwiworldgen landform cancel",
                "locate landform river 63", "locate landform river 32769", "locate biome cwi:forsaken_land 63",
                "locate biome cwi:forsaken_land 32769", "locate structure #minecraft:village 63",
                "locate structure #minecraft:village 32769"}) {
            var parsed = dispatcher.parse(command, source);
            helper.assertTrue(parsed.getReader().canRead() || !parsed.getExceptions().isEmpty()
                            || parsed.getContext().getCommand() == null,
                    "Retired or out-of-bounds command still parses: " + command);
        }
        for (int permission : new int[]{0, 1}) for (String command : commands)
            helper.assertTrue(dispatcher.parse(command, source.withPermission(permission)).getReader().canRead(),
                    "Permission-" + permission + " source can access operator command: " + command);
        var landforms = dispatcher.getCompletionSuggestions(dispatcher.parse("locate landform ", source)).join();
        helper.assertTrue(landforms.getList().stream().anyMatch(s -> s.getText().equals("cwi_worldgen:waterfall")),
                "Landform completion is missing");
        var biomes = dispatcher.getCompletionSuggestions(dispatcher.parse("locate biome cwi:", source)).join();
        helper.assertTrue(biomes.getList().stream().anyMatch(s -> s.getText().equals("cwi:forsaken_land")),
                "Biome completion is missing");
        helper.assertTrue(dispatcher.getRoot().getChild("cwiworldgen").getChild("locate") == null,
                "Retired nested locate command was restored");
        System.out.println("CWI_SEARCH_GRAMMAR=vanilla:true,simplified:true,retired_absent:true,tags:true,permissions:true,completion:true,radius:true,max32768:true,above_max_rejected:true");
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "cwi_worldgen", timeoutTicks = 60000)
    public static void asynchronousResultsPublishAutomaticallyAndRetireSafely(GameTestHelper helper) throws Exception {
        ServerLevel level = level(helper);
        Sink sink = new Sink();
        var terrain = ((TerrainRandomState) (Object) level.getChunkSource().randomState()).cwiWorldgen$terrain();
        helper.assertTrue(terrain != null && terrain.generationVersion() == 21,
                "Search fixture must use the current saved terrain profile");
        var survey = new LandscapeSurvey(terrain, () -> false);
        LandscapeSearch.Candidate river = null;
        for (int z = -3888; z < -3872 && river == null; z++) for (int x = 1632; x < 1648; x++) {
            river = survey.find(LandscapeType.RIVER, x, z);
            if (river != null) break;
        }
        helper.assertTrue(river != null, "Known profile-21 wet river fixture is missing");
        Vec3 position = new Vec3(river.x(), 100, river.z());
        var source = source(level, sink, position);
        String surfaceBiome = biome(level, position);
        int chunks = level.getChunkSource().getLoadedChunksCount();
        helper.assertTrue(execute(source, "locate landform river") == 1,
                "Asynchronous landform command must return accepted-start value 1");
        helper.assertTrue(sink.containsSince("8192 blocks", 0), "Landform default range is not 8192 blocks");
        int[] job = {sink.latestJob()};
        helper.assertTrue(job[0] > 0, "Search did not advertise a numeric job ID: " + sink.messages);
        checkLandformHeartbeat(helper, source, sink, job[0]);
        reject(helper, source, "locate biome " + surfaceBiome, "A second search bypassed the per-owner bound");
        int[] phase = {0};
        int[] messagesBeforeQuery = {0};
        long[] cancelledAt = {0};
        List<Integer> retiredJobs = new ArrayList<>();
        long started = System.nanoTime();
        CommandSourceStack caveSource = source(level, sink, new Vec3(position.x, -32, position.z));
        String caveBiome = biome(level, caveSource.getPosition());
        ServerLevel flat = helper.getLevel();
        helper.assertTrue(!(flat.getChunkSource().getGenerator().getBiomeSource() instanceof BoundedCaveBiomeSource),
                "Generic-biome regression must use a non-CWI source");
        CommandSourceStack genericSource = source(flat, sink, new Vec3(0, 500, 0));
        String genericBiome = biome(flat, genericSource.getPosition());
        int genericChunks = flat.getChunkSource().getLoadedChunksCount();
        CommandSourceStack unloadSource = source(level, sink, position.add(64, 0, 0));
        String unloadBiome = biome(level, unloadSource.getPosition());
        helper.succeedWhen(() -> {
            helper.assertTrue(System.nanoTime() - started < TimeUnit.SECONDS.toNanos(60),
                    "Search lifecycle exceeded 60 wall-clock seconds: " + sink.messages);
            if (phase[0] == 0) {
                var found = awaitFound(sink, job[0]);
                helper.assertTrue(found.title.equals("River") && survey.find(LandscapeType.RIVER, found.x, found.z) != null,
                        "Search returned a dry or rejected river candidate");
                helper.assertTrue(found.distance == (int) Math.round(Math.hypot(found.x - position.x, found.z - position.z)),
                        "Displayed landform distance is not the horizontal distance");
                helper.assertTrue(level.getChunkSource().getLoadedChunksCount() == chunks,
                        "Landform search loaded distant Minecraft chunks");
                execute(source, "scoreboard objectives add cwi_search dummy");
                messagesBeforeQuery[0] = sink.messages.size();
                source.getServer().getCommands().performPrefixedCommand(source,
                        "execute store result score #biome cwi_search run locate biome " + surfaceBiome);
                var scoreboard = source.getServer().getScoreboard();
                var score = scoreboard.getPlayerScoreInfo(ScoreHolder.forNameOnly("#biome"), scoreboard.getObjective("cwi_search"));
                helper.assertTrue(score != null && score.value() == 1,
                        "Biome command must expose accepted-start value 1 to execute store");
                helper.assertTrue(sink.containsSince("8192 blocks", messagesBeforeQuery[0]),
                        "Biome default range is not 8192 blocks");
                job[0] = sink.latestJob();
                helper.assertTrue(sink.found(job[0]) == null,
                        "Fresh biome command completed synchronously instead of yielding to later ticks");
                ageSearchBeyondFormerDeadline(source, job[0]);
                reject(helper, source, "locate biome " + surfaceBiome, "Pending biome search did not retain its owner bound");
                phase[0] = 1;
            }
            if (phase[0] == 1) {
                helper.assertTrue(awaitFound(sink, job[0]).distance == 0, "Biome search differs at its matching origin");
                int previous = job[0];
                messagesBeforeQuery[0] = sink.messages.size();
                helper.assertTrue(execute(source, "locate biome " + surfaceBiome) == 1, "Cached search changed command return");
                job[0] = sink.latestJob();
                helper.assertTrue(job[0] > previous, "Repeating a query reused a retired job ID");
                phase[0] = 2;
            }
            if (phase[0] == 2) {
                helper.assertTrue(awaitFound(sink, job[0]).distance == 0, "Cached biome query changed its result");
                helper.assertTrue(sink.containsSince("cached", messagesBeforeQuery[0]),
                        "Identical completed biome query did not use the bounded result cache");
                int previous = job[0];
                // Cancel an actual pending task, before another server tick can finish it.
                execute(caveSource, "locate biome " + caveBiome + " 64");
                int reloaded = sink.latestJob();
                NeoForge.EVENT_BUS.post(new OnDatapackSyncEvent(level.getServer().getPlayerList(), null));
                helper.assertTrue(sink.terminal(reloaded) != null && sink.terminal(reloaded).contains("Cancelled"),
                        "Reload silently abandoned a pending search: " + sink.messages);
                retiredJobs.add(reloaded);
                messagesBeforeQuery[0] = sink.messages.size();
                execute(source, "locate biome " + surfaceBiome);
                job[0] = sink.latestJob();
                helper.assertTrue(job[0] > previous, "Datapack reload reused a retired job ID");
                phase[0] = 3;
            }
            if (phase[0] == 3) {
                helper.assertTrue(awaitFound(sink, job[0]).distance == 0, "Fresh query after reload changed its result");
                helper.assertTrue(!sink.containsSince("cached", messagesBeforeQuery[0]),
                        "Datapack reload failed to invalidate the result cache");
                execute(caveSource, "locate biome " + caveBiome + " 64");
                job[0] = sink.latestJob();
                phase[0] = 4;
            }
            if (phase[0] == 4) {
                helper.assertTrue(awaitFound(sink, job[0]).distance == 0, "Biome search ignored the complete cave wrapper");
                helper.assertTrue(execute(genericSource, "locate biome " + genericBiome) == 1,
                        "Non-CWI dimension lost asynchronous biome locate support");
                job[0] = sink.latestJob();
                phase[0] = 5;
            }
            if (phase[0] == 5) {
                helper.assertTrue(awaitFound(sink, job[0]).distance == 0, "Generic biome source changed its matching-origin result");
                helper.assertTrue(flat.getChunkSource().getLoadedChunksCount() == genericChunks,
                        "Generic biome search loaded distant Minecraft chunks");
                retiredJobs.addAll(checkPlayerRetirement(helper, source, sink));
                execute(unloadSource, "locate biome " + unloadBiome);
                int unloaded = sink.latestJob();
                retiredJobs.add(unloaded);
                NeoForge.EVENT_BUS.post(new LevelEvent.Unload(level));
                helper.assertTrue(sink.terminal(unloaded) != null && sink.terminal(unloaded).contains("Cancelled"),
                        "Level unload silently abandoned a pending search");
                execute(unloadSource, "locate biome " + unloadBiome);
                job[0] = sink.latestJob();
                cancelledAt[0] = helper.getTick();
                phase[0] = 6;
            }
            if (phase[0] == 6) {
                helper.assertTrue(awaitFound(sink, job[0]).distance == 0, "World unload did not release pending search ownership");
                helper.assertTrue(helper.getTick() - cancelledAt[0] >= 10, "Waiting for stale-result rejection");
                for (int retired : retiredJobs) helper.assertTrue(sink.found(retired) == null,
                        "Retired search published a late successful result: " + retired);
                helper.assertTrue(level.getChunkSource().getLoadedChunksCount() == chunks,
                        "Biome search loaded distant Minecraft chunks");
                System.out.println("CWI_SEARCH_NATIVE=river:true,surface:true,cave:true,generic:true,automatic:true,start_result:true,"
                        + "ownership:true,players:true,logout:true,dimension:true,pending:true,duplicate:true,cache:true,reload:true,unload:true,no_chunks:true,millis:"
                        + (System.nanoTime() - started) / 1_000_000);
                lifecycleFinished = true;
            }
        });
    }

    @GameTest(template = "empty", templateNamespace = "cwi_worldgen", timeoutTicks = 60000)
    public static void structureRangeUsesBlockRadiusAndPublishesAutomatically(GameTestHelper helper) throws Exception {
        ServerLevel level = helper.getLevel();
        Sink sink = new Sink();
        var structures = level.registryAccess().registryOrThrow(Registries.STRUCTURE);
        Holder<Structure> village = structures.getHolderOrThrow(ResourceKey.create(Registries.STRUCTURE,
                ResourceLocation.parse("minecraft:village_plains")));
        var state = level.getChunkSource().getGeneratorState();
        List<RandomSpreadStructurePlacement> placements = state.getPlacementsForStructure(village).stream()
                .filter(RandomSpreadStructurePlacement.class::isInstance)
                .map(RandomSpreadStructurePlacement.class::cast).toList();
        helper.assertTrue(placements.size() == 1, "Flat fixture must expose one native village placement");
        var placement = placements.getFirst();
        ChunkPos candidate = placement.getPotentialStructureChunk(state.getLevelSeed(), 0, 0);
        BlockPos expected = placement.getLocatePos(candidate);
        // Construct native loaded-start metadata only. No blocks or chunks are generated by this fixture.
        var start = new StructureStart(village.value(), candidate, 0, new PiecesContainer(List.of(
                new BuriedTreasurePieces.BuriedTreasurePiece(expected))));
        var checkField = StructureManager.class.getDeclaredField("structureCheck");
        checkField.setAccessible(true);
        ((StructureCheck) checkField.get(level.structureManager())).onStructureLoad(candidate, Map.of(village.value(), start));
        int[] direction = directionWithoutOtherVillage(placement, state.getLevelSeed(), candidate, expected);
        helper.assertTrue(direction != null, "Could not isolate the village radius-boundary fixture");
        Vec3 position = new Vec3(expected.getX(), 100, expected.getZ());
        var source = source(level, sink, position);
        var parsed = source.getServer().getCommands().getDispatcher()
                .parse("locate structure minecraft:village_plains 64", source);
        helper.assertTrue(!parsed.getReader().canRead() && parsed.getExceptions().isEmpty()
                        && parsed.getContext().getCommand() != null,
                "Structure optional block range is not registered");
        var boundarySource = source.withPosition(position.add(direction[0] * 64, 0, direction[1] * 64));
        var outsideSource = source.withPosition(position.add(direction[0] * 65, 0, direction[1] * 65));
        ChunkPos coldCandidate = placement.getPotentialStructureChunk(state.getLevelSeed(),
                placement.spacing() * 64, placement.spacing() * 64);
        BlockPos coldExpected = placement.getLocatePos(coldCandidate);
        var coldSource = source.withPosition(new Vec3(coldExpected.getX(), 100, coldExpected.getZ()));
        helper.assertTrue(level.getChunkSource().getChunk(coldCandidate.x, coldCandidate.z,
                ChunkStatus.STRUCTURE_STARTS, false) == null, "Cold structure candidate already has loaded chunk metadata");
        int chunks = level.getChunkSource().getLoadedChunksCount();
        int[] job = {-1};
        int[] phase = {-1};
        Object[] coldQuery = {null};
        CompletableFuture<?>[] coldFuture = {null};
        helper.succeedWhen(() -> {
            // The lifecycle fixture deliberately resets every search service on this server.
            // Start our query after it finishes, so those intentional resets cannot cancel it.
            helper.assertTrue(lifecycleFinished, "Waiting for shared-server lifecycle fixture cleanup");
            if (phase[0] == -1) {
                helper.assertTrue(execute(source, "locate structure minecraft:village_plains") == 1,
                        "Structure command must return accepted-start value 1");
                helper.assertTrue(sink.containsSince("8192 blocks", 0), "Structure default range is not 8192 blocks");
                job[0] = sink.latestJob();
                helper.assertTrue(sink.found(job[0]) == null, "Fresh structure command completed synchronously");
                ageSearchBeyondFormerDeadline(source, job[0]);
                phase[0] = 0;
            }
            if (phase[0] == 0) {
                Found found = awaitFound(sink, job[0]);
                helper.assertTrue(found.x == expected.getX() && found.z == expected.getZ() && found.distance == 0,
                        "Default structure query changed its confirmed origin anchor");
                execute(boundarySource, "locate structure minecraft:village_plains 64");
                job[0] = sink.latestJob();
                phase[0] = 1;
            }
            if (phase[0] == 1) {
                Found found = awaitFound(sink, job[0]);
                helper.assertTrue(found.x == expected.getX() && found.z == expected.getZ() && found.distance == 64,
                        "Structure radius did not include its exact 64-block boundary");
                execute(outsideSource, "locate structure minecraft:village_plains 64");
                job[0] = sink.latestJob();
                phase[0] = 2;
            }
            if (phase[0] == 2) {
                String completed = sink.terminal(job[0]);
                helper.assertTrue(completed != null, "Waiting for out-of-range structure absence reply");
                helper.assertTrue(sink.found(job[0]) == null && completed.contains("No "),
                        "Structure search returned an outside-radius candidate or failed: " + completed);
                helper.assertTrue(level.getChunkSource().getLoadedChunksCount() == chunks,
                        "Loaded native structure metadata required distant chunk loading");
                helper.assertTrue(execute(coldSource, "locate structure minecraft:village_plains 64") == 1,
                        "Cold structure command must return accepted-start value 1");
                job[0] = sink.latestJob();
                helper.assertTrue(sink.terminal(job[0]) == null, "Cold structure query completed synchronously");
                coldQuery[0] = searchField(searchTask(coldSource, job[0]), "structureQuery");
                phase[0] = 3;
            }
            if (phase[0] == 3) {
                // Keep the genuine future even when normal completion releases the chunk ticket.
                if (coldFuture[0] == null)
                    coldFuture[0] = (CompletableFuture<?>) searchField(coldQuery[0], "pending");
                String completed = sink.terminal(job[0]);
                helper.assertTrue(completed != null, "Waiting for cold structure candidate future");
                helper.assertTrue(!completed.contains("failed") && !completed.contains("Failed"),
                        "Cold structure future failed: " + completed);
                helper.assertTrue(coldFuture[0] != null && coldFuture[0].isDone(),
                        "Cold query did not expose a completed native structure chunk future");
                Object value = coldFuture[0].getNow(null);
                helper.assertTrue(value instanceof ChunkResult<?> && ((ChunkResult<?>) value).isSuccess(),
                        "Cold native structure future did not complete successfully");
                Object loaded = ((ChunkResult<?>) value).orElse(null);
                helper.assertTrue(loaded instanceof ChunkAccess, "Cold native future did not return chunk access");
                var chunk = (ChunkAccess) loaded;
                helper.assertTrue(chunk.getPersistedStatus().isOrAfter(ChunkStatus.STRUCTURE_STARTS),
                        "Cold candidate did not exercise the asynchronous structure-start chunk request");
                helper.assertTrue(searchField(coldQuery[0], "ticket") == null,
                        "Completed structure query did not release its native chunk ticket");
                Found found = sink.found(job[0]);
                var actualStart = chunk.getStartForStructure(village.value());
                boolean present = actualStart != null && actualStart.isValid();
                helper.assertTrue((found != null) == present, "Cold result differs from the actual native structure start");
                if (found != null) helper.assertTrue(found.x == coldExpected.getX() && found.z == coldExpected.getZ(),
                        "Cold structure query returned another placement anchor");
                else helper.assertTrue(completed.contains("No "), "Cold absence reply was not a completed search: " + completed);
                System.out.println("CWI_STRUCTURE_RANGE=default8192:true,explicit64:true,inclusive_boundary:true,"
                        + "outside_excluded:true,async:true,no_deadline:true,metadata_fixture:true,metadata_no_chunks:true,cold_future:true");
            }
        });
    }

    private static int[] directionWithoutOtherVillage(RandomSpreadStructurePlacement placement, long seed,
            ChunkPos candidate, BlockPos expected) {
        for (int[] direction : new int[][]{{1,0},{-1,0},{0,1},{0,-1}}) {
            long originX = expected.getX() + direction[0] * 65L;
            long originZ = expected.getZ() + direction[1] * 65L;
            boolean isolated = true;
            int cx = Math.floorDiv(candidate.x, placement.spacing());
            int cz = Math.floorDiv(candidate.z, placement.spacing());
            for (int x = cx - 2; x <= cx + 2; x++) for (int z = cz - 2; z <= cz + 2; z++) {
                BlockPos pos = placement.getLocatePos(placement.getPotentialStructureChunk(seed,
                        x * placement.spacing(), z * placement.spacing()));
                long dx = pos.getX() - originX, dz = pos.getZ() - originZ;
                if (dx * dx + dz * dz <= 64L * 64) isolated = false;
            }
            if (isolated) return direction;
        }
        return null;
    }

    private static Object searchTask(CommandSourceStack source, int job) {
        try {
            var serversField = net.quiwh.cwiworldgen.search.SearchService.class.getDeclaredField("SERVERS");
            serversField.setAccessible(true);
            var services = (java.util.Map<?, ?>) serversField.get(null);
            Object service = services.get(source.getServer());
            return ((java.util.Map<?, ?>) searchField(service, "tasks")).get(job);
        } catch (ReflectiveOperationException | NullPointerException failure) {
            throw new GameTestAssertException("Could not inspect real pending search: " + failure);
        }
    }

    private static Object searchField(Object object, String name) {
        try {
            var field = object.getClass().getDeclaredField(name);
            field.setAccessible(true);
            return field.get(object);
        } catch (ReflectiveOperationException | NullPointerException failure) {
            throw new GameTestAssertException("Could not inspect search field " + name + ": " + failure);
        }
    }

    private static void ageSearchBeyondFormerDeadline(CommandSourceStack source, int job) {
        try {
            Object task = searchTask(source, job);
            var startedField = task.getClass().getDeclaredField("started");
            startedField.setAccessible(true);
            startedField.setLong(task, System.nanoTime() - TimeUnit.SECONDS.toNanos(31));
        } catch (ReflectiveOperationException | NullPointerException failure) {
            throw new GameTestAssertException("Could not age the real pending search for deadline regression: " + failure);
        }
    }

    private static List<Integer> checkPlayerRetirement(GameTestHelper helper, CommandSourceStack source, Sink sink) {
        // Fake players never join the real player list. These checks happen before the next tick.
        var first = FakePlayerFactory.get(source.getLevel(), new GameProfile(
                UUID.fromString("2d84d704-a61f-48ad-91f3-52664e2edab5"), "Search owner A"));
        var second = FakePlayerFactory.get(source.getLevel(), new GameProfile(
                UUID.fromString("c1746cd5-7a2d-48f3-8420-f8328643151b"), "Search owner B"));
        var firstSource = source.withPosition(source.getPosition().add(128, 0, 0)).withEntity(first);
        var secondSource = source.withPosition(firstSource.getPosition()).withEntity(second);
        String target = biome(source.getLevel(), firstSource.getPosition());
        List<Integer> jobs = new ArrayList<>();
        execute(firstSource, "locate biome " + target);
        jobs.add(sink.latestJob());
        reject(helper, firstSource, "locate biome " + target, "A player bypassed its active-job bound");
        execute(secondSource, "locate biome " + target);
        jobs.add(sink.latestJob());
        helper.assertTrue(jobs.get(1) > jobs.get(0), "Distinct player UUIDs shared one active-job identity");
        NeoForge.EVENT_BUS.post(new PlayerEvent.PlayerLoggedOutEvent(first));
        execute(firstSource, "locate biome " + target);
        jobs.add(sink.latestJob());
        helper.assertTrue(jobs.get(2) > jobs.get(1), "Logout did not release pending search ownership");
        NeoForge.EVENT_BUS.post(new PlayerEvent.PlayerChangedDimensionEvent(first, source.getLevel().dimension(), Level.NETHER));
        int dimensionJob = jobs.getLast();
        helper.assertTrue(sink.terminal(dimensionJob) != null && sink.terminal(dimensionJob).contains("Cancelled"),
                "Dimension change silently abandoned a pending search");
        execute(firstSource, "locate biome " + target);
        jobs.add(sink.latestJob());
        NeoForge.EVENT_BUS.post(new PlayerEvent.PlayerLoggedOutEvent(first));
        NeoForge.EVENT_BUS.post(new PlayerEvent.PlayerLoggedOutEvent(second));
        return jobs;
    }

    private record Found(String title, int x, int y, int z, int distance) {}

    private static void checkLandformHeartbeat(GameTestHelper helper, CommandSourceStack source, Sink sink, int job) {
        try {
            Object task = searchTask(source, job);
            var progress = task.getClass().getDeclaredField("lastProgress");
            progress.setAccessible(true);
            progress.setLong(task, System.nanoTime() - TimeUnit.SECONDS.toNanos(6));
            Object service = searchField(task, "service");
            var tick = service.getClass().getDeclaredMethod("tick");
            tick.setAccessible(true);
            int messages = sink.messages.size();
            tick.invoke(service);
            helper.assertTrue(sink.messages.size() == messages + 1
                            && sink.messages.getLast().contains("Searching for ")
                            && sink.messages.getLast().contains("candidates"),
                    "Landform-only service did not report live progress: " + sink.messages);
            helper.assertTrue(sink.terminal(job) == null,
                    "Heartbeat prematurely completed or timed out a landform query");
            tick.invoke(service);
            helper.assertTrue(sink.messages.size() == messages + 1, "Progress was not rate limited");
            System.out.println("CWI_SEARCH_HEARTBEAT=landform_only:true,rate_limited:true,no_deadline:true");
        } catch (ReflectiveOperationException failure) {
            throw new GameTestAssertException("Could not exercise actual search progress: " + failure);
        }
    }

    private static Found awaitFound(Sink sink, int job) {
        Found result = sink.found(job);
        if (result == null) {
            // GameTestServer ticks are unpaced; yield to the genuine terrain worker.
            java.util.concurrent.locks.LockSupport.parkNanos(1_000_000);
            throw new GameTestAssertException("Waiting for automatic search #" + job + " reply: " + sink.messages);
        }
        return result;
    }

    private static int execute(CommandSourceStack source, String command) {
        try { return source.getServer().getCommands().getDispatcher().execute(command, source); }
        catch (CommandSyntaxException failure) { throw new GameTestAssertException(command + ": " + failure.getMessage()); }
    }

    private static void reject(GameTestHelper helper, CommandSourceStack source, String command, String message) {
        try { source.getServer().getCommands().getDispatcher().execute(command, source); }
        catch (CommandSyntaxException expected) { return; }
        helper.fail(message + ": " + command);
    }

    private static String biome(ServerLevel level, Vec3 position) {
        return level.getNoiseBiome(QuartPos.fromBlock((int) position.x), QuartPos.fromBlock((int) position.y),
                QuartPos.fromBlock((int) position.z)).unwrapKey().orElseThrow().location().toString();
    }

    private static ServerLevel level(GameTestHelper helper) {
        ServerLevel level = helper.getLevel().getServer().getLevel(ResourceKey.create(Registries.DIMENSION,
                ResourceLocation.parse("cwi_worldgen:wasteland_test")));
        helper.assertTrue(level != null && level.getSeed() == 0, "Seed-0 disposable CWI fixture is missing");
        return level;
    }

    private static CommandSourceStack source(ServerLevel level, Sink sink, Vec3 position) {
        return new CommandSourceStack(sink, position, Vec2.ZERO, level, 2, "Search optimizer test",
                Component.literal("Search optimizer test"), level.getServer(), null);
    }

    private static final class Sink implements CommandSource {
        private final List<String> messages = new ArrayList<>();
        @Override public void sendSystemMessage(Component message) { messages.add(message.getString()); }
        @Override public boolean acceptsSuccess() { return true; }
        @Override public boolean acceptsFailure() { return true; }
        @Override public boolean shouldInformAdmins() { return false; }
        private int latestJob() {
            for (int index = messages.size() - 1; index >= 0; index--) {
                var match = JOB.matcher(messages.get(index));
                if (match.find()) return Integer.parseInt(match.group(1));
            }
            return -1;
        }
        private Found found(int job) {
            String prefix = "Search #" + job + ": ";
            String cachedPrefix = "Search #" + job + " (cached): ";
            for (String message : messages) if (message.startsWith(prefix) || message.startsWith(cachedPrefix)) {
                var match = FOUND.matcher(message);
                if (match.find()) return new Found(match.group(1), Integer.parseInt(match.group(2)),
                        Integer.parseInt(match.group(3)), Integer.parseInt(match.group(4)), Integer.parseInt(match.group(5)));
            }
            return null;
        }
        private String terminal(int job) {
            String prefix = "Search #" + job + ": ";
            String cachedPrefix = "Search #" + job + " (cached): ";
            return messages.stream().filter(message -> message.startsWith(prefix) || message.startsWith(cachedPrefix))
                    .filter(message -> !message.contains("Searching for ")).findFirst().orElse(null);
        }
        private boolean containsSince(String text, int first) {
            return messages.subList(first, messages.size()).stream()
                    .anyMatch(message -> message.toLowerCase(java.util.Locale.ROOT).contains(text));
        }
    }
}
