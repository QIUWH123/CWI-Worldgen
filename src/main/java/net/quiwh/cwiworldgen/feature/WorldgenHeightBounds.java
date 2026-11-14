package net.quiwh.cwiworldgen.feature;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;

/** Optional limits checked on final feature writes, after floor/ceiling searches. */
public record WorldgenHeightBounds(int minY, int maxY) {
    public static final WorldgenHeightBounds UNBOUNDED = new WorldgenHeightBounds(-2032, 2031);
    public static final Codec<WorldgenHeightBounds> CODEC = RecordCodecBuilder.<WorldgenHeightBounds>create(instance -> instance.group(
        Codec.intRange(-2032, 2031).fieldOf("min_y").forGetter(WorldgenHeightBounds::minY),
        Codec.intRange(-2032, 2031).fieldOf("max_y").forGetter(WorldgenHeightBounds::maxY)
    ).apply(instance, WorldgenHeightBounds::new)).validate(bounds -> bounds.minY <= bounds.maxY
        ? DataResult.success(bounds) : DataResult.error(() -> "Feature height bounds must be ordered"));

    public boolean contains(BlockPos position) { return position.getY() >= minY && position.getY() <= maxY; }
}
