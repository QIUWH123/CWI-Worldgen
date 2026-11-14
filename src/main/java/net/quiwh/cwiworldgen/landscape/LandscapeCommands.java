package net.quiwh.cwiworldgen.landscape;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.DynamicCommandExceptionType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import java.util.Arrays;
import java.util.stream.Collectors;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.ResourceLocationArgument;
import net.minecraft.commands.arguments.ResourceOrTagArgument;
import net.minecraft.commands.arguments.ResourceOrTagKeyArgument;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.quiwh.cwiworldgen.search.SearchService;

/** Consistent resumable terrain, biome and structure locate; POI remains native. */
public final class LandscapeCommands {
    private static final DynamicCommandExceptionType ERROR = new DynamicCommandExceptionType(
            message -> Component.literal(message.toString()));
    private LandscapeCommands() {}

    public static void register() {
        SearchService.registerEvents();
        NeoForge.EVENT_BUS.addListener((RegisterCommandsEvent event) ->
                register(event.getDispatcher(), event.getBuildContext()));
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher, CommandBuildContext buildContext) {
        register(dispatcher);
        dispatcher.register(Commands.literal("locate").requires(source -> source.hasPermission(2))
                .then(Commands.literal("biome")
                        .then(Commands.argument("biome", ResourceOrTagArgument.resourceOrTag(buildContext, Registries.BIOME))
                                .executes(context -> biome(context, LandscapeSearch.DEFAULT_RADIUS))
                                .then(Commands.argument("range", IntegerArgumentType.integer(64, LandscapeSearch.MAX_RADIUS))
                                        .executes(context -> biome(context, IntegerArgumentType.getInteger(context, "range"))))))
                .then(Commands.literal("structure")
                        .then(Commands.argument("structure", ResourceOrTagKeyArgument.resourceOrTagKey(Registries.STRUCTURE))
                                .executes(context -> structure(context, LandscapeSearch.DEFAULT_RADIUS))
                                .then(Commands.argument("range", IntegerArgumentType.integer(64, LandscapeSearch.MAX_RADIUS))
                                        .executes(context -> structure(context, IntegerArgumentType.getInteger(context, "range")))))));
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        var landscape = Commands.literal("landform").executes(LandscapeCommands::help)
                .then(Commands.literal("list").executes(context -> {
                    reply(context.getSource(), Arrays.stream(LandscapeType.values()).map(LandscapeType::id)
                            .collect(Collectors.joining(", ")));
                    return LandscapeType.values().length;
                }))
                .then(Commands.literal("here").executes(context -> SearchService.startLandform(context.getSource(), null, 1)));
        dispatcher.register(Commands.literal("cwiworldgen").requires(source -> source.hasPermission(2)).then(landscape));
        dispatcher.register(Commands.literal("locate").requires(source -> source.hasPermission(2))
                .then(Commands.literal("landform")
                        .then(Commands.argument("type", ResourceLocationArgument.id())
                                .suggests((context, builder) -> SharedSuggestionProvider.suggest(
                                        Arrays.stream(LandscapeType.values()).map(LandscapeType::id), builder))
                                .executes(context -> locate(context, LandscapeSearch.DEFAULT_RADIUS))
                                .then(Commands.argument("range", IntegerArgumentType.integer(64, LandscapeSearch.MAX_RADIUS))
                                        .executes(context -> locate(context, IntegerArgumentType.getInteger(context, "range")))))));
    }

    private static int locate(CommandContext<CommandSourceStack> context, int radius) throws CommandSyntaxException {
        var id = ResourceLocationArgument.getId(context, "type");
        var type = LandscapeType.parse(id.getNamespace().equals("minecraft") ? id.getPath() : id.toString());
        if (type == null) throw ERROR.create("Unknown landform. Use /cwiworldgen landform list");
        return SearchService.startLandform(context.getSource(), type, radius);
    }

    private static int biome(CommandContext<CommandSourceStack> context, int range) throws CommandSyntaxException {
        return SearchService.startBiome(context.getSource(),
                ResourceOrTagArgument.getResourceOrTag(context, "biome", Registries.BIOME), range);
    }

    private static int structure(CommandContext<CommandSourceStack> context, int range) throws CommandSyntaxException {
        ResourceOrTagKeyArgument.Result<Structure> target = ResourceOrTagKeyArgument.getResourceOrTagKey(
                context, "structure", Registries.STRUCTURE, ERROR);
        return SearchService.startStructure(context.getSource(), target, range);
    }

    private static int help(CommandContext<CommandSourceStack> context) {
        reply(context.getSource(), "/cwiworldgen landform list | here");
        reply(context.getSource(), "/locate landform <type> [range] | biome <id or #tag> [range] | structure <id or #tag> [range]");
        return 1;
    }

    private static void reply(CommandSourceStack source, String message) {
        source.sendSuccess(() -> Component.literal(message), false);
    }
}
