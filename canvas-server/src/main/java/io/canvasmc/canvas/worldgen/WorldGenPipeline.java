package io.canvasmc.canvas.worldgen;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import net.minecraft.server.level.GenerationChunkHolder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.StaticCache2D;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.UpgradeData;
import net.minecraft.world.level.chunk.status.ChunkDependencies;
import net.minecraft.world.level.chunk.status.ChunkPyramid;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.chunk.status.ChunkStep;
import net.minecraft.world.level.chunk.status.WorldGenContext;
import org.jspecify.annotations.NullMarked;

/**
 * Generates a rectangle of chunks as one pipeline rather than as a set of independent batches.
 * <p>
 * A batch pays for its padding twice: once to generate it and once again when the next batch regenerates the same
 * chunks as its own padding. Here the padding is the previous row's work, still in memory. Each status runs a fixed
 * number of rows ahead of the target - exactly its dependency radius - so by the time a row is asked for features
 * everything features can reach is already at the status features wants it at, and no chunk is generated twice.
 * <p>
 * A row is written and dropped once the row after it has run the target status, since that is the last thing that can
 * write into it.
 */
@NullMarked
public final class WorldGenPipeline {

    private WorldGenPipeline() {
    }

    public record Result(int chunks, int saved, long nanos) {
    }

    /**
     * How many chunks beyond the rectangle each status has to run.
     * <p>
     * The step table's accumulated radius answers this for one chunk, not for a rectangle: it is the widest ring any
     * single chain needs, while a rectangle needs every chain at once. A status one ring out still reads its own
     * dependencies a full radius past that, so the rings compound and have to be walked directly.
     */
    private static int[] leads(final ChunkStatus target) {
        final int[] leads = new int[target.getIndex() + 1];
        Arrays.fill(leads, -1);
        leads[target.getIndex()] = 0;

        for (int index = target.getIndex(); index >= 1; --index) {
            final int lead = leads[index];
            if (lead < 0) {
                continue;
            }

            final ChunkDependencies dependencies = ChunkPyramid.GENERATION_PYRAMID.getStepTo(ChunkStatus.getStatusList().get(index))
                .directDependencies();
            for (int distance = 0; distance < dependencies.size(); ++distance) {
                for (int below = dependencies.get(distance).getIndex(); below >= 1; --below) {
                    leads[below] = Math.max(leads[below], lead + distance);
                }
            }
        }
        return leads;
    }

    /**
     * How far outside its rectangle a run can touch a chunk. Two runs further apart than twice this share nothing.
     */
    public static int reach(final ChunkStatus target) {
        final ChunkStep targetStep = ChunkPyramid.GENERATION_PYRAMID.getStepTo(target);
        final int[] leads = leads(target);
        int reach = 0;
        for (final ChunkStatus status : statusesTo(target)) {
            final ChunkStep step = ChunkPyramid.GENERATION_PYRAMID.getStepTo(status);
            reach = Math.max(reach, leads[status.getIndex()] + Math.max(0, step.directDependencies().size() - 1));
        }
        return reach + (targetStep.blockStateWriteRadius() * 2);
    }

    public static Result generate(
        final ServerLevel level,
        final int minChunkX,
        final int minChunkZ,
        final int width,
        final int height,
        final ChunkStatus target
    ) {
        return generate(level, minChunkX, minChunkZ, width, height, target, null, 1, null);
    }

    /**
     * Spreads each row across a pool. Steps that write only their own chunk have nothing to contend over, so a whole
     * row runs at once; the one step that writes into its neighbours is run in three passes of every third column, far
     * enough apart that no two running chunks can reach the same block.
     */
    public static Result generate(
        final ServerLevel level,
        final int minChunkX,
        final int minChunkZ,
        final int width,
        final int height,
        final ChunkStatus target,
        final int threads,
        final ExecutorService pool
    ) {
        return generate(level, minChunkX, minChunkZ, width, height, target, null, threads, pool);
    }

    /**
     * Runs the pipeline and hands back the rectangle instead of writing it, for comparing against another generator.
     */
    public static ChunkAccess[] collect(
        final ServerLevel level,
        final int minChunkX,
        final int minChunkZ,
        final int width,
        final int height,
        final ChunkStatus target,
        final int threads
    ) {
        final ChunkAccess[] collected = new ChunkAccess[width * height];
        final ExecutorService pool = threads > 1 ? java.util.concurrent.Executors.newFixedThreadPool(threads) : null;
        try {
            generate(level, minChunkX, minChunkZ, width, height, target, collected, threads, pool);
        } finally {
            if (pool != null) {
                pool.shutdownNow();
            }
        }
        return collected;
    }

