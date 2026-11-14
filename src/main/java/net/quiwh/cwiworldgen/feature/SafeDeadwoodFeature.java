package net.quiwh.cwiworldgen.feature;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.util.RandomSource;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext;
import net.minecraft.world.level.levelgen.feature.configurations.FeatureConfiguration;
import net.quiwh.cwiworldgen.terrain.TerrainRandomState;
import net.quiwh.cwiworldgen.terrain.SurfaceHabitat;
import net.quiwh.cwiworldgen.terrain.LocalRockMaterials;

/** Existing wasteland wood and cover with atomic, supported, owning-chunk footprints. */
public final class SafeDeadwoodFeature extends Feature<SafeDeadwoodFeature.Config> {
    public enum Kind implements StringRepresentable {
        STANDING, FALLEN, COVER;
        @Override public String getSerializedName() { return name().toLowerCase(java.util.Locale.ROOT); }
    }

    public record Config(BlockState state, Kind kind, int minSize, int maxSize, boolean fork) implements FeatureConfiguration {
        public static final Codec<Config> CODEC = RecordCodecBuilder.<Config>create(instance -> instance.group(
                BlockState.CODEC.fieldOf("state").forGetter(Config::state),
                StringRepresentable.fromEnum(Kind::values).fieldOf("kind").forGetter(Config::kind),
                Codec.intRange(1, 12).fieldOf("min_size").forGetter(Config::minSize),
                Codec.intRange(1, 12).fieldOf("max_size").forGetter(Config::maxSize),
                Codec.BOOL.optionalFieldOf("fork", false).forGetter(Config::fork)
        ).apply(instance, Config::new)).validate(value -> value.minSize <= value.maxSize
                && !value.state.isAir() && value.state.getFluidState().isEmpty() && !value.state.hasBlockEntity()
                ? DataResult.success(value) : DataResult.error(() -> "Invalid scenery size range or material"));
    }

    private static final Set<String> SUPPORTS = Set.of(
            "minecraft:dirt", "minecraft:coarse_dirt", "minecraft:rooted_dirt", "minecraft:grass_block",
            "minecraft:podzol", "minecraft:mycelium", "minecraft:sand", "minecraft:red_sand", "minecraft:gravel",
            "minecraft:stone", "minecraft:deepslate", "minecraft:granite", "minecraft:andesite", "minecraft:diorite",
            "minecraft:tuff", "minecraft:calcite", "minecraft:basalt", "minecraft:clay", "create:limestone",
            "kubejs:depleted_dirt", "kubejs:ashen_depleted_dirt", "kubejs:ash_dirt", "kubejs:claystone",
            "kubejs:schist", "kubejs:gneiss", "kubejs:rhyolite", "kubejs:shale", "kubejs:conglomerate",
            "kubejs:phyllite", "kubejs:quartzite", "kubejs:ash_stone", "kubejs:dark_ash_stone", "kubejs:darkslate");

    public SafeDeadwoodFeature(Codec<Config> codec) { super(codec); }

    @Override public boolean place(FeaturePlaceContext<Config> context) {
        ChunkPos owner = new ChunkPos(context.origin());
        ChunkAccess chunk = context.level().getChunk(owner.x, owner.z);
        if (!((Object) context.level().getLevel().getChunkSource().randomState() instanceof TerrainRandomState state)
                || state.cwiWorldgen$terrain() == null) return false;
        var terrain = state.cwiWorldgen$terrain();
        var reader = terrain.prepareChunk(owner.x, owner.z);
        var manager = context.level().getLevel().structureManager();
        if (context.level() instanceof WorldGenRegion region) manager = manager.forWorldGenRegion(region);
        var boxes = manager.startsForStructure(owner, structure -> true).stream()
                .flatMap(start -> start.getPieces().stream()).map(piece -> piece.getBoundingBox()).toList();
        return placeChunk(chunk, context.origin(), context.random(), context.config(), position -> {
            var cell = reader.getCell(position.getX(), position.getZ());
            if (cell.terrain == null || cell.terrain.isRiver() || cell.terrain.isLake() || cell.terrain.isWetland()
                    || cell.terrain.isSubmerged() || cell.terrain.isDeepOcean() || cell.terrain.isShallowOcean()) return false;
            var biome = context.level().getBiome(position);
            if (!biome.is(net.minecraft.resources.ResourceLocation.parse("cwi:forsaken_land"))
                    && !biome.is(net.minecraft.resources.ResourceLocation.parse("cwi:smoldering_barren"))) return false;
            for (var box : boxes) if (box.isInside(position) || box.isInside(position.below())) return false;
            return context.config().state.canSurvive(context.level(), position);
        }, terrain.generationVersion(), terrain.generationVersion()>=12&&context.config().kind()!=Kind.STANDING
                ?terrain.surfaceHabitat(context.origin().getX(),context.origin().getZ(),reader.getCell(context.origin().getX(),context.origin().getZ())):null,
                context.level().getSeed());
    }

