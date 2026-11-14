package net.quiwh.cwiworldgen.terrain;

import net.quiwh.cwiworldgen.terrain.reference.cell.Cell;
import net.quiwh.cwiworldgen.terrain.reference.cell.rivermap.ContinentalHydrology;

/** Local river cross sections, evaluated before the native terrain height is committed. */
public final class ReferenceRiverMorphology {
    private final float x1, z1, ndx, ndz, length, unit, oceanWater;
    private final boolean broad;
    private final long seed;

    public ReferenceRiverMorphology(float x1, float z1, float x2, float z2,
            boolean main, int bedWidth, float unit, float oceanWater) {
        this.x1 = x1;
        this.z1 = z1;
        this.length = (float) Math.hypot(x2 - x1, z2 - z1);
        this.ndx = (x2 - x1) / this.length;
        this.ndz = (z2 - z1) / this.length;
        this.unit = unit;
        this.oceanWater = oceanWater;
        this.broad = main && bedWidth >= 7 && this.length > 500 && unit > 0;
        this.seed = mix(((long) Float.floatToIntBits(x1) << 32)
                ^ Float.floatToIntBits(z1) ^ ((long) Float.floatToIntBits(x2) << 17)
                ^ Float.floatToIntBits(z2));
    }

    public float shape(Cell cell, float x, float z, float progress, float distance,
            float bedRadius, float bankRadius, float valleyRadius, float outerRadius,
            float water, float lakeMultiplier, float flatness, float originalFinalHeight) {
        if (!broad || bedRadius < 6 || !Float.isFinite(cell.height)
                || !Float.isFinite(originalFinalHeight) || !Float.isFinite(progress)
                || lakeMultiplier >= 1.15F || water <= oceanWater + 3 * unit
                || distance >= outerRadius || progress <= .08F || progress >= .90F
                || cell.continentEdge <= .52F) return originalFinalHeight;

        float ends = smooth(.08F, .18F, progress) * (1 - smooth(.78F, .90F, progress));
        float inland = smooth(.52F, .64F, cell.continentEdge);
        float envelope = ends * inland * smooth(6, 6.5F, bedRadius)
                * (1 - smooth(1, 1.15F, lakeMultiplier))
                * smooth(3, 8, (water - oceanWater) / unit);
        if (envelope <= 0) return originalFinalHeight;
        float along = progress * length;
        // Native forward-mapped lateral coordinate; no inverse centerline is invented.
        float lateral = ndx * (z - z1) - ndz * (x - x1);
        float relief = (cell.height - water) / unit;
        float height = originalFinalHeight;
        boolean plateau = ContinentalHydrology.getStepId(cell.waterTable) >= -1;

        if (plateau && flatness > .22F) {
            float district = sample(0, 0x7C2BL) < .34F
                    ? smooth(.22F, .75F, noise(along / 310F, 0x33A5L)) : 0;
            float gorgeAmount = envelope * smooth(35, 65, relief) * district
                    * smooth(.22F, .50F, flatness);
            if (distance < bedRadius) {
                height = bar(along, lateral, bedRadius, water, height,
                        envelope * smooth(.22F, .50F, flatness)
                                * (1 - smooth(0, .20F, gorgeAmount)));
            }
            if (distance > bedRadius && relief > 35 && sample(0, 0x7C2BL) < .34F) {
                float amount = gorgeAmount;
                if (amount > 0) {
                    float variation = noise(along / 115F, 0x14B3L);
                    float side = lateral < 0 ? -1 : 1;
                    float bias = (noise(along / 270F, 0x51A2L) * 2 - 1) * side;
                    float span = (11 + 15 * variation + Math.min(120, relief) * .045F) * (1 + bias * .38F);
                    // A modest, irregular shoulder interrupts a steep wall without repeating ribs.
                    float p = clamp((distance - bedRadius - 1.5F) / span);
                    float shoulder = noise(along / 83F + side * 2, 0x27B1L);
                    float shelf = smooth(.25F, .42F, p) * (1 - smooth(.52F, .73F, p));
                    float profile = clamp(p - shelf * (.04F + .065F * shoulder));
                    float wall = water + (1.6F + relief * smooth(0, 1, profile)) * unit;
                    height = lerp(height, Math.max(height, Math.min(cell.height, wall)),
                            amount * smooth(0, 2, distance - bedRadius));
                }
            }
        }

        // Water-table values increase upstream. A plateau whose immediate upstream
        // sample enters a ramp is the lower foot of a real native water-height step.
        // This does not manufacture new water levels or depress a retaining bank.
        if (plateau && flatness < .30F && distance < bedRadius * .72F && height < water) {
            float upstream = ContinentalHydrology.getComplexWaterHeight(
                    Math.min(1, cell.waterTable + .008F), cell.globalContinentScale,
                    cell.continentSizeModifier);
            float here = ContinentalHydrology.getComplexWaterHeight(cell.waterTable,
                    cell.globalContinentScale, cell.continentSizeModifier);
            float foot = smooth(.25F, 2.5F, (upstream - here) / unit);
            float center = 1 - smooth(.20F, .72F, distance / bedRadius);
            float pocket = .55F + .45F * noise(along / 29F, 0x293DL);
            float stability = smooth(0, .035F, flatness) * (1 - smooth(.12F, .30F, flatness));
            height -= 3 * unit * envelope * foot * center * pocket * stability;
        }
        return Math.min(cell.height, Float.isFinite(height) ? height : originalFinalHeight);
    }

    private float bar(float along, float lateral, float radius, float water, float height, float envelope) {
        int index = (int) Math.floor(along / 240F);
        if (sample(index, 0x6C57L) > .46F) return height;
        float center = (index + .5F) * 240F;
        float length = 29 + 32 * sample(index, 0x44A3L);
        float width = Math.min(radius * .32F, 2.5F + sample(index, 0x22ABL));
        float offset = (sample(index, 0x1031L) * 2 - 1) * radius * .18F;
        float waviness = (noise(along / 17F, 0x63D9L) * 2 - 1) * width * .26F;
        float a = (along - center) / length;
        float b = (lateral - offset - waviness) / width;
        float footprint = a * a + b * b;
        if (footprint >= 1) return height;
        // A few crests emerge; the irregular perimeter remains a submerged shoal.
        float crest = water + (.15F + .8F * sample(index, 0x7177L)) * unit;
        float amount = envelope * (1 - smooth(.08F, 1, footprint));
        return lerp(height, Math.max(height, crest), amount);
    }

    private float noise(float coordinate, long salt) {
        int base = (int) Math.floor(coordinate);
        float t = coordinate - base;
        return lerp(sample(base, salt), sample(base + 1, salt), t * t * (3 - 2 * t));
    }

    private float sample(int coordinate, long salt) {
        return (mix(seed ^ salt ^ coordinate * 0x9E3779B97F4A7C15L) >>> 40) / 16777216F;
    }

    private static long mix(long value) {
        value ^= value >>> 30;
        value *= 0xBF58476D1CE4E5B9L;
        value ^= value >>> 27;
        value *= 0x94D049BB133111EBL;
        return value ^ value >>> 31;
    }

    private static float clamp(float value) { return Math.max(0, Math.min(1, value)); }
    private static float smooth(float low, float high, float value) {
        float t = clamp((value - low) / (high - low));
        return t * t * (3 - 2 * t);
    }
    private static float lerp(float a, float b, float t) { return a + (b - a) * t; }
}