    private static Result generate(
        final ServerLevel level,
        final int minChunkX,
        final int minChunkZ,
        final int width,
        final int height,
        final ChunkStatus target,
        final ChunkAccess @org.jspecify.annotations.Nullable [] collect,
        final int threads,
        final @org.jspecify.annotations.Nullable ExecutorService pool
    ) {
        final long start = System.nanoTime();
        final ChunkStep targetStep = ChunkPyramid.GENERATION_PYRAMID.getStepTo(target);
        final int write = targetStep.blockStateWriteRadius() * 2;

        final int x0 = minChunkX - write;
        final int x1 = minChunkX + width - 1 + write;
        final int z0 = minChunkZ - write;
        final int z1 = minChunkZ + height - 1 + write;

        final List<ChunkStatus> statuses = statusesTo(target);
        final int[] byStatus = leads(target);
        final int[] leads = new int[statuses.size()];
        int maxLead = 0;
        int trail = 0;
        for (int i = 0; i < statuses.size(); ++i) {
            leads[i] = byStatus[statuses.get(i).getIndex()];
            maxLead = Math.max(maxLead, leads[i]);
            // a status running near the front still reads this far behind it
            final int reads = Math.max(0, ChunkPyramid.GENERATION_PYRAMID.getStepTo(statuses.get(i)).directDependencies().size() - 1);
            trail = Math.max(trail, reads - leads[i]);
        }

        final WorldGenContext context = level.getChunkSource().chunkMap.worldGenContext;
        final Map<Long, Slot> live = new ConcurrentHashMap<>();
        final int side = reach(target);
        final int fromX = x0 - side;
        final int toX = x1 + side;
        // dropping rows as the front passes keeps a big rectangle's memory flat, but it means a chunk can be written
        // and then asked for again, and the write is asynchronous. A rectangle small enough to hold whole never has to
        // take that risk
        final boolean evict = (long) (width + (side * 2)) * (height + (side * 2)) > 16384L;
        int saved = 0;

        for (int front = z0 - (maxLead * 2); front <= z1; ++front) {
            for (int i = 0; i < statuses.size(); ++i) {
                final ChunkStatus status = statuses.get(i);
                final int lead = leads[i];
                final int row = front + lead;
                if (row < z0 - lead || row > z1 + lead) {
                    continue;
                }

                final ChunkStep step = ChunkPyramid.GENERATION_PYRAMID.getStepTo(status);
                final int reach = Math.max(0, step.directDependencies().size() - 1);
                final int rowFrom = x0 - lead;
                final int rowTo = x1 + lead;
                // a step that writes into its neighbours needs a gap wide enough that two running chunks cannot touch
                // the same block, which is one chunk of write on each side plus the chunk between them. Steps that
                // never declare a write radius report -1 rather than 0, so the gap is clamped
                final int stride = Math.max(1, (step.blockStateWriteRadius() * 2) + 1);

                if (pool == null || threads <= 1) {
                    for (int x = rowFrom; x <= rowTo; ++x) {
                        apply(live, level, context, step, status, x, row, reach);
                    }
                } else {
                    for (int offset = 0; offset < stride; ++offset) {
                        runPass(pool, live, level, context, step, status, row, reach, rowFrom + offset, rowTo, stride);
                    }
                }
            }

            // a row is finished once nothing still running can read or write it, which is the status that reaches
            // furthest behind its own row, not the target status
            final int finished = front - 1 - trail;
            if (evict && finished >= z0 - maxLead) {
                saved += flush(live, level, finished, fromX, toX, collect, minChunkX, minChunkZ, width, height);
            }
        }

        for (final Slot slot : live.values()) {
            if (collect != null) {
                final int localX = slot.getPos().x() - minChunkX;
                final int localZ = slot.getPos().z() - minChunkZ;
                if (localX >= 0 && localX < width && localZ >= 0 && localZ < height) {
                    collect[(localZ * width) + localX] = slot.chunk;
                }
            } else if (BatchWorldGen.write(level, slot.chunk)) {
                ++saved;
            }
        }
        live.clear();

        return new Result(width * height, saved, System.nanoTime() - start);
    }

