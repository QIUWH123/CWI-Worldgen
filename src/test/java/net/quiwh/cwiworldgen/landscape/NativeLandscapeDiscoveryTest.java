package net.quiwh.cwiworldgen.landscape;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.quiwh.cwiworldgen.CwiWorldgen;
import net.quiwh.cwiworldgen.terrain.TerrainRandomState;

/** Exercises registered commands and their asynchronous replies inside an isolated actual server. */
@GameTestHolder(CwiWorldgen.ID)
@PrefixGameTestTemplate(false)
public final class NativeLandscapeDiscoveryTest {
    @GameTest(template = "empty", templateNamespace = "cwi_worldgen", timeoutTicks = 60000)
    public static void locateRiverRepliesAsynchronously(GameTestHelper helper) throws Exception {
        ServerLevel level = level(helper);
        Sink sink = new Sink();
        var terrain = ((TerrainRandomState) (Object) level.getChunkSource().randomState()).cwiWorldgen$terrain();
        helper.assertTrue(terrain != null && terrain.generationVersion() == 21,
                "Native locate fixture must use the installed terrain profile");
        var survey = new LandscapeSurvey(terrain, () -> false);
        LandscapeSearch.Candidate river = null;
        for (int z = -3888; z < -3872 && river == null; z++) for (int x = 1632; x < 1648; x++) {
            river = survey.find(LandscapeType.RIVER, x, z);
            if (river != null) break;
        }
        helper.assertTrue(river != null, "Checked profile-21 river fixture has no wet river");
        String expected = "Found River at [" + river.x() + ", " + river.y() + ", " + river.z() + "]";
        var source = source(level, sink, new Vec3(river.x(), 100, river.z()));
        int result = level.getServer().getCommands().getDispatcher()
                .execute("locate landform cwi_worldgen:river 64", source);
        helper.assertTrue(result == 1 && sink.contains("Searching for River"), "Registered command did not enqueue search");
        var dispatcher = level.getServer().getCommands().getDispatcher();
        long started = System.nanoTime();
        helper.succeedWhen(() -> {
            helper.assertTrue(System.nanoTime() - started < java.util.concurrent.TimeUnit.SECONDS.toNanos(45),
                    "Landscape command did not complete within 45 wall-clock seconds");
            // GameTestServer ticks are unpaced. Yield real time to the actual background worker,
            // as in NativeAsyncCurrentTest, without changing production search limits or assertions.
            if (!sink.contains(expected))
                java.util.concurrent.locks.LockSupport.parkNanos(1_000_000);
            helper.assertTrue(sink.contains(expected),
                    "Asynchronous locate has not returned the native seed-0 river: " + sink.messages);
            if (!sink.contains("Inspecting landforms here")) {
                try { dispatcher.execute("cwiworldgen landform here", source); }
                catch (com.mojang.brigadier.exceptions.CommandSyntaxException failure) {
                    throw new net.minecraft.gametest.framework.GameTestAssertException(failure.getMessage());
                }
            }
            if (!sink.contains("Landforms here:") && !sink.contains("No named landform"))
                java.util.concurrent.locks.LockSupport.parkNanos(1_000_000);
            helper.assertTrue(sink.contains("Landforms here:") || sink.contains("No named landform"),
                    "Inspection has not returned");
            for (String retired : new String[]{"cwiworldgen landform cancel", "cwiworldgen landform status"}) {
                var parsed = dispatcher.parse(retired, source);
                helper.assertTrue(parsed.getReader().canRead() || !parsed.getExceptions().isEmpty()
                        || parsed.getContext().getCommand() == null, "Retired control still parses: " + retired);
            }
            System.out.println("CWI_LANDFORM_NATIVE=river:true,inspect:true,retired_controls_absent:true,millis:"
                    + (System.nanoTime() - started) / 1_000_000);
        });
    }

    @GameTest(template = "empty", templateNamespace = "cwi_worldgen", timeoutTicks = 100)
    public static void registeredGrammarAndPermissions(GameTestHelper helper) throws Exception {
        var source = source(level(helper), new Sink(), Vec3.ZERO);
        var dispatcher = source.getServer().getCommands().getDispatcher();
        var locate = dispatcher.getRoot().getChild("locate");
        for (String child : new String[]{"structure", "biome", "poi", "landform"})
            helper.assertTrue(locate != null && locate.getChild(child) != null,
                    "Minecraft locate branch is missing: " + child);
        for (String command : new String[]{"locate landform cwi_worldgen:river 2048",
                "locate landform waterfall", "locate structure minecraft:village_plains",
                "locate biome minecraft:plains", "locate poi minecraft:home", "cwiworldgen landform here", "cwiworldgen landform list"}) {
            var parsed = dispatcher.parse(command, source);
            helper.assertTrue(!parsed.getReader().canRead() && parsed.getExceptions().isEmpty()
                    && parsed.getContext().getCommand() != null, "Registered command fails to parse: " + command);
        }
        for (int permission : new int[]{0, 1}) for (String command : new String[]{
                "locate landform river", "locate biome minecraft:plains", "cwiworldgen landform list"})
            helper.assertTrue(dispatcher.parse(command, source.withPermission(permission)).getReader().canRead(),
                    "Permission-" + permission + " source can access operator command: " + command);
        var suggestions = dispatcher.getCompletionSuggestions(dispatcher.parse("locate landform ", source)).join();
        helper.assertTrue(suggestions.getList().stream().anyMatch(s -> s.getText().equals("cwi_worldgen:mountain_range")),
                "Landform completion is missing from Minecraft locate");
        helper.assertTrue(dispatcher.getRoot().getChild("cwiworldgen").getChild("locate") == null,
                "Old nested locate command is still advertised");
        System.out.println("CWI_LANDFORM_GRAMMAR=vanilla_branches:true,permissions:true,completion:true");
        helper.succeed();
    }

    private static ServerLevel level(GameTestHelper helper) {
        ServerLevel level = helper.getLevel().getServer().getLevel(ResourceKey.create(Registries.DIMENSION,
                ResourceLocation.parse("cwi_worldgen:wasteland_test")));
        helper.assertTrue(level != null && level.getSeed() == 0, "Native seed-0 CWI fixture dimension is missing");
        return level;
    }
    private static CommandSourceStack source(ServerLevel level, Sink sink, Vec3 position) {
        return new CommandSourceStack(sink, position, Vec2.ZERO, level, 2, "Landscape test",
                Component.literal("Landscape test"), level.getServer(), null);
    }
    private static final class Sink implements CommandSource {
        private final List<String> messages = new ArrayList<>();
        @Override public void sendSystemMessage(Component message) { messages.add(message.getString()); }
        @Override public boolean acceptsSuccess() { return true; }
        @Override public boolean acceptsFailure() { return true; }
        @Override public boolean shouldInformAdmins() { return false; }
        private boolean contains(String text) { return messages.stream().anyMatch(message -> message.contains(text)); }
    }
}
