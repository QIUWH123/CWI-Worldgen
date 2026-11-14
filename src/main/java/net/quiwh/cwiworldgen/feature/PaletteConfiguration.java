package net.quiwh.cwiworldgen.feature;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.Map;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.levelgen.feature.configurations.FeatureConfiguration;

/** Resolves content through datapack states rather than requiring another mod's registries. */
public record PaletteConfiguration(Map<String, BlockState> palette) implements FeatureConfiguration {
    public static final Codec<Map<String, BlockState>> PALETTE_CODEC = Codec.unboundedMap(Codec.STRING, BlockState.CODEC);
    public static final Codec<PaletteConfiguration> CODEC = RecordCodecBuilder.create(instance -> instance.group(
        PALETTE_CODEC.fieldOf("palette").forGetter(PaletteConfiguration::palette)
    ).apply(instance, PaletteConfiguration::new));

    public PaletteConfiguration {
        palette = Map.copyOf(palette);
    }

    public static Codec<PaletteConfiguration> codec(String... required) {
        return CODEC.validate(config -> validate(config, required));
    }

    public static DataResult<PaletteConfiguration> validate(PaletteConfiguration config, String... required) {
        for (String key : required) {
            if (!config.palette.containsKey(key)) {
                return DataResult.error(() -> "Missing worldgen palette state: " + key);
            }
        }
        return DataResult.success(config);
    }

    public BlockState state(String key) {
        BlockState state = palette.get(key);
        if (state == null) {
            throw new IllegalArgumentException("Missing worldgen palette state: " + key);
        }
        return state;
    }

    public Block block(String key) {
        return state(key).getBlock();
    }

    public static <T extends Comparable<T>> BlockState with(BlockState state, Property<T> property, T value) {
        return state.hasProperty(property) ? state.setValue(property, value) : state;
    }

    public static BlockState with(BlockState state, String name, String value) {
        Property<?> property = state.getBlock().getStateDefinition().getProperty(name);
        return property == null ? state : withSerialized(state, property, value);
    }

    private static <T extends Comparable<T>> BlockState withSerialized(BlockState state, Property<T> property, String value) {
        return property.getValue(value).map(parsed -> state.setValue(property, parsed)).orElse(state);
    }
}
