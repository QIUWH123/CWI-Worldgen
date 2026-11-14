package net.quiwh.cwiworldgen.block;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.PlaceOnWaterBlockItem;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.PushReaction;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.quiwh.cwiworldgen.CwiWorldgen;

/** Only missing blocks used by CWI worldgen and its original templates. */
public final class WorldgenBlocks {
    private static final DeferredRegister<Block> BLOCKS = DeferredRegister.create(Registries.BLOCK, CwiWorldgen.ID);
    private static final DeferredRegister<Item> ITEMS = DeferredRegister.create(Registries.ITEM, CwiWorldgen.ID);
    private static final Map<String, DeferredHolder<Block, ? extends Block>> ENTRIES = new LinkedHashMap<>();

    static {
        // Preserve existing saved palettes and inventories while Core owns the
        // canonical registrations. These aliases do not register duplicate blocks.
        for (String name : new String[]{"petrified_log", "stripped_petrified_log", "stripped_petrified_wood",
                "porous_petrified_log", "petrified_roots", "petrified_roots_plant", "amber_cluster", "rope", "amber"}) {
            var old = net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(CwiWorldgen.ID, name);
            var target = net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("cwi", name.equals("rope") ? "reinforced_rope" : name);
            if (!name.equals("amber")) BLOCKS.addAlias(old, target);
            if (!name.equals("petrified_roots_plant")) ITEMS.addAlias(old, target);
        }
        add("dry_sprouts", () -> new CaveSproutsBlock(plant(SoundType.GRASS), 8, true));
        add("mossy_sprouts", () -> new CaveSproutsBlock(plant(SoundType.MOSS), 6, false));
        add("scorched_remains", () -> new CaveSproutsBlock(plant(SoundType.GRASS), 10, false));
        add("scorched_remains_block", () -> new Block(BlockBehaviour.Properties.ofFullCopy(Blocks.COAL_BLOCK).sound(SoundType.GRASS)));
        add("dead_living_crystal", () -> new CrystalHuskBlock(BlockBehaviour.Properties.ofFullCopy(Blocks.DEEPSLATE).strength(4, 7.5f).randomTicks()));
        add("living_crystal", () -> new LivingCrystalBlock(BlockBehaviour.Properties.ofFullCopy(Blocks.DEEPSLATE).strength(4, 7.5f).randomTicks()));
        add("stone_melon", () -> new StoneMelonBlock(BlockBehaviour.Properties.ofFullCopy(Blocks.MELON).strength(1).noOcclusion()));
        add("crystal_melon", () -> new CrystalMelonBlock(BlockBehaviour.Properties.ofFullCopy(Blocks.MELON).strength(1.5f,1).lightLevel(state -> 10)));
        add("glowshroom", () -> new GlowshroomBlock(plant(SoundType.FUNGUS).lightLevel(state -> 3 + 2 * state.getValue(GlowshroomBlock.CLUSTERS))));
        add("glowshroom_block", () -> new Block(BlockBehaviour.Properties.ofFullCopy(Blocks.RED_MUSHROOM_BLOCK).strength(.5f)));
        add("glowshroom_stem", () -> new RotatedPillarBlock(BlockBehaviour.Properties.ofFullCopy(Blocks.MUSHROOM_STEM).strength(1.2f, 2)));
        add("glowshroom_heart", () -> new Block(BlockBehaviour.Properties.ofFullCopy(Blocks.SHROOMLIGHT).strength(2, 3).lightLevel(state -> 15)));
        add("glowspurs", () -> new GlowspursBlock(plant(SoundType.FUNGUS).lightLevel(state -> 5)));
        add("glimmering_vines", () -> new HangingHeadBlock(plant(SoundType.WEEPING_VINES).lightLevel(state -> 8).randomTicks(), true));
        add("glimmering_vine_plant", () -> new HangingBodyBlock(plant(SoundType.WEEPING_VINES).lightLevel(state -> 8), true));
        add("magma_pad", () -> new MagmaPadBlock(BlockBehaviour.Properties.ofFullCopy(Blocks.LILY_PAD).noOcclusion()));
        for (String family : new String[]{"nether_quartz", "echo"}) {
            for (String size : new String[]{"small", "medium", "large"}) {
                int height = size.equals("small") ? 3 : size.equals("medium") ? 4 : 5;
                add(size + "_" + family + "_bud", () -> new AmethystClusterBlock(height, size.equals("small") ? 4 : 3,
                    BlockBehaviour.Properties.ofFullCopy(Blocks.AMETHYST_CLUSTER).lightLevel(state -> 1)));
            }
            String crystal = family.equals("echo") ? "echo_crystal" : "nether_quartz_crystal";
            add(crystal, () -> new AmethystClusterBlock(7, 3, BlockBehaviour.Properties.ofFullCopy(Blocks.AMETHYST_CLUSTER).lightLevel(state -> 5)));
            add(crystal + "_block", () -> new AmethystBlock(BlockBehaviour.Properties.ofFullCopy(Blocks.AMETHYST_BLOCK)));
            add("budding_" + family, () -> new FamilyBuddingBlock(BlockBehaviour.Properties.ofFullCopy(Blocks.BUDDING_AMETHYST)
                .randomTicks().pushReaction(PushReaction.DESTROY), family));
        }
        add("iron_plateblock", () -> new Block(BlockBehaviour.Properties.ofFullCopy(Blocks.IRON_BLOCK)));
        add("iron_plating", () -> new Block(BlockBehaviour.Properties.ofFullCopy(Blocks.IRON_BLOCK)));
        add("steel_plateblock", () -> new Block(BlockBehaviour.Properties.ofFullCopy(Blocks.IRON_BLOCK)));
        add("marked_iron_pillar", () -> new RotatedPillarBlock(BlockBehaviour.Properties.ofFullCopy(Blocks.IRON_BLOCK)));
        add("small_white_industrial_lamp", () -> new IndustrialLampBlock(BlockBehaviour.Properties.ofFullCopy(Blocks.LANTERN).lightLevel(state -> 15)));
        add("slag_bricks_slab", () -> new SlabBlock(BlockBehaviour.Properties.ofFullCopy(Blocks.STONE_BRICK_SLAB)));
        ITEMS.register("glow_grime", () -> new Item(new Item.Properties()));
    }

