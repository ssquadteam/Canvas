package io.canvasmc.canvas.subcommands;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import io.canvasmc.canvas.commands.SubCommand;
import io.canvasmc.canvas.worldgen.BatchWorldGen;
import io.canvasmc.canvas.worldgen.VanillaComparison;
import io.canvasmc.canvas.worldgen.LiveWorldGen;
import io.canvasmc.canvas.worldgen.WorldGenPipeline;
import io.canvasmc.canvas.worldgen.WorldGenSweep;
import io.canvasmc.canvas.worldgen.WorldGenVerifier;
import java.util.Locale;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.permissions.Permissions;
import net.minecraft.util.Mth;
import net.minecraft.util.CommonColors;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;

import static net.minecraft.commands.Commands.argument;
import static net.minecraft.commands.Commands.literal;

public class WorldGenSubCommand implements SubCommand {

    private static final String[] STATUS_NAMES = {
        "structure_starts", "structure_references", "biomes", "noise", "surface", "carvers", "features"
    };

    @Override
    public String getName() {
        return "worldgen";
    }

    @Override
    public String getDescription() {
        return "Verifies and benchmarks the batched world generator.";
    }

    @Override
    public LiteralArgumentBuilder<CommandSourceStack> construct(final LiteralArgumentBuilder<CommandSourceStack> base, final CommandBuildContext buildContext) {
        return base.requires(stack -> stack.hasPermission(Permissions.COMMANDS_ADMIN, "canvas.command.worldgen"))
            .then(literal("verify")
                .then(argument("size", IntegerArgumentType.integer(1, 16))
                    .executes(context -> verify(context, IntegerArgumentType.getInteger(context, "size")))))
            .then(literal("vanilla")
                .then(argument("size", IntegerArgumentType.integer(1, 8))
                    .executes(context -> vanilla(context, IntegerArgumentType.getInteger(context, "size"), "features", 2048))
                    .then(argument("status", StringArgumentType.word())
                        .suggests((context, builder) -> SharedSuggestionProvider.suggest(STATUS_NAMES, builder))
                        .executes(context -> vanilla(context, IntegerArgumentType.getInteger(context, "size"), StringArgumentType.getString(context, "status"), 2048))
                        .then(argument("offset", IntegerArgumentType.integer(64, 100000))
                            .executes(context -> vanilla(context, IntegerArgumentType.getInteger(context, "size"), StringArgumentType.getString(context, "status"), IntegerArgumentType.getInteger(context, "offset")))))))
            .then(literal("order")
                .then(argument("size", IntegerArgumentType.integer(1, 16))
                    .executes(context -> order(context, IntegerArgumentType.getInteger(context, "size")))))
            .then(literal("sweep")
                .then(argument("tile", IntegerArgumentType.integer(1, 256))
                    .then(argument("tiles", IntegerArgumentType.integer(1, 64))
                        .then(argument("threads", IntegerArgumentType.integer(1, 64))
                            .then(argument("offset", IntegerArgumentType.integer(64, 200000))
                                .executes(context -> sweep(
                                    context,
                                    IntegerArgumentType.getInteger(context, "tile"),
                                    IntegerArgumentType.getInteger(context, "tiles"),
                                    IntegerArgumentType.getInteger(context, "threads"),
                                    IntegerArgumentType.getInteger(context, "offset")
                                )))))))
            .then(literal("pipeline")
                .then(argument("width", IntegerArgumentType.integer(1, 512))
                    .then(argument("height", IntegerArgumentType.integer(1, 512))
                        .then(argument("offset", IntegerArgumentType.integer(64, 200000))
                            .executes(context -> pipeline(
                                context,
                                IntegerArgumentType.getInteger(context, "width"),
                                IntegerArgumentType.getInteger(context, "height"),
                                IntegerArgumentType.getInteger(context, "offset"),
                                1
                            ))
                            .then(argument("threads", IntegerArgumentType.integer(1, 32))
                                .executes(context -> pipeline(
                                    context,
                                    IntegerArgumentType.getInteger(context, "width"),
                                    IntegerArgumentType.getInteger(context, "height"),
                                    IntegerArgumentType.getInteger(context, "offset"),
                                    IntegerArgumentType.getInteger(context, "threads")
                                )))))))
            .then(literal("vanillabench")
                .then(argument("size", IntegerArgumentType.integer(1, 256))
                    .then(argument("offset", IntegerArgumentType.integer(64, 200000))
                        .executes(context -> vanillaBench(
                            context,
                            IntegerArgumentType.getInteger(context, "size"),
                            IntegerArgumentType.getInteger(context, "offset")
                        )))))
            .then(literal("anchor")
                .then(argument("chunkX", IntegerArgumentType.integer(-1000000, 1000000))
                    .then(argument("chunkZ", IntegerArgumentType.integer(-1000000, 1000000))
                        .executes(context -> {
                            LiveWorldGen.anchor(
                                context.getSource().getLevel(),
                                IntegerArgumentType.getInteger(context, "chunkX"),
                                IntegerArgumentType.getInteger(context, "chunkZ")
                            );
                            context.getSource().sendSystemMessage(Component.literal("anchor added"));
                            return Command.SINGLE_SUCCESS;
                        })))
                .then(literal("clear")
                    .executes(context -> {
                        LiveWorldGen.clearAnchors();
                        context.getSource().sendSystemMessage(Component.literal("anchors cleared"));
                        return Command.SINGLE_SUCCESS;
                    })))
            .then(literal("status")
                .executes(context -> {
                    context.getSource().sendSystemMessage(Component.literal(LiveWorldGen.status()));
                    return Command.SINGLE_SUCCESS;
                }))
            .then(literal("bench")
                .then(argument("size", IntegerArgumentType.integer(1, 32))
                    .executes(context -> bench(context, IntegerArgumentType.getInteger(context, "size")))));
    }

