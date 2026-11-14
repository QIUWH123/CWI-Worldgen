package net.quiwh.cwiworldgen.terrain;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.KeyDispatchDataCodec;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.SurfaceRules;
import org.jetbrains.annotations.Nullable;

/** Seeded, irregular rock masses restricted to stored biome cells and explicit heights. */
public record BiomeRockRule(int cellSize, List<Profile> profiles) implements SurfaceRules.RuleSource {
    public static final MapCodec<BiomeRockRule> CODEC = RecordCodecBuilder.<BiomeRockRule>mapCodec(instance -> instance.group(
            Codec.intRange(16, 256).optionalFieldOf("cell_size", 48).forGetter(BiomeRockRule::cellSize),
            Profile.CODEC.listOf(1, 256).fieldOf("profiles").forGetter(BiomeRockRule::profiles)
    ).apply(instance, BiomeRockRule::new)).flatXmap(BiomeRockRule::validate, BiomeRockRule::validate);

    public record Rock(BlockState state, int weight) {
        public static final Codec<Rock> CODEC = RecordCodecBuilder.<Rock>create(instance -> instance.group(
                BlockState.CODEC.fieldOf("state").forGetter(Rock::state),
                Codec.intRange(1, Integer.MAX_VALUE).fieldOf("weight").forGetter(Rock::weight)
        ).apply(instance, Rock::new)).validate(rock -> !rock.state.isAir()
                && rock.state.getFluidState().isEmpty() && !rock.state.hasBlockEntity()
                && rock.state.canOcclude()
                ? DataResult.success(rock)
                : DataResult.error(() -> "Biome rock palette targets must be solid rock states without fluids or block entities"));
    }

    public record Profile(ResourceLocation biome, int minY, int maxY, List<Rock> rocks) {
        public static final Codec<Profile> CODEC = RecordCodecBuilder.<Profile>create(instance -> instance.group(
                ResourceLocation.CODEC.fieldOf("biome").forGetter(Profile::biome),
                Codec.intRange(-2032, 2031).fieldOf("min_y").forGetter(Profile::minY),
                Codec.intRange(-2032, 2031).fieldOf("max_y").forGetter(Profile::maxY),
                Rock.CODEC.listOf(1, 256).fieldOf("rocks").forGetter(Profile::rocks)
        ).apply(instance, Profile::new)).validate(profile -> {
            if (profile.minY > profile.maxY) {
                return DataResult.error(() -> "Biome rock profile height bounds must be ordered");
            }
            long total = profile.rocks.stream().mapToLong(Rock::weight).sum();
            return total <= Integer.MAX_VALUE ? DataResult.success(profile)
                    : DataResult.error(() -> "Biome rock palette weight total exceeds the supported integer range");
        });

        public Profile {
            rocks = List.copyOf(rocks);
        }
    }

    public BiomeRockRule {
        profiles = List.copyOf(profiles);
    }

    private static DataResult<BiomeRockRule> validate(BiomeRockRule rule) {
        Set<ResourceLocation> biomes = new HashSet<>();
        for (Profile profile : rule.profiles) {
            if (!biomes.add(profile.biome)) {
                return DataResult.error(() -> "Duplicate biome rock profile: " + profile.biome);
            }
        }
        return DataResult.success(rule);
    }

    /** The seed is the reference terrain engine's low 32-bit world seed. */
    public Sampler sampler(long seed) {
        return new Sampler(cellSize, profiles, (int) seed);
    }