    private static BlockBehaviour.Properties log() {
        return BlockBehaviour.Properties.ofFullCopy(Blocks.OAK_LOG).strength(2.4f, 3);
    }

    private static BlockBehaviour.Properties plant(SoundType sound) {
        return BlockBehaviour.Properties.of().noCollission().instabreak().sound(sound).pushReaction(PushReaction.DESTROY);
    }

    private static void add(String name, Supplier<? extends Block> factory) {
        var block = BLOCKS.register(name, factory);
        ENTRIES.put(name, block);
        if (!name.equals("glimmering_vine_plant") && !name.equals("petrified_roots_plant")) {
            ITEMS.register(name, () -> name.equals("magma_pad") ? new PlaceOnWaterBlockItem(block.get(),new Item.Properties())
                : new BlockItem(block.get(), new Item.Properties()));
        }
    }

    public static Block get(String name) {
        if (name.equals("petrified_log") || name.equals("stripped_petrified_log") || name.equals("stripped_petrified_wood")
                || name.equals("porous_petrified_log") || name.equals("petrified_roots") || name.equals("petrified_roots_plant")
                || name.equals("amber_cluster") || name.equals("rope")) {
            var id = net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("cwi", name.equals("rope") ? "reinforced_rope" : name);
            var block = net.minecraft.core.registries.BuiltInRegistries.BLOCK.get(id);
            if (block == Blocks.AIR) throw new IllegalStateException("CWI Core is missing required block " + id);
            return block;
        }
        var holder = ENTRIES.get(name);
        if (holder == null) throw new IllegalArgumentException("Unknown worldgen block " + name);
        return holder.get();
    }

    public static void register(IEventBus bus) {
        BLOCKS.register(bus);
        ITEMS.register(bus);
    }

    private WorldgenBlocks() {}
}
