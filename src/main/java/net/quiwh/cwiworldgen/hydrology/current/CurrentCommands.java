package net.quiwh.cwiworldgen.hydrology.current;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.DynamicCommandExceptionType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.commands.arguments.coordinates.Vec2Argument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

/** Operator controls author metadata in existing vanilla water; they never carve or place water. */
public final class CurrentCommands {
    private static final DynamicCommandExceptionType ERROR = new DynamicCommandExceptionType(value -> Component.literal(value.toString()));
    private CurrentCommands() {}
    public static void register() { NeoForge.EVENT_BUS.addListener(CurrentCommands::registered); }
    private static void registered(RegisterCommandsEvent event) { register(event.getDispatcher()); }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        var root = Commands.literal("cwi_currents").requires(source -> source.hasPermission(2)).executes(CurrentCommands::help);
        root.then(Commands.literal("help").executes(CurrentCommands::help));
        root.then(Commands.literal("list").executes(context -> {
            var data = CurrentOverrides.get(level(context));
            reply(context, data.manualCount() + " manual edits; " + data.reflowCount() + " persisted reflow chunks");
            for (String entry : data.summaries()) reply(context, entry);
            return data.manualCount();
        }));
        root.then(Commands.literal("path").then(Commands.literal("add")
                .then(Commands.argument("id", StringArgumentType.word())
                .then(Commands.argument("from", Vec2Argument.vec2(false))
                .then(Commands.argument("to", Vec2Argument.vec2(false))
                .then(Commands.argument("min_y", IntegerArgumentType.integer(CurrentOverrides.MIN_Y, CurrentOverrides.MAX_Y))
                .then(Commands.argument("max_y", IntegerArgumentType.integer(CurrentOverrides.MIN_Y, CurrentOverrides.MAX_Y))
                .then(Commands.argument("radius", DoubleArgumentType.doubleArg(.5, 32))
                .then(Commands.argument("speed", DoubleArgumentType.doubleArg(.001, CurrentColumn.MAX_SPEED)).executes(context -> change(context, () -> {
                    var from = Vec2Argument.getVec2(context, "from"); var to = Vec2Argument.getVec2(context, "to");
                    CurrentOverrides.get(level(context)).addPath(StringArgumentType.getString(context, "id"), from.x, from.y, to.x, to.y,
                            IntegerArgumentType.getInteger(context, "min_y"), IntegerArgumentType.getInteger(context, "max_y"),
                            DoubleArgumentType.getDouble(context, "radius"), DoubleArgumentType.getDouble(context, "speed"));
                    return "Current path saved; only actual water within its height and radius receives flow";
                })))))))))));
        root.then(Commands.literal("still").then(Commands.literal("add")
                .then(Commands.argument("id", StringArgumentType.word())
                .then(Commands.argument("center", Vec2Argument.vec2(false))
                .then(Commands.argument("min_y", IntegerArgumentType.integer(CurrentOverrides.MIN_Y, CurrentOverrides.MAX_Y))
                .then(Commands.argument("max_y", IntegerArgumentType.integer(CurrentOverrides.MIN_Y, CurrentOverrides.MAX_Y))
                .then(Commands.argument("radius", DoubleArgumentType.doubleArg(.5, 32)).executes(context -> change(context, () -> {
                    var center = Vec2Argument.getVec2(context, "center");
                    CurrentOverrides.get(level(context)).addStill(StringArgumentType.getString(context, "id"), center.x, center.y,
                            IntegerArgumentType.getInteger(context, "min_y"), IntegerArgumentType.getInteger(context, "max_y"),
                            DoubleArgumentType.getDouble(context, "radius"));
                    return "Still mask saved; its height interval suppresses flowing currents";
                })))))))));
        root.then(Commands.literal("remove").then(Commands.argument("id", StringArgumentType.word()).executes(context -> change(context, () -> {
            if (!CurrentOverrides.get(level(context)).remove(StringArgumentType.getString(context, "id"))) throw new IllegalArgumentException("No manual current edit has that id");
            return "Manual current edit removed";
        }))));
        var clear = Commands.literal("clear");
        clear.then(Commands.literal("manual").executes(context -> change(context, () -> { CurrentOverrides.get(level(context)).clearManual(); return "Manual current edits cleared"; })));
        clear.then(Commands.literal("reflow").executes(context -> change(context, () -> {
            var level = level(context); ExistingWaterReflow.cancel(level); CurrentOverrides.get(level).clearReflow(); return "Reflow metadata cleared";
        })));
        clear.then(Commands.literal("all").executes(context -> change(context, () -> {
            var level = level(context); ExistingWaterReflow.cancel(level); var data = CurrentOverrides.get(level); data.clearManual(); data.clearReflow(); return "All manual and reflow metadata cleared";
        })));
        root.then(clear);
        root.then(Commands.literal("inspect").then(Commands.argument("pos", BlockPosArgument.blockPos()).executes(context -> {
            var level = level(context); BlockPos pos = BlockPosArgument.getBlockPos(context, "pos");
            var field = ServerCurrentFields.fieldIfPresent(level, pos.getX() >> 4, pos.getZ() >> 4);
            var chunk = level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4);
            CurrentColumn sampled = RuntimeCurrentSampler.sample(field, chunk, pos);
            reply(context, field == null ? "No cached current field at this position; inspection does not load or build chunks"
                    : !sampled.plannedWater() ? "No applicable current in actual water at this height"
                    : (sampled.active() ? "Flow speed=" + sampled.speed() + " heading=" + sampled.heading() : "Still water")
                            + " y=" + sampled.minY() + ".." + sampled.maxY());
            return sampled.active() ? 1 : 0;
        })));
        var reflow = Commands.literal("reflow");
        reflow.then(Commands.literal("start").then(Commands.argument("center", BlockPosArgument.blockPos())
                .then(Commands.argument("radius", IntegerArgumentType.integer(4, ExistingWaterReflow.MAX_RADIUS)).executes(context -> {
                    try {
                        int result = ExistingWaterReflow.start(level(context), BlockPosArgument.getBlockPos(context, "center"), IntegerArgumentType.getInteger(context, "radius"));
                        reply(context, "Reflow started for loaded water only; use /cwi_currents reflow status"); return result;
                    } catch (IllegalArgumentException failure) { throw ERROR.create(failure.getMessage()); }
                }))));
        reflow.then(Commands.literal("status").executes(context -> { reply(context, ExistingWaterReflow.status(level(context))); return 1; }));
        reflow.then(Commands.literal("cancel").executes(context -> { boolean cancelled = ExistingWaterReflow.cancel(level(context)); reply(context, cancelled ? "Reflow cancelled" : "No running reflow"); return cancelled ? 1 : 0; }));
        root.then(reflow); dispatcher.register(root);
    }
    private static ServerLevel level(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        ServerLevel level = context.getSource().getLevel(); var terrain = ServerCurrentFields.terrain(level);
        if (level.dimension() != Level.OVERWORLD || terrain == null || terrain.generationVersion() < 2)
            throw ERROR.create("Current controls require a CWI Overworld with runtime currents (generation 2 or later)");
        return level;
    }
    private static int help(CommandContext<CommandSourceStack> context) {
        reply(context, "/cwi_currents path add <id> <fromX> <fromZ> <toX> <toZ> <minY> <maxY> <radius> <speed>");
        reply(context, "/cwi_currents still add <id> <x> <z> <minY> <maxY> <radius>");
        reply(context, "/cwi_currents list | remove <id> | clear manual/reflow/all | inspect <x> <y> <z>");
        reply(context, "/cwi_currents reflow start <x> <y> <z> <radius> | reflow status | reflow cancel");
        return 1;
    }
    private static void reply(CommandContext<CommandSourceStack> context, String message) { context.getSource().sendSuccess(() -> Component.literal(message), false); }
    @FunctionalInterface private interface Change { String run() throws CommandSyntaxException; }
    private static int change(CommandContext<CommandSourceStack> context, Change mutation) throws CommandSyntaxException {
        ServerLevel level = level(context);
        try { String message = mutation.run(); ServerCurrentFields.invalidate(level); reply(context, message); return 1; }
        catch (IllegalArgumentException failure) { throw ERROR.create(failure.getMessage()); }
    }
}
