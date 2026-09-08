package io.canvasmc.canvas.worldgen;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import net.minecraft.server.level.GenerationChunkHolder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.StaticCache2D;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.UpgradeData;
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
     * How far outside its rectangle a run can touch a chunk. Two runs further apart than twice this share nothing.
     */
    public static int reach(final ChunkStatus target) {
        final ChunkStep targetStep = ChunkPyramid.GENERATION_PYRAMID.getStepTo(target);
        int reach = targetStep.blockStateWriteRadius() * 2;
        for (final ChunkStatus status : statusesTo(target)) {
            final ChunkStep step = ChunkPyramid.GENERATION_PYRAMID.getStepTo(status);
            reach = Math.max(reach, targetStep.getAccumulatedRadiusOf(status) + Math.max(0, step.directDependencies().size() - 1));
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
        final long start = System.nanoTime();
        final ChunkStep targetStep = ChunkPyramid.GENERATION_PYRAMID.getStepTo(target);
        final int write = targetStep.blockStateWriteRadius() * 2;

        final int x0 = minChunkX - write;
        final int x1 = minChunkX + width - 1 + write;
        final int z0 = minChunkZ - write;
        final int z1 = minChunkZ + height - 1 + write;

        final List<ChunkStatus> statuses = statusesTo(target);
        final int[] leads = new int[statuses.size()];
        int maxLead = 0;
        for (int i = 0; i < statuses.size(); ++i) {
            leads[i] = targetStep.getAccumulatedRadiusOf(statuses.get(i));
            maxLead = Math.max(maxLead, leads[i]);
        }

        final WorldGenContext context = level.getChunkSource().chunkMap.worldGenContext;
        final Long2ObjectOpenHashMap<Slot> live = new Long2ObjectOpenHashMap<>();
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
                for (int x = x0 - lead; x <= x1 + lead; ++x) {
                    final Slot slot = slot(live, level, x, row);
                    if (slot.chunk.getPersistedStatus().isOrAfter(status)) {
                        continue;
                    }
                    slot.chunk = step.apply(context, neighbours(live, level, x, row, reach), slot.chunk).join();
                }
            }

            // the row behind the target row is the last one anything can still write into
            final int finished = front - 1;
            if (finished >= z0 - maxLead) {
                saved += flush(live, level, finished, x0 - maxLead, x1 + maxLead);
            }
        }

        for (final Slot slot : live.values()) {
            if (BatchWorldGen.write(level, slot.chunk)) {
                ++saved;
            }
        }
        live.clear();

        return new Result(width * height, saved, System.nanoTime() - start);
    }

    private static StaticCache2D<GenerationChunkHolder> neighbours(
        final Long2ObjectOpenHashMap<Slot> live, final ServerLevel level, final int x, final int z, final int reach
    ) {
        return StaticCache2D.create(x, z, reach, (final int nx, final int nz) -> slot(live, level, nx, nz));
    }

    private static Slot slot(final Long2ObjectOpenHashMap<Slot> live, final ServerLevel level, final int x, final int z) {
        final long key = ChunkPos.pack(x, z);
        Slot slot = live.get(key);
        if (slot == null) {
            final ChunkPos pos = new ChunkPos(x, z);
            final ChunkAccess existing = BatchWorldGen.read(level, pos);
            slot = new Slot(pos, existing != null ? existing
                : new ProtoChunk(pos, UpgradeData.EMPTY, level, level.palettedContainerFactory(), null));
            live.put(key, slot);
        }
        return slot;
    }

    private static int flush(final Long2ObjectOpenHashMap<Slot> live, final ServerLevel level, final int row, final int fromX, final int toX) {
        int saved = 0;
        for (int x = fromX; x <= toX; ++x) {
            final Slot slot = live.remove(ChunkPos.pack(x, row));
            if (slot != null && BatchWorldGen.write(level, slot.chunk)) {
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

        private ChunkAccess chunk;

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