    @Override
    public SurfaceRules.SurfaceRule apply(SurfaceRules.Context context) {
        var terrain = ((TerrainRandomState) (Object) context.randomState).cwiWorldgen$terrain();
        if (terrain == null) return (x, y, z) -> null;
        Sampler sampler = sampler(terrain.seed());
        return new SurfaceRules.SurfaceRule() {
            private final BlockPos.MutableBlockPos position = new BlockPos.MutableBlockPos();
            private final Sampler.MassSiteCache massSites = new Sampler.MassSiteCache(sampler);
            private int lastQuartX = Integer.MIN_VALUE;
            private int lastQuartY = Integer.MIN_VALUE;
            private int lastQuartZ = Integer.MIN_VALUE;
            private ResourceLocation biome;

            @Override
            public BlockState tryApply(int x, int y, int z) {
                // Top Material Also Calls Rules During Carver Dirt Restoration.
                if (context.chunk.getBlockState(position.set(x, y, z)) != Blocks.STONE.defaultBlockState()) return null;
                int quartX = x >> 2;
                int quartY = y >> 2;
                int quartZ = z >> 2;
                if (quartX != lastQuartX || quartY != lastQuartY || quartZ != lastQuartZ) {
                    biome = context.chunk.getNoiseBiome(quartX, quartY, quartZ).unwrapKey()
                            .map(key -> key.location()).orElse(null);
                    lastQuartX = quartX;
                    lastQuartY = quartY;
                    lastQuartZ = quartZ;
                }
                return sampler.materialAt(biome, x, y, z, massSites);
            }
        };
    }

    @Override
    public KeyDispatchDataCodec<BiomeRockRule> codec() {
        return KeyDispatchDataCodec.of(CODEC);
    }

    /** Immutable and safe to query concurrently, independently of chunk generation order. */
    public static final class Sampler {
        private static final long MASS_SEED = 0x6A09E667F3BCC909L;
        private static final long X_SALT = 0x9E3779B97F4A7C15L;
        private static final long Y_SALT = 0xC2B2AE3D27D4EB4FL;
        private static final long Z_SALT = 0x165667B19E3779F9L;
        private final int cellSize;
        private final long seed;
        private final Map<ResourceLocation, Palette> palettes;

        private Sampler(int cellSize, List<Profile> profiles, int seed) {
            this.cellSize = cellSize;
            this.seed = mix(seed ^ MASS_SEED);
            Map<ResourceLocation, Palette> selected = new HashMap<>();
            for (Profile profile : profiles) {
                selected.put(profile.biome, new Palette(profile,
                        profile.rocks.stream().mapToInt(Rock::weight).sum(), mix(profile.biome.toString().hashCode())));
            }
            this.palettes = Map.copyOf(selected);
        }

        /** Returns null for a biome or actual block height outside its configured profile. */
        @Nullable
        public BlockState materialAt(@Nullable ResourceLocation biome, int x, int y, int z) {
            Palette palette = biome == null ? null : palettes.get(biome);
            if (palette == null || y < palette.profile.minY || y > palette.profile.maxY) return null;
            if (palette.profile.rocks.size() == 1) return palette.profile.rocks.getFirst().state;
            int choice = (int) Long.remainderUnsigned(mix(massId(x, y, z) ^ palette.salt), palette.totalWeight);
            for (Rock rock : palette.profile.rocks) {
                choice -= rock.weight;
                if (choice < 0) return rock.state;
            }
            throw new IllegalStateException("Biome rock palette weight total did not match its entries");
        }

        /** Only the context-owned surface rule uses mutable site reuse; public sampling stays pure. */
        @Nullable
        private BlockState materialAt(@Nullable ResourceLocation biome, int x, int y, int z, MassSiteCache sites) {
            Palette palette = biome == null ? null : palettes.get(biome);
            if (palette == null || y < palette.profile.minY || y > palette.profile.maxY) return null;
            if (palette.profile.rocks.size() == 1) return palette.profile.rocks.getFirst().state;
            int choice = (int) Long.remainderUnsigned(mix(massId(x, y, z, sites) ^ palette.salt), palette.totalWeight);
            for (Rock rock : palette.profile.rocks) {
                choice -= rock.weight;
                if (choice < 0) return rock.state;
            }
            throw new IllegalStateException("Biome rock palette weight total did not match its entries");
        }

