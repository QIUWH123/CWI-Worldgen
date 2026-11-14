package net.quiwh.cwiworldgen.terrain.reference.cell.terrain;

import net.quiwh.cwiworldgen.terrain.reference.noise.module.Noise;
import net.quiwh.cwiworldgen.terrain.reference.noise.module.Noises;

/** Profile-8 bedding deformation; uses existing noise nodes so terrain graphs remain serializable. */
public final class LocalTerraces {
    private LocalTerraces() {}

    /** Continuous shelves: adjacent quantization bins meet at the same elevation. */
    public static float continuous(float source, int steps) {
        float scaled = Math.max(0, Math.min(1, source)) * steps;
        float base = (float) Math.floor(scaled);
        float phase = scaled - base;
        return (base + phase * phase * (3 - 2 * phase)) / steps;
    }

    /** Derived salts do not consume the terrain selector's seed sequence. */
    public static Field field(int seed, int steps) {
        float amplitude = Math.min(.038F, .16F / steps);
        Noise uplift = Noises.add(Noises.mul(Noises.perlin(seed ^ 0x65D92B17, 420, 2), amplitude * 2), -amplitude);
        Noise strain = Noises.add(Noises.mul(Noises.perlin(seed ^ 0x3C8A49E5, 710, 2), .22F), -.11F);
        Noise weathering = Noises.add(Noises.mul(Noises.perlin(seed ^ 0x7986AB43, 170, 2), .16F), .10F);
        return new Field(uplift, strain, weathering);
    }

    public record Field(Noise uplift, Noise strain, Noise weathering) {
        public Noise apply(Noise source, Noise terraced) {
            Noise envelope = Noises.clamp(Noises.mul(source, 4), 0, 1);
            Noise displacement = Noises.add(uplift, Noises.mul(Noises.add(terraced, -.5F), strain));
            Noise shelves = Noises.clamp(Noises.add(terraced, Noises.mul(envelope, displacement)), 0, 1);
            return Noises.blend(Noises.clamp(weathering, 0, 1), shelves, source, .5F, 1);
        }

        /** Same policy for island relief, which is assembled directly rather than as a noise graph. */
        public float sample(float source, float terraced, float x, float z) {
            float envelope = clamp(source * 4);
            float displacement = uplift.compute(x, z, 0) + (terraced - .5F) * strain.compute(x, z, 0);
            float shelves = clamp(terraced + envelope * displacement);
            float weathered = weathering.compute(x, z, 0);
            return shelves + (source - shelves) * weathered;
        }

        private static float clamp(float value) { return Math.max(0, Math.min(1, value)); }
    }
}
