package net.quiwh.cwiworldgen.terrain;

/** Compact interpolation weights: no influence outside the caller's existing blend interval. */
public final class CompactTerrainTransitions {
    private CompactTerrainTransitions() {}

    public static float weight(float alpha, boolean mountainFoot) {
        if (alpha <= 0) return 0;
        if (alpha >= 1) return 1;
        if (mountainFoot) return (float) ((double) alpha * alpha * alpha
                * (alpha * (alpha * 6.0 - 15.0) + 10.0));
        return alpha * alpha * (3F - 2F * alpha);
    }

    /** Broader lower flanks inside the original interval; preserves both blend endpoints. */
    public static float broadMountainFoot(float weight) {
        if (weight <= 0) return 0;
        if (weight >= 1) return 1;
        float remainder = 1F - weight;
        float entry = Math.min(1F, weight * 4F);
        entry = entry * entry * (3F - 2F * entry);
        return weight + 1.5F * weight * remainder * remainder * entry;
    }

    /** Regional support concentrates near the foot, leaving upper slopes sharply tapered. */
    public static float regionalMountainFoot(float weight) {
        if (weight <= 0) return 0;
        if (weight >= 1) return 1;
        float remainder = 1F - weight;
        // Keep the outer regional fringe low; add the extra shoulder only as
        // the mountain starts to own the slope. The original foot remains.
        float shoulder = weight((weight - .28F) / .075F, false);
        return weight + (3.5F + 1.4F * shoulder) * weight * weight * remainder * remainder * remainder;
    }
}