        /** Identifies one warped Voronoi mass, including masses that select the same rock. */
        public long massId(int x, int y, int z) {
            double warpScale = cellSize * 2.0;
            double wx = x / warpScale;
            double wy = y / warpScale;
            double wz = z / warpScale;
            double amplitude = cellSize * .24;
            double px = (x + valueNoise(wx, wy, wz, X_SALT) * amplitude) / cellSize;
            double py = (y + valueNoise(wx, wy, wz, Y_SALT) * amplitude) / cellSize;
            double pz = (z + valueNoise(wx, wy, wz, Z_SALT) * amplitude) / cellSize;
            int cellX = (int) Math.floor(px);
            int cellY = (int) Math.floor(py);
            int cellZ = (int) Math.floor(pz);
            double nearest = Double.POSITIVE_INFINITY;
            long mass = 0;
            // Bounded Jitter Keeps The Nearest Site Within These 27 Cells.
            for (int dz = -1; dz <= 1; dz++) for (int dy = -1; dy <= 1; dy++) for (int dx = -1; dx <= 1; dx++) {
                int cx = cellX + dx;
                int cy = cellY + dy;
                int cz = cellZ + dz;
                long candidate = cellHash(cx, cy, cz, MASS_SEED);
                double distanceX = cx + .3 + .4 * unit(candidate) - px;
                double distanceY = cy + .3 + .4 * unit(mix(candidate ^ Y_SALT)) - py;
                double distanceZ = cz + .3 + .4 * unit(mix(candidate ^ Z_SALT)) - pz;
                double distance = distanceX * distanceX + distanceY * distanceY + distanceZ * distanceZ;
                if (distance < nearest) {
                    nearest = distance;
                    mass = candidate;
                }
            }
            return mass;
        }

        private long massId(int x, int y, int z, MassSiteCache sites) {
            if (sites.sampler != this) throw new IllegalArgumentException("Mass site cache belongs to another sampler");
            double warpScale = cellSize * 2.0;
            double wx = x / warpScale;
            double wy = y / warpScale;
            double wz = z / warpScale;
            double amplitude = cellSize * .24;
            double px = (x + sites.warpValueNoise(wx, wy, wz, 0) * amplitude) / cellSize;
            double py = (y + sites.warpValueNoise(wx, wy, wz, 1) * amplitude) / cellSize;
            double pz = (z + sites.warpValueNoise(wx, wy, wz, 2) * amplitude) / cellSize;
            int cellX = (int) Math.floor(px);
            int cellY = (int) Math.floor(py);
            int cellZ = (int) Math.floor(pz);
            return sites.massAt(cellX, cellY, cellZ, px, py, pz);
        }

        /** One rule context owns this cache; no mutable state is added to the public sampler. */
        private static final class MassSiteCache {
            private final Sampler sampler;
            private final long[] candidates = new long[27];
            private final double[] centersX = new double[27];
            private final double[] centersY = new double[27];
            private final double[] centersZ = new double[27];
            private boolean populated;
            private int lastCellX;
            private int lastCellY;
            private int lastCellZ;
            private final double[] warpCorners = new double[24];
            private boolean warpPopulated;
            private int warpCellX;
            private int warpCellY;
            private int warpCellZ;

            private MassSiteCache(Sampler sampler) {
                this.sampler = sampler;
            }

            /** Retains the original smooth/lerp order; only immutable corner hashes are reused. */
            private double warpValueNoise(double x, double y, double z, int axis) {
                int ix = (int) Math.floor(x);
                int iy = (int) Math.floor(y);
                int iz = (int) Math.floor(z);
                if (!warpPopulated || ix != warpCellX || iy != warpCellY || iz != warpCellZ) {
                    for (int component = 0; component < 3; component++) {
                        long salt = component == 0 ? X_SALT : component == 1 ? Y_SALT : Z_SALT;
                        int offset = component * 8;
                        warpCorners[offset] = unit(sampler.cellHash(ix, iy, iz, salt));
                        warpCorners[offset + 1] = unit(sampler.cellHash(ix + 1, iy, iz, salt));
                        warpCorners[offset + 2] = unit(sampler.cellHash(ix, iy + 1, iz, salt));
                        warpCorners[offset + 3] = unit(sampler.cellHash(ix + 1, iy + 1, iz, salt));
                        warpCorners[offset + 4] = unit(sampler.cellHash(ix, iy, iz + 1, salt));
                        warpCorners[offset + 5] = unit(sampler.cellHash(ix + 1, iy, iz + 1, salt));
                        warpCorners[offset + 6] = unit(sampler.cellHash(ix, iy + 1, iz + 1, salt));
                        warpCorners[offset + 7] = unit(sampler.cellHash(ix + 1, iy + 1, iz + 1, salt));
                    }
                    warpCellX = ix;
                    warpCellY = iy;
                    warpCellZ = iz;
                    warpPopulated = true;
                }
                double fx = smooth(x - ix);
                double fy = smooth(y - iy);
                double fz = smooth(z - iz);
                int offset = axis * 8;
                double lower = lerp(fy,
                        lerp(fx, warpCorners[offset], warpCorners[offset + 1]),
                        lerp(fx, warpCorners[offset + 2], warpCorners[offset + 3]));
                double upper = lerp(fy,
                        lerp(fx, warpCorners[offset + 4], warpCorners[offset + 5]),
                        lerp(fx, warpCorners[offset + 6], warpCorners[offset + 7]));
                return lerp(fz, lower, upper) * 2 - 1;
            }