    private static int verify(final CommandContext<CommandSourceStack> context, final int size) {
        final CommandSourceStack source = context.getSource();
        final ServerLevel level = source.getLevel();
        final int minChunkX = Mth.floor(source.getPosition().x()) >> 4;
        final int minChunkZ = Mth.floor(source.getPosition().z()) >> 4;

        source.sendSystemMessage(Component.literal("Generating " + (size * size) + " chunks both ways, this blocks the region..."));

        final WorldGenVerifier.Result result = WorldGenVerifier.verify(level, minChunkX, minChunkZ, size, ChunkStatus.FEATURES);
        final String summary = String.format(
            Locale.ROOT,
            "%d chunks, %d blocks compared, %d mismatches. batch %.2fs, wide reference %.2fs",
            result.chunks(), result.blocks(), result.mismatches(),
            result.batchNanos() / 1.0E9, result.wideNanos() / 1.0E9
        );

        source.sendSystemMessage(Component.literal(summary).withColor(result.identical() ? CommonColors.GREEN : CommonColors.RED));
        if (!result.identical()) {
            source.sendSystemMessage(Component.literal(result.firstMismatch()).withColor(CommonColors.RED));
        }
        return Command.SINGLE_SUCCESS;
    }

    private static int vanilla(final CommandContext<CommandSourceStack> context, final int size, final String statusName, final int offset) {
        final CommandSourceStack source = context.getSource();
        final ServerLevel level = source.getLevel();
        final ChunkStatus target = ChunkStatus.byName(statusName);
        if (target == null || target == ChunkStatus.EMPTY) {
            source.sendSystemMessage(Component.literal("unknown status " + statusName).withColor(CommonColors.RED));
            return 0;
        }

        // somewhere the world has not generated yet, so the chunk system really generates instead of loading a chunk
        // that a previous run already took past this status
        final int minChunkX = (Mth.floor(source.getPosition().x()) >> 4) + offset;
        final int minChunkZ = (Mth.floor(source.getPosition().z()) >> 4) + offset;

        source.sendSystemMessage(Component.literal("Comparing " + (size * size) + " chunks at " + target.getName() + " against the chunk system, this runs in the background..."));
        VanillaComparison.run(level, minChunkX, minChunkZ, size, target,
            line -> source.getServer().sendSystemMessage(Component.literal("[worldgen] " + line)));
        return Command.SINGLE_SUCCESS;
    }

    private static int order(final CommandContext<CommandSourceStack> context, final int size) {
        final CommandSourceStack source = context.getSource();
        final ServerLevel level = source.getLevel();
        final int minChunkX = (Mth.floor(source.getPosition().x()) >> 4) + 7000;
        final int minChunkZ = (Mth.floor(source.getPosition().z()) >> 4) + 7000;

        source.sendSystemMessage(Component.literal("Generating " + (size * size) + " chunks in both orders, this blocks the region..."));

        final WorldGenVerifier.Result result = WorldGenVerifier.order(level, minChunkX, minChunkZ, size, ChunkStatus.FEATURES);
        source.sendSystemMessage(Component.literal(String.format(
            Locale.ROOT,
            "%d chunks, %d blocks compared, %d order dependent blocks. forward %.2fs, reverse %.2fs",
            result.chunks(), result.blocks(), result.mismatches(),
            result.batchNanos() / 1.0E9, result.wideNanos() / 1.0E9
        )).withColor(result.identical() ? CommonColors.GREEN : CommonColors.RED));
        if (!result.identical()) {
            source.sendSystemMessage(Component.literal(result.firstMismatch()).withColor(CommonColors.RED));
        }
        return Command.SINGLE_SUCCESS;
    }

