package net.quiwh.cwiworldgen.feature;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.List;
import java.util.Optional;
import net.minecraft.tags.TagKey;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.feature.configurations.FeatureConfiguration;

/** A mineral body with smaller actual-ore pockets; all sizes are block radii. */
public record IndustrialDepositConfiguration(TagKey<Block> replaceable, int radiusX, int radiusY, int radiusZ,
        float oreFraction, List<Material> hosts, List<Material> ores, boolean surfaceIndicators,
        List<String> morphologies, VeinSizeSettings sizeSettings) implements FeatureConfiguration {
    public IndustrialDepositConfiguration(TagKey<Block> replaceable, int radiusX, int radiusY, int radiusZ,
            float oreFraction, List<Material> hosts, List<Material> ores, boolean surfaceIndicators) {
        this(replaceable, radiusX, radiusY, radiusZ, oreFraction, hosts, ores, surfaceIndicators,
            List.of("massive"), VeinSizeSettings.FIXED);
    }
    public IndustrialDepositConfiguration(TagKey<Block> replaceable, int radiusX, int radiusY, int radiusZ,
            float oreFraction, List<Material> hosts, List<Material> ores, boolean surfaceIndicators, List<String> morphologies) {
        this(replaceable, radiusX, radiusY, radiusZ, oreFraction, hosts, ores, surfaceIndicators,
            morphologies, VeinSizeSettings.FIXED);
    }
    public IndustrialDepositConfiguration(TagKey<Block> replaceable, int radiusX, int radiusY, int radiusZ,
            float oreFraction, List<Material> hosts, List<Material> ores) {
        this(replaceable, radiusX, radiusY, radiusZ, oreFraction, hosts, ores, false);
    }
    private static final Codec<List<Material>> PALETTE = Material.CODEC.listOf().validate(entries ->
        entries.isEmpty() || entries.size() > 16
            ? DataResult.error(() -> "Industrial deposit palettes require 1..16 materials")
            : DataResult.success(List.copyOf(entries)));

    private static final Codec<List<String>> MORPHOLOGIES = Codec.STRING.listOf().validate(entries ->
        entries.isEmpty() || entries.size() > 5 || entries.stream().anyMatch(entry -> !List.of(
            "cubic", "massive", "bedded", "crystalline", "nodular").contains(entry))
                ? DataResult.error(() -> "Industrial morphologies require 1..5 of cubic, massive, bedded, crystalline, nodular")
                : DataResult.success(List.copyOf(entries)));

    public static final Codec<IndustrialDepositConfiguration> CODEC = RecordCodecBuilder.create(instance -> instance.group(
        TagKey.codec(Registries.BLOCK).fieldOf("replaceable").forGetter(IndustrialDepositConfiguration::replaceable),
        Codec.intRange(2, 7).fieldOf("radius_x").forGetter(IndustrialDepositConfiguration::radiusX),
        Codec.intRange(2, 16).fieldOf("radius_y").forGetter(IndustrialDepositConfiguration::radiusY),
        Codec.intRange(2, 7).fieldOf("radius_z").forGetter(IndustrialDepositConfiguration::radiusZ),
        Codec.floatRange(0.01F, 0.35F).fieldOf("ore_fraction").forGetter(IndustrialDepositConfiguration::oreFraction),
        PALETTE.fieldOf("hosts").forGetter(IndustrialDepositConfiguration::hosts),
        PALETTE.fieldOf("ores").forGetter(IndustrialDepositConfiguration::ores),
        Codec.BOOL.optionalFieldOf("surface_indicators", false).forGetter(IndustrialDepositConfiguration::surfaceIndicators),
        MORPHOLOGIES.optionalFieldOf("morphologies", List.of("massive")).forGetter(IndustrialDepositConfiguration::morphologies),
        VeinSizeSettings.CODEC.optionalFieldOf("size_settings", VeinSizeSettings.FIXED).forGetter(IndustrialDepositConfiguration::sizeSettings)
    ).apply(instance, IndustrialDepositConfiguration::new));

    public record Material(BlockState state, Optional<BlockState> deepState, int weight) {
        public static final Codec<Material> CODEC = RecordCodecBuilder.<Material>create(instance -> instance.group(
            BlockState.CODEC.fieldOf("state").forGetter(Material::state),
            BlockState.CODEC.optionalFieldOf("deep_state").forGetter(Material::deepState),
            Codec.intRange(1, 1000).fieldOf("weight").forGetter(Material::weight)
        ).apply(instance, Material::new)).validate(material -> valid(material.state)
            && material.deepState.map(Material::valid).orElse(true)
                ? DataResult.success(material)
                : DataResult.error(() -> "Deposit materials must be solid, fluid-free blocks without block entities"));

        private static boolean valid(BlockState state) {
            return !state.isAir() && state.getFluidState().isEmpty() && !state.hasBlockEntity()
                && state.blocksMotion();
        }

        public BlockState atDepth(int y) {
            return y < 0 ? deepState.orElse(state) : state;
        }
    }
}