            private long massAt(int cellX, int cellY, int cellZ, double px, double py, double pz) {
                if (!populated || cellX != lastCellX || cellY != lastCellY || cellZ != lastCellZ) {
                    int index = 0;
                    // Retain original dz/dy/dx order, integer arithmetic and center evaluation order.
                    for (int dz = -1; dz <= 1; dz++) for (int dy = -1; dy <= 1; dy++) for (int dx = -1; dx <= 1; dx++) {
                        int cx = cellX + dx;
                        int cy = cellY + dy;
                        int cz = cellZ + dz;
                        long candidate = sampler.cellHash(cx, cy, cz, MASS_SEED);
                        candidates[index] = candidate;
                        centersX[index] = cx + .3 + .4 * unit(candidate);
                        centersY[index] = cy + .3 + .4 * unit(mix(candidate ^ Y_SALT));
                        centersZ[index] = cz + .3 + .4 * unit(mix(candidate ^ Z_SALT));
                        index++;
                    }
                    lastCellX = cellX;
                    lastCellY = cellY;
                    lastCellZ = cellZ;
                    populated = true;
                }
                double nearest = Double.POSITIVE_INFINITY;
                long mass = 0;
                for (int index = 0; index < candidates.length; index++) {
                    double distanceX = centersX[index] - px;
                    double distanceY = centersY[index] - py;
                    double distanceZ = centersZ[index] - pz;
                    double distance = distanceX * distanceX + distanceY * distanceY + distanceZ * distanceZ;
                    if (distance < nearest) {
                        nearest = distance;
                        mass = candidates[index];
                    }
                }
                return mass;
            }
        }

        private double valueNoise(double x, double y, double z, long salt) {
            int ix = (int) Math.floor(x);
            int iy = (int) Math.floor(y);
            int iz = (int) Math.floor(z);
            double fx = smooth(x - ix);
            double fy = smooth(y - iy);
            double fz = smooth(z - iz);
            double lower = lerp(fy,
                    lerp(fx, unit(cellHash(ix, iy, iz, salt)), unit(cellHash(ix + 1, iy, iz, salt))),
                    lerp(fx, unit(cellHash(ix, iy + 1, iz, salt)), unit(cellHash(ix + 1, iy + 1, iz, salt))));
            double upper = lerp(fy,
                    lerp(fx, unit(cellHash(ix, iy, iz + 1, salt)), unit(cellHash(ix + 1, iy, iz + 1, salt))),
                    lerp(fx, unit(cellHash(ix, iy + 1, iz + 1, salt)), unit(cellHash(ix + 1, iy + 1, iz + 1, salt))));
            return lerp(fz, lower, upper) * 2 - 1;
        }

        private long cellHash(int x, int y, int z, long salt) {
            return mix(seed ^ x * X_SALT ^ y * Y_SALT ^ z * Z_SALT ^ salt);
        }

        private static double unit(long value) {
            return (value >>> 11) * 0x1.0p-53;
        }

        private static double smooth(double value) {
            return value * value * (3 - 2 * value);
        }

        private static double lerp(double amount, double from, double to) {
            return from + amount * (to - from);
        }

        private static long mix(long value) {
            value = (value ^ (value >>> 30)) * 0xBF58476D1CE4E5B9L;
            value = (value ^ (value >>> 27)) * 0x94D049BB133111EBL;
            return value ^ (value >>> 31);
        }

        private record Palette(Profile profile, int totalWeight, long salt) {}
    }
}
