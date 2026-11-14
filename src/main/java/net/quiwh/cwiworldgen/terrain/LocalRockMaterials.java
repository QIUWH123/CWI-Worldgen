package net.quiwh.cwiworldgen.terrain;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

/** Pack rock families, resolved from actual blocks rather than a biome's predicted geology. */
public final class LocalRockMaterials {
    private static final Set<String> SOILS = Set.of("kubejs:depleted_dirt", "kubejs:ashen_depleted_dirt",
            "minecraft:dirt", "minecraft:coarse_dirt", "minecraft:rooted_dirt", "minecraft:grass_block",
            "minecraft:mycelium", "minecraft:podzol", "minecraft:sand", "minecraft:red_sand", "minecraft:clay",
            "minecraft:sandstone", "minecraft:smooth_sandstone");
    private static final Map<String, Definition> FAMILIES = definitions();

    private LocalRockMaterials() {}

    public record Family(BlockState raw, BlockState cobble, BlockState gravel) {}
    private record Definition(String raw, String cobble, String gravel, double hardness) {}

    private static Map<String, Definition> definitions() {
        Map<String, Definition> result = new HashMap<>();
        add(result, "minecraft:stone", "minecraft:cobblestone", "minecraft:gravel", 2.4);
        add(result, "minecraft:deepslate", "minecraft:cobbled_deepslate", "kubejs:deepslate_gravel", 3.3);
        for (String name : new String[]{"granite", "diorite", "andesite", "basalt", "claystone", "schist",
                "gneiss", "rhyolite", "shale", "conglomerate", "phyllite", "quartzite", "darkslate"}) {
            String namespace = Set.of("granite", "diorite", "andesite", "basalt").contains(name) ? "minecraft:" : "kubejs:";
            double hardness = switch (name) {
                case "granite" -> 4.3;
                case "diorite", "basalt" -> 4.0;
                case "andesite" -> 3.7;
                case "claystone", "quartzite" -> 1.7;
                case "schist" -> 3.4;
                case "gneiss", "rhyolite" -> 4.5;
                case "shale" -> 2.3;
                case "conglomerate", "phyllite" -> 2.8;
                default -> 3.8;
            };
            add(result, namespace + name, "kubejs:cobbled_" + name, "kubejs:" + name + "_gravel", hardness);
            // Some compatible packs register their own raw igneous block under KubeJS.
            if (namespace.equals("minecraft:")) result.put("kubejs:" + name,
                    new Definition("kubejs:" + name, "kubejs:cobbled_" + name, "kubejs:" + name + "_gravel", hardness));
        }
        for (String raw : new String[]{"minecraft:tuff", "minecraft:calcite", "create:limestone", "kubejs:quartzite_deepslate"})
            result.put(raw, new Definition(raw, raw, "minecraft:gravel", raw.equals("minecraft:tuff") ? 1.5 : 2.0));
        return Map.copyOf(result);
    }

    private static void add(Map<String, Definition> result, String raw, String cobble, String gravel, double hardness) {
        Definition family = new Definition(raw, cobble, gravel, hardness);
        result.put(raw, family);
        result.put(cobble, family);
        result.put(gravel, family);
    }

    /** Missing optional cobble retains raw rock; missing optional gravel uses vanilla gravel. */
    public static @Nullable Family family(BlockState state) {
        if (state.hasBlockEntity() || !state.getFluidState().isEmpty()) return null;
        Definition definition = FAMILIES.get(id(state));
        if (definition == null) return null;
        BlockState raw = registered(definition.raw);
        if (raw == null) raw = state;
        BlockState cobble = registered(definition.cobble), gravel = registered(definition.gravel);
        return new Family(raw, cobble == null ? raw : cobble, gravel == null ? Blocks.GRAVEL.defaultBlockState() : gravel);
    }

    public static boolean rawRock(BlockState state) {
        Definition definition = FAMILIES.get(id(state));
        return definition != null && id(state).equals(definition.raw) && family(state) != null;
    }

    public static boolean gravel(BlockState state) {
        Definition definition = FAMILIES.get(id(state));
        return definition != null && id(state).equals(definition.gravel) && family(state) != null;
    }

    /** Normalized pack hardness, reduced for loose gravel and weathered cobble. */
    public static double resistance(BlockState state) {
        Definition definition = FAMILIES.get(id(state));
        if (definition == null || family(state) == null) return .15;
        double hardness = definition.hardness;
        if (gravel(state)) hardness *= .2;
        else if (!rawRock(state)) hardness *= .7;
        return Math.clamp(hardness / 4.5, 0., 1.);
    }

    public static boolean natural(BlockState state) {
        return !state.hasBlockEntity() && state.getFluidState().isEmpty()
                && (FAMILIES.containsKey(id(state)) || SOILS.contains(id(state)));
    }

    /** Finds contiguous sturdy host rock; existing cobble/gravel is a fallback when no raw host is exposed. */
    public static @Nullable Family find(BlockGetter level, BlockPos top, int maxDepth) {
        Family weathered = null;
        BlockPos.MutableBlockPos pos = top.mutable();
        for (int depth = 0; depth <= maxDepth && top.getY() - depth >= level.getMinBuildHeight(); depth++) {
            BlockState state = level.getBlockState(pos.setY(top.getY() - depth));
            if (!natural(state) || !state.isFaceSturdy(level, pos, Direction.UP)) return null;
            Family family = family(state);
            if (rawRock(state)) return family;
            if (weathered == null && family != null) weathered = family;
        }
        return weathered;
    }

    /** Reuses the requested rock form while preserving non-rock authored palettes. */
    public static BlockState matching(BlockState requested, @Nullable Family host) {
        if (host == null || family(requested) == null) return requested;
        if (gravel(requested)) return host.gravel;
        if (rawRock(requested)) return host.raw;
        return host.cobble;
    }

    private static @Nullable BlockState registered(String id) {
        ResourceLocation location = ResourceLocation.parse(id);
        if (!BuiltInRegistries.BLOCK.containsKey(location)) return null;
        BlockState state = BuiltInRegistries.BLOCK.get(location).defaultBlockState();
        return state.isAir() || state.hasBlockEntity() || !state.getFluidState().isEmpty() ? null : state;
    }
    private static String id(BlockState state) { return BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString(); }
}
