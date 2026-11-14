package net.quiwh.cwiworldgen;

import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.*;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RailBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.RailShape;
import net.minecraft.world.level.levelgen.structure.PoolElementStructurePiece;
import net.minecraft.world.level.levelgen.structure.templatesystem.*;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Actual template placement and inventory retention, beyond palette resolution. */
@GameTestHolder(CwiWorldgen.ID)
@PrefixGameTestTemplate(false)
public final class NativeStructureTests {
    @GameTest(template = "empty", templateNamespace = "cwi_worldgen", timeoutTicks = 2400)
    public static void allCwiTemplatesPlaceWithTheirBlocksAndInventories(GameTestHelper helper) throws Exception {
        ServerLevel level = helper.getLevel();
        var resources = new TreeMap<>(level.getServer().getResourceManager().listResources("structure", name ->
                name.getNamespace().equals("cwi") && name.getPath().endsWith(".nbt")));
        check(resources.size() == 32,"Expected 32 CWI templates");
        List<String> failures = new ArrayList<>();
        int templateIndex = 0, blockCount = 0, blockEntityCount = 0, directInventorySlots = 0, nestedStacks = 0;
        for (var entry : resources.entrySet()) {
            String path = entry.getKey().getPath().substring("structure/".length()).replaceFirst("\\.nbt$","");
            ResourceLocation id = ResourceLocation.fromNamespaceAndPath("cwi",path);
            CompoundTag raw;
            try (var stream = entry.getValue().open()) { raw = NbtIo.readCompressed(stream,NbtAccounter.unlimitedHeap()); }
            var template = level.getStructureManager().get(id).orElseThrow(() -> new AssertionError("DFU could not load " + id));
            CompoundTag canonical = template.save(new CompoundTag());
            nestedStacks += validateStackIds(raw,id.toString(),failures);
            validateStackIds(canonical,id+" after DFU",failures);
            BlockPos origin = new BlockPos(20000+(templateIndex%8)*128,100,20000+(templateIndex/8)*128);
            templateIndex++;
            var settings = new StructurePlaceSettings().setKnownShape(true).setIgnoreEntities(false)
                    .setLiquidSettings(LiquidSettings.IGNORE_WATERLOGGING).setRandom(RandomSource.create(854L+templateIndex));
            check(template.placeInWorld(level,origin,origin,settings,RandomSource.create(854L+templateIndex),
                    Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE),"Native placement failed for " + id);
            ListTag palette = canonical.getList("palette",Tag.TAG_COMPOUND);
            for (Tag tag : canonical.getList("blocks",Tag.TAG_COMPOUND)) {
                CompoundTag info = (CompoundTag) tag;
                ListTag offset = info.getList("pos",Tag.TAG_INT);
                BlockPos pos = origin.offset(offset.getInt(0),offset.getInt(1),offset.getInt(2));
                BlockState expected = NbtUtils.readBlockState(BuiltInRegistries.BLOCK.asLookup(),palette.getCompound(info.getInt("state")));
                BlockState actual = level.getBlockState(pos);
                blockCount++;
                if (actual != expected) {
                    // RailBlock.onPlace calls BaseRailBlock.updateState even with
                    // UPDATE_KNOWN_SHAPE. These four isolated source slopes have
                    // no raised neighbor, so vanilla recalculates EAST_WEST.
                    // Compare every other state property (including waterlogged)
                    // and retain the authored slope in the original NBT.
                    boolean nativeRailAdjustment = id.toString().equals("cwi:sb/sml_6") && expected.is(Blocks.RAIL)
                        && offset.getInt(1) == 2 && (offset.getInt(0) == 2 || offset.getInt(0) == 4)
                        && (offset.getInt(2) == 2 || offset.getInt(2) == 4)
                        && (expected.getValue(RailBlock.SHAPE) == RailShape.ASCENDING_EAST
                            || expected.getValue(RailBlock.SHAPE) == RailShape.ASCENDING_WEST)
                        && actual == expected.setValue(RailBlock.SHAPE,RailShape.EAST_WEST);
                    if (nativeRailAdjustment) System.out.println("CWI_WORLDGEN_NATIVE_PLACEMENT_ADJUSTMENT="+id+" "+offset+" "+expected+" -> "+actual);
                    else failures.add(id+" " + offset+" expected "+expected+" got "+actual);
                }
                if (!info.contains("nbt",Tag.TAG_COMPOUND)) continue;
                CompoundTag authored = info.getCompound("nbt");
                BlockEntity entity = level.getBlockEntity(pos);
                if (expected.hasBlockEntity()) {
                    blockEntityCount++;
                    if (entity == null) { failures.add(id+" missing block entity at "+offset); continue; }
                    String expectedType = authored.getString("id");
                    String actualType = BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(entity.getType()).toString();
                    if (!actualType.equals(expectedType)) failures.add(id+" block entity type "+expectedType+" became "+actualType+" at "+offset);
                    validateStackIds(entity.saveWithFullMetadata(level.registryAccess()),id+" placed "+offset,failures);
                    if (entity instanceof Container container && authored.contains("Items",Tag.TAG_LIST)) {
                        for (Tag stackTag : authored.getList("Items",Tag.TAG_COMPOUND)) {
                            CompoundTag stack = (CompoundTag) stackTag;
                            int slot = stack.getInt("Slot");
                            ItemStack wanted = ItemStack.parseOptional(level.registryAccess(),stack);
                            if (slot < 0 || slot >= container.getContainerSize()) { failures.add(id+" invalid inventory slot "+slot); continue; }
                            ItemStack found = container.getItem(slot);
                            directInventorySlots++;
                            if (!ItemStack.matches(wanted,found)) failures.add(id+" inventory "+offset+" slot "+slot+" expected "+wanted+" got "+found);
                        }
                    }
                    // Arbitrary mod inventories are outside vanilla STRUCTURE DFU's schema.
                    // Check the source item/count multiset against the real BE's save output,
                    // including Create toolbox compartments, filters and nested inventories.
                    if (!(entity instanceof Container)) {
                        var wanted = inventoryMultiset(authored);
                        if (!wanted.isEmpty()) {
                            var found = inventoryMultiset(entity.saveWithFullMetadata(level.registryAccess()));
                            if (!wanted.equals(found)) failures.add(id+" mod inventory "+offset+" expected "+wanted+" got "+found);
                        }
                    }
                }
            }
            System.out.println("CWI_WORLDGEN_PLACED_TEMPLATE="+id);
        }
        System.out.println("CWI_WORLDGEN_STRUCTURE_PLACEMENT blocks="+blockCount+" block_entities="+blockEntityCount
                +" container_slots="+directInventorySlots+" nested_stack_compounds="+nestedStacks);
        for (String failure : failures) System.out.println("CWI_WORLDGEN_STRUCTURE_FAILURE="+failure);
        check(failures.isEmpty(),"Native structure retention failures: "+failures.size()+"; see CWI_WORLDGEN_STRUCTURE_FAILURE lines in the server log");
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "cwi_worldgen", timeoutTicks = 1200)
    public static void allSixJigsawStartsGenerateRealTemplatePieces(GameTestHelper helper) {
        for (String biome : new String[]{"forsaken_land","smoldering_barren"}) {
            ServerLevel level = helper.getLevel().getServer().getLevel(ResourceKey.create(Registries.DIMENSION,
                    ResourceLocation.fromNamespaceAndPath(CwiWorldgen.ID,"test_"+biome)));
            check(level != null,"Missing fixed-biome structure test dimension "+biome);
            var generator = level.getChunkSource().getGenerator();
            var registry = level.registryAccess().registryOrThrow(Registries.STRUCTURE);
            int index = 0;
            for (String size : new String[]{"sml","mid","lrg"}) {
                ResourceLocation id = ResourceLocation.fromNamespaceAndPath("cwi",biome+"/"+size);
                var structure = registry.getOptional(id).orElseThrow(() -> new AssertionError("Missing structure "+id));
                var start = net.minecraft.world.level.levelgen.structure.StructureStart.INVALID_START;
                for (int attempt = 0; attempt < 64 && !start.isValid(); attempt++) {
                    ChunkPos chunk = new ChunkPos(48+index*8+(attempt%8)*16,48+(attempt/8)*16);
                    start = structure.generate(level.registryAccess(),generator,generator.getBiomeSource(),level.getChunkSource().randomState(),
                            level.getStructureManager(),level.getSeed(),chunk,0,level,structure.biomes()::contains);
                }
                index++;
                check(start.isValid(),"Jigsaw structure found no eligible terrain across 64 candidates: "+id);
                check(!start.getPieces().isEmpty(),"Jigsaw start has no template pieces: "+id);
                for (var piece : start.getPieces()) {
                    check(piece instanceof PoolElementStructurePiece,"Unexpected non-jigsaw piece in "+id);
                    check(piece.getBoundingBox().getXSpan() > 1 && piece.getBoundingBox().getZSpan() > 1,"Empty jigsaw template bounds in "+id);
                }
                System.out.println("CWI_WORLDGEN_JIGSAW_START="+id+" pieces="+start.getPieces().size()+" bounds="+start.getBoundingBox());
            }
        }
        helper.succeed();
    }