    /** Validates the complete plan before touching any block; never clips a log at a border. */
    static boolean placeChunk(ChunkAccess chunk, BlockPos origin, RandomSource random, Config config,
            Predicate<BlockPos> eligible) {
        return placeChunk(chunk, origin, random, config, eligible, 9);
    }

    static boolean placeChunk(ChunkAccess chunk, BlockPos origin, RandomSource random, Config config,
            Predicate<BlockPos> eligible, int generationVersion) {
        return placeChunk(chunk,origin,random,config,eligible,generationVersion,null,0);
    }

    static boolean placeChunk(ChunkAccess chunk,BlockPos origin,RandomSource random,Config config,
            Predicate<BlockPos> eligible,int generationVersion,SurfaceHabitat habitat,long seed) {
        ChunkPos owner = chunk.getPos();
        if (!owner.equals(new ChunkPos(origin))) return false;
        int x = origin.getX(), z = origin.getZ();
        int y = chunk.getHeight(Heightmap.Types.WORLD_SURFACE_WG, x, z) + 1;
        int minimum = generationVersion >= 10 && config.kind == Kind.STANDING
                ? Math.max(config.minSize, Math.min(3, config.maxSize)) : config.minSize;
        int size = minimum + random.nextInt(config.maxSize - minimum + 1);
        Direction direction = Direction.Plane.HORIZONTAL.getRandomDirection(random);
        Set<BlockPos> targets = new LinkedHashSet<>();
        BlockPos base = new BlockPos(x, y, z);
        switch (config.kind) {
            case STANDING -> {
                for (int i = 0; i < size; i++) targets.add(base.above(i));
                if (config.fork && size >= 4) {
                    targets.add(base.above(size - 2).relative(direction));
                    targets.add(base.above(size - 1).relative(direction));
                }
            }
            case FALLEN -> {
                for (int i = 0; i < size; i++) targets.add(base.relative(direction, i));
            }
            case COVER -> {
                targets.add(base);
                for (int i = 1; i < size; i++) {
                    BlockPos next = base.relative(direction, i);
                    int top = chunk.getHeight(Heightmap.Types.WORLD_SURFACE_WG, next.getX(), next.getZ()) + 1;
                    targets.add(next.atY(top));
                }
            }
        }
        BlockState placed = config.state;
        if (placed.hasProperty(RotatedPillarBlock.AXIS)) placed = placed.setValue(RotatedPillarBlock.AXIS,
                config.kind == Kind.FALLEN ? direction.getAxis() : Direction.Axis.Y);
        List<BlockPos> support = new ArrayList<>();
        for (BlockPos target : targets) {
            if (!owner.equals(new ChunkPos(target)) || target.getY() <= chunk.getMinBuildHeight()
                    || target.getY() >= chunk.getMaxBuildHeight() || !eligible.test(target)
                    || !chunk.getBlockState(target).isAir()) return false;
            if (targets.contains(target.below())) continue;
            // A forked limb is held by its trunk; ground decorations and every log segment need direct support.
            if (config.kind == Kind.STANDING && !target.equals(base)) continue;
            support.add(target.below());
        }
        for (BlockPos position : support) {
            if (!(config.kind == Kind.STANDING ? standingSupport(chunk, position, generationVersion)
                    : actualSupport(chunk, position, generationVersion))) return false;
        }
        if(generationVersion>=12&&habitat!=null&&config.kind!=Kind.STANDING){
            var actual=habitat.withRockResistance(LocalRockMaterials.resistance(chunk.getBlockState(base.below())));
            boolean leaves=config.kind==Kind.COVER&&BuiltInRegistries.BLOCK.getKey(config.state.getBlock()).getPath().contains("leaves");
            SurfaceHabitat.Cover cover=config.kind==Kind.FALLEN?SurfaceHabitat.Cover.FALLEN:
                    leaves?SurfaceHabitat.Cover.LEAVES:SurfaceHabitat.Cover.BUSH;
            if(!actual.admits(seed,x,z,cover,leaves&&hasStandingTrunkNearby(chunk,base)))return false;
        }
        EnumSet<Heightmap.Types> maps = EnumSet.of(Heightmap.Types.WORLD_SURFACE_WG, Heightmap.Types.OCEAN_FLOOR_WG);
        EnumSet<Heightmap.Types> missing = maps.clone();
        missing.removeIf(chunk::hasPrimedHeightmap);
        if (!missing.isEmpty()) Heightmap.primeHeightmaps(chunk, missing);
        for (BlockPos target : targets) {
            chunk.setBlockState(target, placed, false);
            for (Heightmap.Types map : maps) chunk.getOrCreateHeightmapUnprimed(map)
                    .update(target.getX() & 15, target.getY(), target.getZ() & 15, placed);
        }
        return true;
    }

