package io.canvasmc.canvas.subcommands;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import io.canvasmc.canvas.commands.SubCommand;
import io.canvasmc.canvas.worldgen.BatchWorldGen;
import io.canvasmc.canvas.worldgen.WorldGenVerifier;
import java.util.Locale;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
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

    private static int bench(final CommandContext<CommandSourceStack> context, final int size) {
        final CommandSourceStack source = context.getSource();
        final ServerLevel level = source.getLevel();
        final int minChunkX = (Mth.floor(source.getPosition().x()) >> 4) + 512;
        final int minChunkZ = (Mth.floor(source.getPosition().z()) >> 4) + 512;

        final long start = System.nanoTime();
        final ChunkAccess[] batch = BatchWorldGen.generate(level, minChunkX, minChunkZ, size, ChunkStatus.FEATURES);
        final long elapsed = System.nanoTime() - start;

        if (batch == null) {
            source.sendSystemMessage(Component.literal("generation returned nothing").withColor(CommonColors.RED));
            return 0;
        }

        final double seconds = elapsed / 1.0E9;
        source.sendSystemMessage(Component.literal(String.format(
            Locale.ROOT, "%d chunks in %.2fs on one thread, %.1f chunks/s", batch.length, seconds, batch.length / seconds
        )).withColor(CommonColors.GREEN));
        return Command.SINGLE_SUCCESS;
    }
}