    private static int validateStackIds(Tag tag, String location, List<String> failures) {
        int total = 0;
        if (tag instanceof CompoundTag compound) {
            if (compound.contains("id",Tag.TAG_STRING) && (compound.contains("Count") || compound.contains("count"))) {
                total++;
                String id = compound.getString("id");
                if (!BuiltInRegistries.ITEM.containsKey(ResourceLocation.parse(id))) failures.add(location+" missing nested item "+id);
            }
            for (String key : compound.getAllKeys()) total += validateStackIds(compound.get(key),location,failures);
        } else if (tag instanceof ListTag list) for (Tag value : list) total += validateStackIds(value,location,failures);
        return total;
    }

    private static Map<String,Integer> inventoryMultiset(Tag tag) {
        Map<String,Integer> values = new TreeMap<>();
        collectStacks(tag,values);
        return values;
    }

    private static void collectStacks(Tag tag, Map<String,Integer> values) {
        if (tag instanceof CompoundTag compound) {
            if (compound.contains("id",Tag.TAG_STRING) && (compound.contains("Count") || compound.contains("count"))) {
                String id = compound.getString("id");
                int count = compound.contains("count") ? compound.getInt("count") : compound.getInt("Count");
                if (!id.equals("minecraft:air") && count > 0) values.merge(id,count,Integer::sum);
            }
            for (String key : compound.getAllKeys()) if (!key.equals("ForgeCaps")) collectStacks(compound.get(key),values);
        } else if (tag instanceof ListTag list) for (Tag value : list) collectStacks(value,values);
    }

    private static void check(boolean passed, String message) { if (!passed) throw new AssertionError(message); }
    private NativeStructureTests() {}
}
