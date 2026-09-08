package io.canvasmc.canvas.worldgen;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.jspecify.annotations.NullMarked;

/**
 * Checks that batching does not change what generation produces.
 * <p>
 * Generation is local: past a chunk's dependency radius nothing can reach it. So the same chunk out of a small batch
 * and out of a much wider one has to be block for block identical, and any difference is padding the batch was missing.
 */
@NullMarked
public final class WorldGenVerifier {

    private WorldGenVerifier() {
    }

    public record Result(int chunks, int blocks, int mismatches, long batchNanos, long wideNanos, String firstMismatch) {

        public boolean identical() {
            return this.mismatches == 0;
        }
    }

    public static Result verify(final ServerLevel level, final int minChunkX, final int minChunkZ, final int size, final ChunkStatus target) {
        final long batchStart = System.nanoTime();
        final ChunkAccess[] batched = BatchWorldGen.generate(level, minChunkX, minChunkZ, size, target);
        final long batchNanos = System.nanoTime() - batchStart;
        if (batched == null) {
            return new Result(0, 0, 1, batchNanos, 0L, "batch generation returned nothing");
        }

        // the same chunks out of a batch that reaches four further in every direction. correct padding makes the batch
        // size irrelevant, so any disagreement is padding the smaller one did not have
        final int margin = 4;
        final long wideStart = System.nanoTime();
        final ChunkAccess[] wide = BatchWorldGen.generate(level, minChunkX - margin, minChunkZ - margin, size + (margin * 2), target);
        final long wideNanos = System.nanoTime() - wideStart;
        if (wide == null) {
            return new Result(0, 0, 1, batchNanos, wideNanos, "wide generation returned nothing");
        }

        final int minY = level.getMinY();
        final int maxY = level.getMaxY();
        final int wideSize = size + (margin * 2);

        int blocks = 0;
        int mismatches = 0;
        String firstMismatch = "";

        for (int index = 0; index < batched.length; ++index) {
            final int localX = index % size;
            final int localZ = index / size;
            final ChunkAccess fromBatch = batched[index];
            final ChunkAccess fromWide = wide[((localZ + margin) * wideSize) + localX + margin];

            for (int y = minY; y <= maxY; ++y) {
                for (int z = 0; z < 16; ++z) {
                    for (int x = 0; x < 16; ++x) {
                        final BlockPos pos = new BlockPos(x, y, z);
                        ++blocks;
                        if (!fromBatch.getBlockState(pos).equals(fromWide.getBlockState(pos))) {
                            ++mismatches;
                            if (firstMismatch.isEmpty()) {
                                firstMismatch = new ChunkPos(minChunkX + localX, minChunkZ + localZ) + " at " + x + "," + y + "," + z
                                    + " batch=" + fromBatch.getBlockState(pos) + " wide=" + fromWide.getBlockState(pos);
                            }
                        }
                    }
                }
            }
        }

        return new Result(batched.length, blocks, mismatches, batchNanos, wideNanos, firstMismatch);
    }
}
