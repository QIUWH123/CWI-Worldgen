package net.quiwh.cwiworldgen.hydrology.current;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import java.util.List;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.LongArrayTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.UpgradeData;
import net.minecraft.world.level.saveddata.SavedData;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.quiwh.cwiworldgen.CwiWorldgen;

@GameTestHolder(CwiWorldgen.ID)
@PrefixGameTestTemplate(false)
public final class NativeAuthoredCurrentsTest {
    @GameTest(template = "empty", templateNamespace = "cwi_worldgen")
    public static void manualStillAndGeneratedPrecedenceSurvivePersistenceAtExactHeights(GameTestHelper helper) {
        var data = new CurrentOverrides(); int index = 8 * 16 + 8;
        long[] generated = new long[256], reflow = new long[256];
        generated[index] = CurrentColumn.of(80,100,1,0,.04,0,0).pack();
        reflow[index] = CurrentColumn.of(70,90,-1,0,.02,0,0).pack();
        data.replaceReflow(List.of(new CurrentChunk(0,reflow)));
        data.addPath("canal",8,0,8,16,85,90,2,.06);
        data.addStill("pond",8,8,87,88,2);
        CurrentChunk field = data.apply(new ChunkPos(0,0),new CurrentChunk(0,generated));
        helper.assertTrue(field.column(8,75,8).directionX() < -.99, "Lower reflow lost its own interval");
        helper.assertTrue(field.column(8,82,8).directionX() > .99, "Reflow overrode an original generated river");
        helper.assertTrue(field.column(8,86,8).directionZ() > .99 && field.column(8,89,8).directionZ() > .99, "Manual path lost precedence");
        helper.assertTrue(!field.column(8,87,8).active() && field.column(8,87,8).plannedWater(), "Still mask failed to suppress overlapping flow");
        helper.assertTrue(field.column(8,95,8).directionX() > .99 && !field.column(8,110,8).plannedWater(), "An override leaked beyond its vertical interval");
        CompoundTag saved = data.save(new CompoundTag(),helper.getLevel().registryAccess());
        CurrentOverrides loaded = CurrentOverrides.load(saved,helper.getLevel().registryAccess());
        CurrentChunk restored = loaded.apply(new ChunkPos(0,0),new CurrentChunk(0,generated));
        for (int y = 60; y < 120; y++) helper.assertTrue(restored.column(8,y,8).pack() == field.column(8,y,8).pack(), "Persistence changed a visible current interval at " + y);
        helper.assertTrue(!loaded.isDirty(), "Loading current edits marked them dirty");
        boolean rejected = false;
        try { data.addStill("overflow",8,8,130,131,2); } catch (IllegalArgumentException expected) { rejected = true; }
        helper.assertTrue(rejected && data.manualCount() == 2, "Interval overflow was silent or partially committed");
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "cwi_worldgen")
    public static void authoredCurrentsNeedActualWaterAndNeverAcquireALevelToLoadChunks(GameTestHelper helper) {
        var data = new CurrentOverrides();
        data.addPath("water_only",0,8,16,8,80,95,2,.04);
        var field = data.apply(new ChunkPos(0,0),new CurrentChunk(0,new long[256]));
        var level = helper.getLevel();
        var chunk = new ProtoChunk(new ChunkPos(0,0),UpgradeData.EMPTY,level,
                level.registryAccess().registryOrThrow(Registries.BIOME),null);
        BlockPos inside = new BlockPos(8,90,8), high = inside.atY(100), far = new BlockPos(8,90,0);
        helper.assertTrue(!RuntimeCurrentSampler.sample(field,chunk,inside).active(), "Authored path pushed air");
        for (BlockPos pos : new BlockPos[]{inside,high,far}) chunk.setBlockState(pos,Blocks.WATER.defaultBlockState(),false);
        helper.assertTrue(RuntimeCurrentSampler.sample(field,chunk,inside).active(), "Authored path failed inside actual water");
        helper.assertTrue(!RuntimeCurrentSampler.sample(field,chunk,high).active() && !RuntimeCurrentSampler.sample(field,chunk,far).active(), "Authored path ignored radius or height");
        helper.assertTrue(!RuntimeCurrentSampler.sample(field,null,inside).active(), "Authored path loaded or inferred a missing chunk");
        long[] upper = new long[256]; upper[8 * 16 + 8] = CurrentColumn.of(120,125,0,-1,.02,0,0).pack();
        data.clearManual(); data.replaceReflow(List.of(new CurrentChunk(0,upper)));
        field = data.apply(new ChunkPos(0,0),new CurrentChunk(0,new long[256]));
        helper.assertTrue(field.column(8,122,8).active() && !field.column(8,90,8).plannedWater(), "Stacked reflow ignored its actual water layer");
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "cwi_worldgen")
    public static void malformedSavedEditsAreBoundedAndAreaReflowUpdatesAreAtomic(GameTestHelper helper) {
        var data = new CurrentOverrides(); long key = ChunkPos.asLong(7,-3);
        long[] water = new long[256]; water[0] = CurrentColumn.still(60,62,0,0).pack();
        data.replaceReflow(List.of(new CurrentChunk(0,water)));
        data.replaceReflow(List.of(new CurrentChunk(key,water)));
        helper.assertTrue(data.reflowCount() == 2 && data.reflowField(0) != null, "Area reflow removed another saved region");
        boolean rejected = false;
        try { data.replaceReflow(List.of(new CurrentChunk(key,water),new CurrentChunk(key,water))); } catch (IllegalArgumentException expected) { rejected = true; }
        helper.assertTrue(rejected && data.reflowCount() == 2, "Duplicate reflow update partially committed");
        data.replaceReflow(List.of(new CurrentChunk(key,new long[256])));
        helper.assertTrue(data.reflowCount() == 1 && data.reflowField(0) != null, "Empty reflow replacement removed unrelated regions");
        var saved = data.save(new CompoundTag(),helper.getLevel().registryAccess());
        var layers = saved.getList("reflow",10).getCompound(0).getList("layers",12);
        layers.set(0,new LongArrayTag(new long[257])); rejected = false;
        try { CurrentOverrides.load(saved,helper.getLevel().registryAccess()); } catch (IllegalArgumentException expected) { rejected = true; }
        helper.assertTrue(rejected,"Oversized saved reflow passed bounds validation");
        data.addStill("valid",8,8,80,90,3); saved = data.save(new CompoundTag(),helper.getLevel().registryAccess());
        saved.getList("manual",10).getCompound(0).putDouble("radius",Double.NaN); rejected = false;
        try { CurrentOverrides.load(saved,helper.getLevel().registryAccess()); } catch (IllegalArgumentException expected) { rejected = true; }
        helper.assertTrue(rejected,"Non-finite authored data was accepted");
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "cwi_worldgen")
    public static void currentCommandsRequireOperatorsAndParseBoundedAdministrativeActions(GameTestHelper helper) {
        var dispatcher = new CommandDispatcher<CommandSourceStack>(); CurrentCommands.register(dispatcher);
        var source = helper.getLevel().getServer().createCommandSourceStack().withLevel(helper.getLevel());
        var root = dispatcher.getRoot().getChild("cwi_currents");
        helper.assertTrue(root.canUse(source.withPermission(2)) && !root.canUse(source.withPermission(1)), "Current controls escaped operator permissions");
        for (String command : new String[]{"cwi_currents path add canal 0 8 16 8 80 95 2 0.04", "cwi_currents still add pond ~ ~ 80 95 2",
                "cwi_currents reflow start 0 90 0 32", "cwi_currents inspect 0 90 0", "cwi_currents clear all", "cwi_currents reflow cancel"}) {
            var parsed = dispatcher.parse(command,source.withPermission(2));
            helper.assertTrue(!parsed.getReader().canRead() && parsed.getExceptions().isEmpty(), "Administrative action did not parse: " + command);
        }
        helper.assertTrue(dispatcher.parse("cwi_currents reflow start 0 90 0 65",source.withPermission(2)).getReader().canRead(), "Reflow radius accepted an unbounded job");
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "cwi_worldgen")
    public static void commandsRejectFlatOverworldAndCwiFixtureBeforeChangingPersistentState(GameTestHelper helper) {
        var flat = helper.getLevel().getServer().overworld();
        var fixture = helper.getLevel().getServer().getLevel(ResourceKey.create(Registries.DIMENSION,
                ResourceLocation.fromNamespaceAndPath("cwi_worldgen", "streams_wasteland_test")));
        helper.assertTrue(flat.dimension() == Level.OVERWORLD && ServerCurrentFields.terrain(flat) == null,
                "The negative canonical Overworld fixture must retain its flat generator");
        helper.assertTrue(fixture != null && ServerCurrentFields.terrain(fixture) != null
                        && ServerCurrentFields.terrain(fixture).generationVersion() == 6,
                "The negative custom-dimension fixture must retain the current CWI generator");
        var dispatcher = new CommandDispatcher<CommandSourceStack>(); CurrentCommands.register(dispatcher);
        for (ServerLevel level : new ServerLevel[]{flat, fixture}) {
            var factory = new SavedData.Factory<>(CurrentOverrides::new, CurrentOverrides::load);
            var before = level.getDataStorage().get(factory, "cwi_worldgen_currents");
            var beforeTag = before == null ? null : before.save(new CompoundTag(), level.registryAccess());
            boolean dirty = before != null && before.isDirty();
            int worlds = ServerCurrentFields.cachedWorlds(), fields = ServerCurrentFields.cachedFields(level);
            String reflowStatus = ExistingWaterReflow.status(level);
            var source = level.getServer().createCommandSourceStack().withLevel(level).withPermission(2);
            for (String command : new String[]{"cwi_currents path add rejected 0 8 16 8 80 95 2 0.04",
                    "cwi_currents still add rejected 8 8 80 95 2", "cwi_currents remove rejected",
                    "cwi_currents clear all", "cwi_currents reflow start 0 90 0 32",
                    "cwi_currents reflow cancel", "cwi_currents inspect 0 90 0", "cwi_currents list"}) {
                boolean rejected = false;
                try { dispatcher.execute(command, source); }
                catch (CommandSyntaxException expected) {
                    rejected = expected.getRawMessage().getString().contains("require a CWI Overworld");
                }
                helper.assertTrue(rejected, "Administrative command bypassed the canonical CWI Overworld gate: " + command);
            }
            var after = level.getDataStorage().get(factory, "cwi_worldgen_currents");
            helper.assertTrue(after == before && (after == null || after.isDirty() == dirty
                            && after.save(new CompoundTag(), level.registryAccess()).equals(beforeTag)),
                    "Rejected administrative commands created or changed persistent current data");
            helper.assertTrue(ServerCurrentFields.cachedWorlds() == worlds
                            && ServerCurrentFields.cachedFields(level) == fields
                            && ExistingWaterReflow.status(level).equals(reflowStatus),
                    "Rejected administrative commands changed runtime caches or reflow jobs");
        }
        helper.succeed();
    }
}