    /** Only successful, supported, vertical trunks in this owning chunk count as leaf-litter shelter. */
    static boolean hasStandingTrunkNearby(ChunkAccess chunk,BlockPos origin){
        ChunkPos owner=chunk.getPos();
        for(int dz=-4;dz<=4;dz++)for(int dx=-4;dx<=4;dx++){
            if(dx*dx+dz*dz>16)continue;
            int x=origin.getX()+dx,z=origin.getZ()+dz;
            if((x>>4)!=owner.x||(z>>4)!=owner.z)continue;
            int top=chunk.getHeight(Heightmap.Types.WORLD_SURFACE_WG,x,z);
            int run=0;
            for(int y=top;y>=Math.max(chunk.getMinBuildHeight(),top-12);y--){
                BlockPos position=new BlockPos(x,y,z);BlockState state=chunk.getBlockState(position);
                String name=BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
                boolean log=state.is(net.minecraft.tags.BlockTags.LOGS)||name.equals("kubejs:ash_log")||name.equals("kubejs:burnt_log");
                if(log&&state.hasProperty(RotatedPillarBlock.AXIS)&&state.getValue(RotatedPillarBlock.AXIS)==Direction.Axis.Y){run++;continue;}
                if(run>=3&&actualSupport(chunk,position,12))return true;
                run=0;
            }
        }
        return false;
    }

    private static boolean naturalSupport(BlockState state) {
        return !state.hasBlockEntity() && state.getFluidState().isEmpty()
                && SUPPORTS.contains(BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString());
    }

    /** Compact classic trees root only in the two depleted soils used by surface generation. */
    static boolean standingSupport(ChunkAccess chunk, BlockPos position, int generationVersion) {
        if (generationVersion < 21) return actualSupport(chunk, position, generationVersion);
        BlockState ground = chunk.getBlockState(position);
        String id = BuiltInRegistries.BLOCK.getKey(ground.getBlock()).toString();
        return (id.equals("kubejs:depleted_dirt") || id.equals("kubejs:ashen_depleted_dirt"))
                && !ground.hasBlockEntity() && ground.getFluidState().isEmpty()
                && ground.isFaceSturdy(chunk, position, Direction.UP);
    }

    /** Falling surface cover needs a shallow stable base before it can support scenery. */
    static boolean actualSupport(ChunkAccess chunk, BlockPos position, int generationVersion) {
        BlockState ground = chunk.getBlockState(position);
        if (generationVersion < 12)
            return naturalSupport(ground) && ground.isFaceSturdy(chunk, position, Direction.UP);
        var cursor = position.mutable();
        for (int depth = 0; depth <= 12; depth++) {
            if (cursor.getY() < chunk.getMinBuildHeight()) return false;
            ground = chunk.getBlockState(cursor);
            if (!(naturalSupport(ground) || LocalRockMaterials.natural(ground))
                    || !ground.isFaceSturdy(chunk, cursor, Direction.UP)) return false;
            if (!(ground.getBlock() instanceof FallingBlock)) return true;
            cursor.move(Direction.DOWN);
        }
        return false;
    }
}
