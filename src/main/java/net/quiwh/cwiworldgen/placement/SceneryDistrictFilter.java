package net.quiwh.cwiworldgen.placement;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.placement.PlacementContext;
import net.minecraft.world.level.levelgen.placement.PlacementModifier;
import net.minecraft.world.level.levelgen.placement.PlacementModifierType;
import net.quiwh.cwiworldgen.terrain.TerrainRandomState;

/** World-seeded, smoothly varying clearings and compact groups of existing scenery. */
public final class SceneryDistrictFilter extends PlacementModifier {
    private static final long ASH_TREE_SALT = 156791L;
    private static final long BURNT_TREE_SALT = 612509L;
    private static final long TREE_THINNING_SALT = 0x545245455448494eL;

    public record Profile(ResourceLocation biome, double chance, int minGroups, int maxGroups,
            int minMembers, int maxMembers, int radius) {
        public static final Codec<Profile> CODEC = RecordCodecBuilder.<Profile>create(instance -> instance.group(
                ResourceLocation.CODEC.fieldOf("biome").forGetter(Profile::biome),
                Codec.doubleRange(0, 1).fieldOf("chance").forGetter(Profile::chance),
                Codec.intRange(1, 8).fieldOf("min_groups").forGetter(Profile::minGroups),
                Codec.intRange(1, 8).fieldOf("max_groups").forGetter(Profile::maxGroups),
                Codec.intRange(1, 8).fieldOf("min_members").forGetter(Profile::minMembers),
                Codec.intRange(1, 8).fieldOf("max_members").forGetter(Profile::maxMembers),
                Codec.intRange(1, 8).fieldOf("radius").forGetter(Profile::radius)
        ).apply(instance, Profile::new)).validate(value -> value.minGroups <= value.maxGroups
                && value.minMembers <= value.maxMembers ? DataResult.success(value)
                : DataResult.error(() -> "Scenery group/member ranges are reversed"));
    }

    public static final MapCodec<SceneryDistrictFilter> CODEC = RecordCodecBuilder.<SceneryDistrictFilter>mapCodec(instance -> instance.group(
            Codec.LONG.fieldOf("salt").forGetter(value -> value.salt),
            Profile.CODEC.listOf().fieldOf("profiles").forGetter(value -> value.profiles),
            Codec.BOOL.optionalFieldOf("scattered", false).forGetter(value -> value.scattered)
    ).apply(instance, SceneryDistrictFilter::new)).validate(value -> !value.profiles.isEmpty()
            && value.profiles.stream().map(Profile::biome).distinct().count() == value.profiles.size()
            ? DataResult.success(value) : DataResult.error(() -> "Scenery profiles must be nonempty and biome-unique"));

    private final long salt;
    private final List<Profile> profiles;
    private final boolean scattered;

    public SceneryDistrictFilter(long salt, List<Profile> profiles) {
        this(salt, profiles, false);
    }

    public SceneryDistrictFilter(long salt, List<Profile> profiles, boolean scattered) {
        this.salt = salt;
        this.profiles = List.copyOf(profiles);
        this.scattered = scattered;
    }

    @Override
    public Stream<BlockPos> getPositions(PlacementContext context, RandomSource random, BlockPos position) {
        ChunkPos chunk = new ChunkPos(position);
        if ((Object) context.getLevel().getLevel().getChunkSource().randomState() instanceof TerrainRandomState state
                && state.cwiWorldgen$terrain() != null && state.cwiWorldgen$terrain().generationVersion() >= 5) {
            long seed = context.getLevel().getSeed();
            return profiles.stream().flatMap(profile -> candidates(seed, salt, chunk, profile, state.cwiWorldgen$terrain().generationVersion(), scattered).stream()
                    .filter(candidate -> {
                        int surface = context.getHeight(Heightmap.Types.WORLD_SURFACE_WG,
                                candidate.getX(), candidate.getZ());
                        return context.getLevel().getBiome(candidate.atY(surface)).is(profile.biome);
                    })).distinct();
        }
        int x = chunk.getMiddleBlockX(), z = chunk.getMiddleBlockZ();
        int y = context.getHeight(Heightmap.Types.WORLD_SURFACE_WG, x, z);
        var biome = context.getLevel().getBiome(new BlockPos(x, y, z));
        Profile profile = profiles.stream().filter(value -> biome.is(value.biome)).findFirst().orElse(null);
        if (profile == null) return Stream.empty();
        return candidates(context.getLevel().getSeed(), salt, chunk, profile).stream();
    }

