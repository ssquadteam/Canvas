package io.canvasmc.canvas.worldgen;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.jspecify.annotations.NullMarked;

/**
 * Walks a square of tiles, generating each one against what the previous tiles left on disk.
 * <p>
 * Two tiles may run at once only if nothing they touch overlaps, padding included, so the tiles are coloured by their
 * position modulo the padded width and one colour is run at a time. Within a colour no two tiles share a chunk, so no
 * chunk is generated twice and no two threads write the same region entry.
 */
@NullMarked
public final class WorldGenSweep {

    private WorldGenSweep() {
    }

    public static void run(
        final ServerLevel level,
        final int minChunkX,
        final int minChunkZ,
        final int tile,
        final int tiles,
        final int threads,
        final ChunkStatus target,
        final Consumer<String> report
    ) {
        final Thread worker = new Thread(() -> {
            try {
                sweep(level, minChunkX, minChunkZ, tile, tiles, threads, target, report);
            } catch (final Throwable thr) {
                report.accept("sweep failed: " + thr);
            }
        }, "canvas-worldgen-sweep");
        worker.setDaemon(true);
        worker.start();
    }

    private static void sweep(
        final ServerLevel level,
        final int minChunkX,
        final int minChunkZ,
        final int tile,
        final int tiles,
        final int threads,
        final ChunkStatus target,
        final Consumer<String> report
    ) throws InterruptedException {
        final int pad = WorldGenPipeline.reach(target);
        // tiles this far apart cannot reach each other even through their padding
        final int stride = ((tile + (pad * 2)) + tile - 1) / tile;

        final AtomicInteger generated = new AtomicInteger();
        final AtomicInteger saved = new AtomicInteger();
        final long start = System.nanoTime();

        for (int colourZ = 0; colourZ < stride; ++colourZ) {
            for (int colourX = 0; colourX < stride; ++colourX) {
                final List<int[]> group = new ArrayList<>();
                for (int tileZ = colourZ; tileZ < tiles; tileZ += stride) {
                    for (int tileX = colourX; tileX < tiles; tileX += stride) {
                        group.add(new int[]{tileX, tileZ});
                    }
                }
                if (group.isEmpty()) {
                    continue;
                }

                final AtomicInteger next = new AtomicInteger();
                final Thread[] pool = new Thread[Math.min(threads, group.size())];
                for (int i = 0; i < pool.length; ++i) {
                    pool[i] = new Thread(() -> {
                        for (int index = next.getAndIncrement(); index < group.size(); index = next.getAndIncrement()) {
                            final int[] position = group.get(index);
                            final WorldGenPipeline.Result result = WorldGenPipeline.generate(
                                level, minChunkX + (position[0] * tile), minChunkZ + (position[1] * tile), tile, tile, target
                            );
                            generated.addAndGet(result.chunks());
                            saved.addAndGet(result.saved());
                        }
                    }, "canvas-worldgen-sweep-" + i);
                    pool[i].setDaemon(true);
                    pool[i].start();
                }
                for (final Thread thread : pool) {
                    thread.join();
                }
            }
        }

        final double seconds = (System.nanoTime() - start) / 1.0E9;
        report.accept(String.format(
            java.util.Locale.ROOT,
            "%d tiles of %dx%d on %d threads (reach %d, stride %d): %d chunks in %.2fs, %.1f chunks/s, %d written",
            tiles * tiles, tile, tile, threads, pad, stride, generated.get(), seconds, generated.get() / seconds, saved.get()
        ));
    }
}
