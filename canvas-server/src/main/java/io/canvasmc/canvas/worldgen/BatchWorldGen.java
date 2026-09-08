package io.canvasmc.canvas.worldgen;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import net.minecraft.server.level.FullChunkStatus;
import net.minecraft.server.level.GenerationChunkHolder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.StaticCache2D;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.PalettedContainerFactory;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.UpgradeData;
import net.minecraft.world.level.chunk.status.ChunkPyramid;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.chunk.status.ChunkStep;
import net.minecraft.world.level.chunk.status.WorldGenContext;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/**
 * Generates a square of chunks by driving the vanilla generation steps directly, over private chunks the chunk system
 * never sees.
 * <p>
 * The steps and their tasks are vanilla's, so the blocks are vanilla's. What changes is who calls them: the live
 * pipeline brings up one chunk at a time and pulls each one's dependencies through tickets and holders, while a step
 * reaches out by radius - noise alone wants structure starts eight chunks away. Standalone that is 289 neighbours for
 * one chunk; across a 16x16 batch sharing one neighbour cache it is about four.
 */
@NullMarked
public final class BatchWorldGen {

    private BatchWorldGen() {
    }

    /**
     * Returns the batch in row major order, or {@code null} if the target is not reachable. Blocking, and safe to run
     * off the region threads: every chunk it touches is its own.
     */
    public static ChunkAccess @Nullable [] generate(
        final ServerLevel level,
        final int minChunkX,
        final int minChunkZ,
        final int size,
        final ChunkStatus target
    ) {
        if (size <= 0 || target == ChunkStatus.EMPTY) {
            return null;
        }

        final ChunkStep targetStep = ChunkPyramid.GENERATION_PYRAMID.getStepTo(target);
        // a step that writes into its neighbours only finishes a chunk once those neighbours have run it too, and
        // those neighbours read across their own border while deciding what to write, so the ring is doubled
        final int write = targetStep.blockStateWriteRadius() * 2;
        final int pad = targetStep.getAccumulatedRadiusOf(ChunkStatus.EMPTY) + write;

        final int centerX = minChunkX + (size >> 1);
        final int centerZ = minChunkZ + (size >> 1);
        final int range = (size >> 1) + pad + 1;

        final PalettedContainerFactory factory = level.palettedContainerFactory();
        final StaticCache2D<GenerationChunkHolder> cache = StaticCache2D.create(
            centerX, centerZ, range, (final int x, final int z) -> new Slot(new ChunkPos(x, z), level, factory)
        );

        final WorldGenContext context = level.getChunkSource().chunkMap.worldGenContext;
        for (final ChunkStatus status : statusesTo(target)) {
            final ChunkStep step = ChunkPyramid.GENERATION_PYRAMID.getStepTo(status);
            final int radius = targetStep.getAccumulatedRadiusOf(status) + write;

            for (int z = minChunkZ - radius; z < minChunkZ + size + radius; ++z) {
                for (int x = minChunkX - radius; x < minChunkX + size + radius; ++x) {
                    final Slot slot = (Slot) cache.get(x, z);
                    if (slot.chunk.getPersistedStatus().isOrAfter(status)) {
                        continue;
                    }
                    slot.chunk = step.apply(context, cache, slot.chunk).join();
                }
            }
        }

        final ChunkAccess[] batch = new ChunkAccess[size * size];
        for (int z = 0; z < size; ++z) {
            for (int x = 0; x < size; ++x) {
                batch[(z * size) + x] = ((Slot) cache.get(minChunkX + x, minChunkZ + z)).chunk;
            }
        }
        return batch;
    }

    private static List<ChunkStatus> statusesTo(final ChunkStatus target) {
        final List<ChunkStatus> statuses = new ArrayList<>();
        for (ChunkStatus status = target; status != ChunkStatus.EMPTY; status = status.getParent()) {
            statuses.add(status);
        }
        Collections.reverse(statuses);
        return statuses;
    }

    // the steps only ever ask a neighbour for its chunk, so serving one from a field is the whole contract
    private static final class Slot extends GenerationChunkHolder {

        private ChunkAccess chunk;

        private Slot(final ChunkPos pos, final ServerLevel level, final PalettedContainerFactory factory) {
            super(pos);
            this.chunk = new ProtoChunk(pos, UpgradeData.EMPTY, level, factory, null);
        }

        @Override
        public @Nullable ChunkAccess getChunkIfPresentUnchecked(final ChunkStatus status) {
            return this.chunk.getPersistedStatus().isOrAfter(status) ? this.chunk : null;
        }

        @Override
        public @Nullable ChunkAccess getChunkIfPresent(final ChunkStatus status) {
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
        public FullChunkStatus getFullStatus() {
            return FullChunkStatus.INACCESSIBLE;
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
        protected void addSaveDependency(final CompletableFuture<?> sync) {
        }
    }
}