    /** Independent of feature RNG and generation order; all returned candidates belong to the owner. */
    public static List<BlockPos> candidates(long seed, long salt, ChunkPos chunk, Profile profile) {
        double richness = richness(seed, chunk.getMiddleBlockX(), chunk.getMiddleBlockZ());
        RandomSource random = RandomSource.create(mix(seed ^ salt ^ mix(chunk.x * 0x632be59bd9b4e019L)
                ^ mix(chunk.z * 0x9e3779b97f4a7c15L)));
        if (richness <= 0 || random.nextDouble() >= profile.chance * (.45 + .55 * richness)) return List.of();
        int groups = profile.minGroups + (int) Math.floor(random.nextDouble()
                * (profile.maxGroups - profile.minGroups + 1) * (.45 + .55 * richness));
        List<BlockPos> result = new ArrayList<>();
        for (int group = 0; group < groups; group++) {
            int centerX = 3 + random.nextInt(10), centerZ = 3 + random.nextInt(10);
            int members = profile.minMembers + random.nextInt(profile.maxMembers - profile.minMembers + 1);
            for (int member = 0; member < members; member++) {
                int localX = centerX + random.nextInt(profile.radius * 2 + 1) - profile.radius;
                int localZ = centerZ + random.nextInt(profile.radius * 2 + 1) - profile.radius;
                if (localX >= 1 && localX <= 14 && localZ >= 1 && localZ <= 14) {
                    BlockPos next = new BlockPos(chunk.getBlockX(localX), 0, chunk.getBlockZ(localZ));
                    if (!result.contains(next)) result.add(next);
                }
            }
        }
        return List.copyOf(result);
    }

    /** New worlds partition world-space groups by owner; saved profiles retain their original candidate stream. */
    public static List<BlockPos> candidates(long seed, long salt, ChunkPos chunk, Profile profile, int generationVersion) {
        return candidates(seed, salt, chunk, profile, generationVersion, false);
    }

    public static List<BlockPos> candidates(long seed, long salt, ChunkPos chunk, Profile profile,
            int generationVersion, boolean scattered) {
        if (generationVersion < 5) return candidates(seed, salt, chunk, profile);
        boolean thinStandingTrees = generationVersion >= 21 && scattered
                && (salt == ASH_TREE_SALT || salt == BURNT_TREE_SALT);
        return SceneryClusters.candidates(seed, salt, chunk.x, chunk.z, profile.chance,
                profile.minGroups, profile.maxGroups, profile.minMembers, profile.maxMembers, profile.radius,
                generationVersion, scattered).stream()
                // Filter the complete established stream without consuming placement or feature RNG.
                .filter(point -> !thinStandingTrees || unit(seed ^ salt ^ TREE_THINNING_SALT
                        ^ mix(point.x() * 0x632be59bd9b4e019L)
                        ^ mix(point.z() * 0x9e3779b97f4a7c15L)) < .75)
                .map(point -> new BlockPos(point.x(), 0, point.z())).toList();
    }

    /** Common composition field lets wood and sparse cover share clearings without identical positions. */
    public static double richness(long seed, int x, int z) {
        double field = .72 * noise(seed ^ 0x4357495343454e45L, x, z, 192)
                + .28 * noise(seed ^ 0x52454c494546L, x, z, 64);
        return Math.max(0, Math.min(1, (field - .35) / .5));
    }

    private static double noise(long seed, int x, int z, int size) {
        int cx = Math.floorDiv(x, size), cz = Math.floorDiv(z, size);
        double u = Math.floorMod(x, size) / (double) size, v = Math.floorMod(z, size) / (double) size;
        u = u * u * (3 - 2 * u); v = v * v * (3 - 2 * v);
        double a = unit(seed ^ mix(cx * 0x632be59bd9b4e019L) ^ mix(cz * 0x9e3779b97f4a7c15L));
        double b = unit(seed ^ mix((cx + 1L) * 0x632be59bd9b4e019L) ^ mix(cz * 0x9e3779b97f4a7c15L));
        double c = unit(seed ^ mix(cx * 0x632be59bd9b4e019L) ^ mix((cz + 1L) * 0x9e3779b97f4a7c15L));
        double d = unit(seed ^ mix((cx + 1L) * 0x632be59bd9b4e019L) ^ mix((cz + 1L) * 0x9e3779b97f4a7c15L));
        return (a + (b - a) * u) * (1 - v) + (c + (d - c) * u) * v;
    }

    private static double unit(long seed) { return (mix(seed) >>> 11) * 0x1.0p-53; }
    private static long mix(long value) {
        value = (value ^ (value >>> 30)) * 0xbf58476d1ce4e5b9L;
        value = (value ^ (value >>> 27)) * 0x94d049bb133111ebL;
        return value ^ (value >>> 31);
    }

    @Override public PlacementModifierType<?> type() { return WorldgenPlacements.SCENERY_DISTRICT.get(); }
}