    private static int sweep(final CommandContext<CommandSourceStack> context, final int tile, final int tiles, final int threads, final int offset) {
        final CommandSourceStack source = context.getSource();
        final ServerLevel level = source.getLevel();
        final int minChunkX = (Mth.floor(source.getPosition().x()) >> 4) + offset;
        final int minChunkZ = (Mth.floor(source.getPosition().z()) >> 4) + offset;

        source.sendSystemMessage(Component.literal("Sweeping " + (tiles * tiles) + " tiles, this runs in the background..."));
        WorldGenSweep.run(level, minChunkX, minChunkZ, tile, tiles, threads, ChunkStatus.FEATURES,
            line -> source.getServer().sendSystemMessage(Component.literal("[worldgen] " + line)));
        return Command.SINGLE_SUCCESS;
    }

    private static int pipeline(final CommandContext<CommandSourceStack> context, final int width, final int height, final int offset, final int threads) {
        final CommandSourceStack source = context.getSource();
        final ServerLevel level = source.getLevel();
        final int minChunkX = (Mth.floor(source.getPosition().x()) >> 4) + offset;
        final int minChunkZ = (Mth.floor(source.getPosition().z()) >> 4) + offset;

        source.sendSystemMessage(Component.literal("Piping " + (width * height) + " chunks, this runs in the background..."));
        final Thread worker = new Thread(() -> {
            try {
                final java.util.concurrent.ExecutorService pool = threads > 1
                    ? java.util.concurrent.Executors.newFixedThreadPool(threads)
                    : null;
                final WorldGenPipeline.Result result;
                try {
                    result = WorldGenPipeline.generate(level, minChunkX, minChunkZ, width, height, ChunkStatus.FEATURES, threads, pool);
                } finally {
                    if (pool != null) {
                        pool.shutdownNow();
                    }
                }
                final double seconds = result.nanos() / 1.0E9;
                source.getServer().sendSystemMessage(Component.literal(String.format(
                    Locale.ROOT, "[worldgen] pipeline %d chunks in %.2fs, %.1f chunks/s, %d written",
                    result.chunks(), seconds, result.chunks() / seconds, result.saved()
                )));
            } catch (final Throwable thr) {
                source.getServer().sendSystemMessage(Component.literal("[worldgen] pipeline failed: " + thr));
            }
        }, "canvas-worldgen-pipeline");
        worker.setDaemon(true);
        worker.start();
        return Command.SINGLE_SUCCESS;
    }

    private static int vanillaBench(final CommandContext<CommandSourceStack> context, final int size, final int offset) {
        final CommandSourceStack source = context.getSource();
        final ServerLevel level = source.getLevel();
        final int minChunkX = (Mth.floor(source.getPosition().x()) >> 4) + offset;
        final int minChunkZ = (Mth.floor(source.getPosition().z()) >> 4) + offset;

        source.sendSystemMessage(Component.literal("Asking the chunk system for " + (size * size) + " chunks..."));
        VanillaComparison.bench(level, minChunkX, minChunkZ, size, ChunkStatus.FEATURES,
            line -> source.getServer().sendSystemMessage(Component.literal("[worldgen] " + line)));
        return Command.SINGLE_SUCCESS;
    }

    private static int bench(final CommandContext<CommandSourceStack> context, final int size) {
        final CommandSourceStack source = context.getSource();
        final ServerLevel level = source.getLevel();
        final int minChunkX = (Mth.floor(source.getPosition().x()) >> 4) + 512;
        final int minChunkZ = (Mth.floor(source.getPosition().z()) >> 4) + 512;

        source.sendSystemMessage(Component.literal("Generating " + (size * size) + " chunks, this runs in the background..."));
        final Thread worker = new Thread(() -> {
            final long start = System.nanoTime();
            final ChunkAccess[] batch = BatchWorldGen.generate(level, minChunkX, minChunkZ, size, ChunkStatus.FEATURES);
            final double seconds = (System.nanoTime() - start) / 1.0E9;
            if (batch == null) {
                source.getServer().sendSystemMessage(Component.literal("[worldgen] generation returned nothing"));
                return;
            }
            source.getServer().sendSystemMessage(Component.literal(String.format(
                Locale.ROOT, "[worldgen] %d chunks in %.2fs on one thread, %.1f chunks/s", batch.length, seconds, batch.length / seconds
            )));
        }, "canvas-worldgen-bench");
        worker.setDaemon(true);
        worker.start();
        return Command.SINGLE_SUCCESS;
    }
}
