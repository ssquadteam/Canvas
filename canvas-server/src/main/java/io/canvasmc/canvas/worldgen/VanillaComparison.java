package io.canvasmc.canvas.worldgen;

import ca.spottedleaf.concurrentutil.util.Priority;
import ca.spottedleaf.moonrise.patches.chunk_system.level.ChunkSystemServerLevel;
import ca.spottedleaf.moonrise.patches.chunk_system.scheduling.ChunkTaskScheduler;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/**
 * Compares the batch generator against the chunk system's own output.
 * <p>
 * A wider batch is not a reference: it shares this generator's ordering, so both sides can be wrong together. The
 * server's pipeline is the only ground truth for whether the blocks are the ones vanilla would have written.
 */
@NullMarked
public final class VanillaComparison {

    private VanillaComparison() {
    }

    /**
     * Times the chunk system generating the same square, for a number the batch can be compared against.
     */
    public static void bench(
        final ServerLevel level,
        final int minChunkX,
        final int minChunkZ,
        final int size,
        final ChunkStatus target,
        final Consumer<String> report
    ) {
        final ChunkTaskScheduler scheduler = ((ChunkSystemServerLevel) level).moonrise$getChunkTaskScheduler();
        final int total = size * size;
        final AtomicInteger remaining = new AtomicInteger(total);
        final long start = System.nanoTime();

        for (int index = 0; index < total; ++index) {
            final int chunkX = minChunkX + (index % size);
            final int chunkZ = minChunkZ + (index / size);
            scheduler.scheduleChunkLoad(chunkX, chunkZ, target, true, Priority.HIGHER, (final @Nullable ChunkAccess chunk) -> {
                if (remaining.decrementAndGet() == 0) {
                    final double seconds = (System.nanoTime() - start) / 1.0E9;
                    report.accept(String.format(
                        java.util.Locale.ROOT,
                        "chunk system %d chunks in %.2fs, %.1f chunks/s",
                        total, seconds, total / seconds
                    ));
                }
            });
        }
    }

    public static void run(
        final ServerLevel level,
        final int minChunkX,
        final int minChunkZ,
        final int size,
        final ChunkStatus target,
        final Consumer<String> report
    ) {
        final Thread worker = new Thread(() -> {
            final long start = System.nanoTime();
            final ChunkAccess[] batch = BatchWorldGen.generate(level, minChunkX, minChunkZ, size, target);
            final long batchNanos = System.nanoTime() - start;

            if (batch == null) {
                report.accept("batch generation returned nothing");
                return;
            }
            compareAgainstChunkSystem(level, minChunkX, minChunkZ, size, target, batch, batchNanos, report);
        }, "canvas-worldgen-verify");
        worker.setDaemon(true);
        worker.start();
    }

    private static void compareAgainstChunkSystem(
        final ServerLevel level,
        final int minChunkX,
        final int minChunkZ,
        final int size,
        final ChunkStatus target,
        final ChunkAccess[] batch,
        final long batchNanos,
        final Consumer<String> report
    ) {
        final ChunkTaskScheduler scheduler = ((ChunkSystemServerLevel) level).moonrise$getChunkTaskScheduler();
        // features write into their neighbours, so a chunk that has only just run features is still missing what its
        // neighbours will write into it. light is the first status that requires features one chunk out, and nothing
        // between it and features touches blocks
        final ChunkStatus request = target == ChunkStatus.FEATURES ? ChunkStatus.LIGHT : target;
        final int total = size * size;
        final AtomicInteger remaining = new AtomicInteger(total);
        final AtomicInteger blocks = new AtomicInteger();
        final AtomicInteger mismatches = new AtomicInteger();
        final AtomicInteger missing = new AtomicInteger();
        final AtomicInteger biomeMismatches = new AtomicInteger();
        final java.util.concurrent.ConcurrentLinkedQueue<String> samples = new java.util.concurrent.ConcurrentLinkedQueue<>();
        final long start = System.nanoTime();

        for (int index = 0; index < total; ++index) {
            final int localX = index % size;
            final int localZ = index / size;
            final int chunkX = minChunkX + localX;
            final int chunkZ = minChunkZ + localZ;
            final ChunkAccess ours = batch[index];

            scheduler.scheduleChunkLoad(chunkX, chunkZ, request, true, Priority.HIGHER, (final @Nullable ChunkAccess theirs) -> {
                if (theirs == null) {
                    missing.incrementAndGet();
                } else {
                    compareChunk(level, ours, theirs, chunkX, chunkZ, blocks, mismatches, biomeMismatches, samples);
                }

                if (remaining.decrementAndGet() == 0) {
                    final long vanillaNanos = System.nanoTime() - start;
                    report.accept(String.format(
                        java.util.Locale.ROOT,
                        "%s: %d chunks, %d blocks compared, %d block mismatches, %d biome mismatches, %d unavailable. batch %.2fs, chunk system %.2fs%s",
                        target.getName(), total, blocks.get(), mismatches.get(), biomeMismatches.get(), missing.get(),
                        batchNanos / 1.0E9, vanillaNanos / 1.0E9,
                        samples.isEmpty() ? "" : " | " + String.join(" | ", samples)
                    ));
                }
            });
        }
    }

    private static void compareChunk(
        final ServerLevel level,
        final ChunkAccess ours,
        final ChunkAccess theirs,
        final int chunkX,
        final int chunkZ,
        final AtomicInteger blocks,
        final AtomicInteger mismatches,
        final AtomicInteger biomeMismatches,
        final java.util.concurrent.ConcurrentLinkedQueue<String> samples
    ) {
        final int minY = level.getMinY();
        final int maxY = level.getMaxY();
        int counted = 0;
        int wrong = 0;

        for (int y = minY; y <= maxY; ++y) {
            for (int z = 0; z < 16; ++z) {
                for (int x = 0; x < 16; ++x) {
                    final BlockPos pos = new BlockPos(x, y, z);
                    ++counted;
                    if (!ours.getBlockState(pos).equals(theirs.getBlockState(pos))) {
                        ++wrong;
                        if (samples.size() < 6 && wrong < 3) {
                            samples.add(new ChunkPos(chunkX, chunkZ) + " at " + x + "," + y + "," + z
                                + " batch=" + ours.getBlockState(pos) + " vanilla=" + theirs.getBlockState(pos));
                        }
                    }
                }
            }
        }

        int wrongBiomes = 0;
        try {
        for (int y = ours.getMinSectionY(); y <= ours.getMaxSectionY(); ++y) {
            for (int quartY = 0; quartY < 4; ++quartY) {
                final int absoluteQuartY = (y << 2) + quartY;
                for (int quartZ = 0; quartZ < 4; ++quartZ) {
                    for (int quartX = 0; quartX < 4; ++quartX) {
                        if (ours.getNoiseBiome(quartX, absoluteQuartY, quartZ) != theirs.getNoiseBiome(quartX, absoluteQuartY, quartZ)) {
                            ++wrongBiomes;
                        }
                    }
                }
            }
        }

        } catch (final IllegalStateException ignored) {
            // one side is not far enough along to hold biomes
        }

        blocks.addAndGet(counted);
        mismatches.addAndGet(wrong);
        biomeMismatches.addAndGet(wrongBiomes);
    }
}