    private static void apply(
        final Map<Long, Slot> live,
        final ServerLevel level,
        final WorldGenContext context,
        final ChunkStep step,
        final ChunkStatus status,
        final int x,
        final int z,
        final int reach
    ) {
        final Slot slot = slot(live, level, x, z);
        if (slot.chunk.getPersistedStatus().isOrAfter(status)) {
            return;
        }
        slot.chunk = step.apply(context, neighbours(live, level, x, z, reach), slot.chunk).join();
    }

    private static void runPass(
        final ExecutorService pool,
        final Map<Long, Slot> live,
        final ServerLevel level,
        final WorldGenContext context,
        final ChunkStep step,
        final ChunkStatus status,
        final int row,
        final int reach,
        final int from,
        final int to,
        final int stride
    ) {
        final List<Future<?>> pending = new ArrayList<>();
        for (int x = from; x <= to; x += stride) {
            final int column = x;
            pending.add(pool.submit(() -> apply(live, level, context, step, status, column, row, reach)));
        }

        for (final Future<?> future : pending) {
            try {
                future.get();
            } catch (final InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted while generating", interrupted);
            } catch (final java.util.concurrent.ExecutionException failure) {
                throw new IllegalStateException("failed to generate " + status.getName() + " row " + row, failure.getCause());
            }
        }
    }

    private static StaticCache2D<GenerationChunkHolder> neighbours(
        final Map<Long, Slot> live, final ServerLevel level, final int x, final int z, final int reach
    ) {
        return StaticCache2D.create(x, z, reach, (final int nx, final int nz) -> slot(live, level, nx, nz));
    }

    private static Slot slot(final Map<Long, Slot> live, final ServerLevel level, final int x, final int z) {
        return live.computeIfAbsent(ChunkPos.pack(x, z), ignored -> {
            final ChunkPos pos = new ChunkPos(x, z);
            final ChunkAccess existing = BatchWorldGen.read(level, pos);
            return new Slot(pos, existing != null ? existing
                : new ProtoChunk(pos, UpgradeData.EMPTY, level, level.palettedContainerFactory(), null));
        });
    }

    private static int flush(
        final Map<Long, Slot> live,
        final ServerLevel level,
        final int row,
        final int fromX,
        final int toX,
        final ChunkAccess @org.jspecify.annotations.Nullable [] collect,
        final int minChunkX,
        final int minChunkZ,
        final int width,
        final int height
    ) {
        int saved = 0;
        for (int x = fromX; x <= toX; ++x) {
            final Slot slot = live.remove(ChunkPos.pack(x, row));
            if (slot == null) {
                continue;
            }

            if (collect != null) {
                final int localX = x - minChunkX;
                final int localZ = row - minChunkZ;
                if (localX >= 0 && localX < width && localZ >= 0 && localZ < height) {
                    collect[(localZ * width) + localX] = slot.chunk;
                }
                continue;
            }
            if (BatchWorldGen.write(level, slot.chunk)) {
                ++saved;
            }
        }
        return saved;
    }

    private static List<ChunkStatus> statusesTo(final ChunkStatus target) {
        final List<ChunkStatus> statuses = new ArrayList<>();
        for (ChunkStatus status = target; status != ChunkStatus.EMPTY; status = status.getParent()) {
            statuses.add(status);
        }
        Collections.reverse(statuses);
        return statuses;
    }

    private static final class Slot extends GenerationChunkHolder {

        private volatile ChunkAccess chunk;

        private Slot(final ChunkPos pos, final ChunkAccess chunk) {
            super(pos);
            this.chunk = chunk;
        }

        @Override
        public ChunkAccess getChunkIfPresentUnchecked(final ChunkStatus status) {
            return this.chunk.getPersistedStatus().isOrAfter(status) ? this.chunk : null;
        }

        @Override
        public ChunkAccess getChunkIfPresent(final ChunkStatus status) {
            return this.getChunkIfPresentUnchecked(status);
        }

        @Override
        public ChunkAccess getLatestChunk() {
            return this.chunk;
        }

        @Override
        public ChunkStatus getPersistedStatus() {
            return this.chunk.getPersistedStatus();
        }

        @Override
        public net.minecraft.server.level.FullChunkStatus getFullStatus() {
            return net.minecraft.server.level.FullChunkStatus.INACCESSIBLE;
        }

        @Override
        public int getTicketLevel() {
            return 0;
        }

        @Override
        public int getQueueLevel() {
            return 0;
        }

        @Override
        protected void addSaveDependency(final java.util.concurrent.CompletableFuture<?> sync) {
        }
    }
}
