package net.quiwh.cwiworldgen.terrain;

import net.quiwh.cwiworldgen.terrain.reference.noise.module.Noise;
import net.quiwh.cwiworldgen.terrain.reference.noise.module.Noises;

/** Broad tapering flanks beneath cusped ridges; used before blending and drainage. */
public final class MountainRidgeProfile {
    private MountainRidgeProfile() {}

    public static Noise create(int seed, int scale, int family, Noise foothills) {
        return create(seed, scale, family, foothills, 15);
    }

    public static Noise create(int seed, int scale, int family, Noise foothills, int generationVersion) {
        int salt = seed ^ family * 0x632BE5AB;
        int wavelength = Math.max(32, Math.round(scale * 1.95F));
        Noise axis = Noises.perlin(salt, wavelength, 2, 2.17F, .25F);
        axis = Noises.warpPerlin(axis, salt ^ 0x27B4E16F,
                Math.max(24, Math.round(wavelength * .72F)), 2, wavelength * .23F);
        // The absolute signed field retains a real ridge cusp. A smooth noise
        // maximum or a clamped distance field would produce rounded summit caps.
        Noise distance = Noises.cache2d(Noises.mul(
                Noises.abs(Noises.add(Noises.mul(axis, 2F), -1F)), 1.8F));
        if (generationVersion >= 17) {
            Noise flank = Noises.clamp(Noises.add(Noises.mul(distance, -1F / .78F), 1F), 0F, 1F);
            Noise relief = Noises.add(Noises.mul(flank, .55F),
                    Noises.mul(Noises.pow(flank, 24F), .45F));
            Noise variation = Noises.alpha(Noises.perlin(salt ^ 0x5A71C93D,
                    Math.max(32, Math.round(wavelength * .67F)), 2), .32F);
            // Sharpen only the crest source, without raising any source column.
            // The existing terrain blend separately controls the broad lower foot.
            Noise ridge = Noises.mul(Noises.mul(relief, variation), .65F);
            return Noises.add(Noises.mul(foothills, .45F), Noises.mul(ridge, .55F));
        }
        Noise flank = Noises.clamp(Noises.add(Noises.mul(distance, -1F / .78F), 1F), 0F, 1F);
        // Both terms share the same foot: the lower linear shoulder tapers
        // continuously into the valley instead of forming a raised pedestal.
        Noise relief = Noises.add(Noises.mul(flank, .65F),
                Noises.mul(Noises.pow(flank, 12F), .35F));
        Noise heightVariation = Noises.alpha(Noises.perlin(salt ^ 0x5A71C93D,
                Math.max(32, Math.round(wavelength * .67F)), 2), .32F);
        Noise ridge = Noises.mul(Noises.mul(relief, heightVariation), .65F);
        // Existing unstepped mountain mass supplies irregular buttresses and
        // secondary spurs below the sharper axes; it is not a constant pedestal.
        return Noises.add(Noises.mul(foothills, .45F), Noises.mul(ridge, .55F));
    }

    /**
     * Regional profile17 height sources on one ridge graph. The sharp source
     * supplies the owned crest; support keeps the profile16 ridge relief at
     * region borders. A lower shoulder grows only where the ridge supplies
     * enough of the existing rock mass; broad foothill summits retain support.
     * Both share the same axis, cached flank, variation and foothills, so choosing support cannot move a ridge or consume new seeds.
     * These sources affect height only; callers keep erosion and weirdness
     * on their existing paths. Profiles16 and older must use create unchanged.
     * Keep each pair seed-local: coordinate caches are shared by the two
     * outputs, and independent mapAll/serialization does not retain that sharing.
     */
    public record RidgeSources(Noise sharp, Noise support, Noise relativeRelief) {
        public RidgeSources(Noise sharp, Noise support) { this(sharp, support, null); }
    }

    public static RidgeSources createSources(int seed, int scale, int family, Noise foothills) {
        int salt = seed ^ family * 0x632BE5AB;
        int wavelength = Math.max(32, Math.round(scale * 1.95F));
        Noise axis = Noises.perlin(salt, wavelength, 2, 2.17F, .25F);
        axis = Noises.warpPerlin(axis, salt ^ 0x27B4E16F,
                Math.max(24, Math.round(wavelength * .72F)), 2, wavelength * .23F);
        Noise distance = Noises.cache2d(Noises.mul(
                Noises.abs(Noises.add(Noises.mul(axis, 2F), -1F)), 1.8F));
        Noise flank = Noises.cache2d(Noises.clamp(
                Noises.add(Noises.mul(distance, -1F / .78F), 1F), 0F, 1F));
        Noise variation = Noises.cache2d(Noises.alpha(Noises.perlin(salt ^ 0x5A71C93D,
                Math.max(32, Math.round(wavelength * .67F)), 2), .32F));
        Noise foothillMass = Noises.cache2d(Noises.mul(foothills, .45F));
        Noise sharpRelief = Noises.add(Noises.mul(flank, .55F),
                Noises.mul(Noises.pow(flank, 24F), .45F));
        Noise supportRelief = Noises.add(Noises.mul(flank, .65F),
                Noises.mul(Noises.pow(flank, 12F), .35F));
        Noise support = Noises.cache2d(combine(supportRelief, variation, foothillMass));
        Noise sharp = Noises.cache2d(combine(sharpRelief, variation, foothillMass));
        Noise potential = Noises.cache2d(Noises.max(combine(Noises.constant(1F), variation, foothillMass), .2F));
        Noise fraction = Noises.cache2d(Noises.clamp(Noises.mul(support, Noises.pow(potential, -1F)), 0F, 1F));
        Noise crest = Noises.clamp(Noises.add(Noises.mul(fraction, 2F), -1F), 0F, 1F);
        Noise crestWeight = Noises.mul(Noises.mul(crest, crest), Noises.add(Noises.mul(crest, -2F), 3F));
        // The shoulder vanishes at both the valley and crest. Existing foothill
        // mass already supports broad low summits, so it must not receive this lift.
        Noise lowerShoulder = Noises.mul(flank, Noises.pow(
                Noises.add(Noises.mul(flank, -1F), 1F), 3F));
        Noise ridgeShare = Noises.clamp(Noises.mul(
                Noises.add(support, Noises.mul(foothillMass, -1F)),
                Noises.pow(Noises.max(support, .0001F), -1F)), 0F, 1F);
        Noise ridgeWeight = Noises.clamp(Noises.add(Noises.mul(ridgeShare, 3F), -1F), 0F, 1F);
        ridgeWeight = Noises.mul(Noises.mul(ridgeWeight, ridgeWeight),
                Noises.add(Noises.mul(ridgeWeight, -2F), 3F));
        Noise footGain = Noises.mul(Noises.mul(Noises.mul(lowerShoulder, ridgeWeight), variation), 2.05F * .65F * .55F);
        Noise correction = Noises.add(Noises.mul(
                Noises.add(sharp, Noises.mul(support, -1F)), crestWeight), footGain);
        return new RidgeSources(Noises.cache2d(Noises.add(support, correction)), support, fraction);
    }

    private static Noise combine(Noise relief, Noise variation, Noise foothillMass) {
        Noise ridge = Noises.mul(Noises.mul(relief, variation), .65F);
        return Noises.add(foothillMass, Noises.mul(ridge, .55F));
    }
}
