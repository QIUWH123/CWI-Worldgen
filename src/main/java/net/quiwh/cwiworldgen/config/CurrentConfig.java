package net.quiwh.cwiworldgen.config;

import net.neoforged.fml.ModContainer;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.common.ModConfigSpec;

/** Independent gameplay and local presentation controls for natural CWI rivers. */
public final class CurrentConfig {
    private CurrentConfig() {}
    private static final ModConfigSpec.Builder SERVER_BUILDER = new ModConfigSpec.Builder();
    public static final ModConfigSpec.BooleanValue ENABLED = SERVER_BUILDER
            .comment("Enable directed currents in generation-version-2 CWI rivers; vanilla water remains unchanged.")
            .define("riverCurrents", true);
    public static final ModConfigSpec.DoubleValue STRENGTH = SERVER_BUILDER
            .comment("Conservative current acceleration/speed multiplier; paddling can overcome the current.")
            .defineInRange("currentStrength", 1.0, 0.0, 2.0);
    public static final ModConfigSpec.DoubleValue PLAYER_STRENGTH = SERVER_BUILDER
            .comment("Additional directed-current acceleration for locally controlled players.")
            .defineInRange("playerCurrentStrength", 1.0, 0.0, 2.0);
    public static final ModConfigSpec.DoubleValue MOB_STRENGTH = SERVER_BUILDER
            .comment("Additional directed-current acceleration for ordinary non-player entities.")
            .defineInRange("mobCurrentStrength", 1.0, 0.0, 2.0);
    public static final ModConfigSpec.DoubleValue BOAT_STRENGTH = SERVER_BUILDER
            .comment("Directed-current acceleration for the boat's single motion owner.")
            .defineInRange("boatCurrentStrength", 1.0, 0.0, 2.0);
    public static final ModConfigSpec.DoubleValue ITEM_DRIFT_SPEED = SERVER_BUILDER
            .comment("Maximum target speed along a current for items and drift-tagged entities, blocks/second.",
                    "Drag approaches that target gradually and preserves sideways motion. Zero disables drift.")
            .defineInRange("itemDriftSpeed", 1.5, 0.0, 4.0);
    public static final ModConfigSpec.BooleanValue AQUATIC_HOLD = SERVER_BUILDER
            .comment("Aquatic mobs hold position against the additional CWI current; vanilla fluid physics stays unchanged.")
            .define("waterMobsIgnoreCurrent", true);
    public static final ModConfigSpec.BooleanValue BOAT_SURFACE = SERVER_BUILDER
            .comment("Gently follow actual loaded water surfaces inside planned current intervals. Legacy profile1 keeps its lift.")
            .define("boatSurfaceFollowing", true);
    public static final ModConfigSpec.IntValue BUILDS_PER_TICK = SERVER_BUILDER
            .comment("Maximum background field jobs dispatched per server tick across all levels; one worker, bounded queue.")
            .defineInRange("fieldBuildsPerTick", 1, 1, 4);
    public static final ModConfigSpec SERVER = SERVER_BUILDER.build();
    private static final ModConfigSpec.Builder CLIENT_BUILDER = new ModConfigSpec.Builder();
    public static final ModConfigSpec.BooleanValue AUDIO = CLIENT_BUILDER.define("waterfallAudio", true);
    public static final ModConfigSpec.DoubleValue VOLUME = CLIENT_BUILDER.defineInRange("waterfallVolume", .22, 0, 1);
    public static final ModConfigSpec.BooleanValue STREAM_AUDIO = CLIENT_BUILDER.define("gentleStreamAudio", true);
    public static final ModConfigSpec.DoubleValue STREAM_VOLUME = CLIENT_BUILDER
            .comment("Quiet vanilla-water ambience for gentle currents, independent of waterfall volume.")
            .defineInRange("gentleStreamVolume", .055, 0, .25);
    public static final ModConfigSpec.IntValue VOICES = CLIENT_BUILDER
            .comment("Total simultaneous water voices across gentle streams and waterfalls.")
            .defineInRange("waterfallVoices", 2, 0, 3);
    public static final ModConfigSpec CLIENT = CLIENT_BUILDER.build();
    public static void register(ModContainer container) {
        container.registerConfig(ModConfig.Type.SERVER, SERVER);
        container.registerConfig(ModConfig.Type.CLIENT, CLIENT);
    }
}
