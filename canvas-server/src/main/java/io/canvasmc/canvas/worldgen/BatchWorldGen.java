package io.canvasmc.canvas.worldgen;

import ca.spottedleaf.concurrentutil.util.Priority;
import ca.spottedleaf.moonrise.patches.chunk_system.io.MoonriseRegionFileIO;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import net.minecraft.nbt.CompoundTag;
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
import net.minecraft.world.level.chunk.storage.SerializableChunkData;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import com.mojang.logging.LogUtils;

/**
 * Generates a square of chunks by driving the vanilla generation steps directly, over chunks the chunk system never
 * sees.
 * <p>
 * The steps and their tasks are vanilla's, so the blocks are vanilla's. What changes is who calls them: the live
 * pipeline brings up one chunk at a time and pulls each one's dependencies through tickets and holders, while a step
 * reaches out by radius - noise alone wants structure starts eight chunks away. Standalone that is 289 neighbours for
 * one chunk; across a 16x16 batch sharing one neighbour cache it is about four.
 */
@NullMarked
public final class BatchWorldGen {

    private static final Logger LOGGER = LogUtils.getLogger();

    private BatchWorldGen() {
    }

    public record Result(ChunkAccess[] chunks, int loaded, int saved) {
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
        final Result result = run(level, minChunkX, minChunkZ, size, target, false, false);
        return result == null ? null : result.chunks();
    }

    /**
     * Walking the square backwards changes only which chunk writes across a shared border first, which is the one thing
     * the live pipeline does not fix either. Two runs that disagree only here disagree the way two vanilla servers do.
     */
    public static ChunkAccess @Nullable [] generate(
        final ServerLevel level,
        final int minChunkX,
        final int minChunkZ,
        final int size,
        final ChunkStatus target,
        final boolean reversed
    ) {
        final Result result = run(level, minChunkX, minChunkZ, size, target, reversed, false);
        return result == null ? null : result.chunks();
    }

    /**
     * Generates with the region files as the shared state, the way the live pipeline uses them.
     * <p>
     * Without this every batch regenerates its own padding, so the chunk a batch wrote at its edge and the same chunk
     * regenerated as the next batch's padding disagree about what their shared border holds, and the seam is visible.
     * Reading what is already on disk also means the padding is generated once for the whole sweep rather than once per
     * batch, which is most of what batching was worth in the first place.
     */
    public static @Nullable Result persist(
        final ServerLevel level,
        final int minChunkX,
        final int minChunkZ,
        final int size,
        final ChunkStatus target
    ) {
        return run(level, minChunkX, minChunkZ, size, target, false, true);
    }

    private static @Nullable Result run(
        final ServerLevel level,
        final int minChunkX,
        final int minChunkZ,
        final int size,
        final ChunkStatus target,
        final boolean reversed,
        final boolean persist
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
        final int[] loaded = new int[1];
        final StaticCache2D<GenerationChunkHolder> cache = StaticCache2D.create(
            centerX, centerZ, range, (final int x, final int z) -> {
                final ChunkPos pos = new ChunkPos(x, z);
                final ProtoChunk existing = persist ? read(level, pos) : null;
                if (existing != null) {
                    ++loaded[0];
                    return new Slot(pos, existing);
                }
                return new Slot(pos, new ProtoChunk(pos, UpgradeData.EMPTY, level, factory, null));
            }
        );

        final WorldGenContext context = level.getChunkSource().chunkMap.worldGenContext;
        for (final ChunkStatus status : statusesTo(target)) {
            final ChunkStep step = ChunkPyramid.GENERATION_PYRAMID.getStepTo(status);
            final int radius = targetStep.getAccumulatedRadiusOf(status) + write;

            final int minZ = minChunkZ - radius;
            final int maxZ = minChunkZ + size + radius - 1;
            final int minX = minChunkX - radius;
            final int maxX = minChunkX + size + radius - 1;

            for (int i = minZ; i <= maxZ; ++i) {
                final int z = reversed ? maxZ - (i - minZ) : i;
                for (int j = minX; j <= maxX; ++j) {
                    final int x = reversed ? maxX - (j - minX) : j;
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

        int saved = 0;
        if (persist) {
            final int minZ = centerZ - range;
            final int maxZ = centerZ + range;
            final int minX = centerX - range;
            final int maxX = centerX + range;
            for (int z = minZ; z <= maxZ; ++z) {
                for (int x = minX; x <= maxX; ++x) {
                    if (write(level, ((Slot) cache.get(x, z)).chunk)) {
                        ++saved;
                    }
                }
            }
        }

        return new Result(batch, loaded[0], saved);
    }

    static @Nullable ProtoChunk read(final ServerLevel level, final ChunkPos pos) {
        try {
            final CompoundTag data = MoonriseRegionFileIO.loadData(
                level, pos.x(), pos.z(), MoonriseRegionFileIO.RegionFileType.CHUNK_DATA, Priority.HIGHER
            );
            if (data == null) {
                return null;
            }

            final SerializableChunkData chunkData = SerializableChunkData.parse(
                level, level.palettedContainerFactory(), level.getChunkSource().chunkMap.upgradeChunkTag(data)
            );
            if (chunkData == null) {
                return null;
            }

            final ProtoChunk chunk = chunkData.read(level, level.getPoiManager(), level.getChunkSource().chunkMap.storageInfo(), pos);
            // it came off disk unchanged, so nothing needs writing back unless something touches it
            chunk.tryMarkSaved();
            return chunk;
        } catch (final Throwable thr) {
            LOGGER.error("Failed to read chunk {} for batch generation", pos, thr);
            return null;
        }
    }

    static boolean write(final ServerLevel level, final ChunkAccess chunk) {
        if (chunk.getPersistedStatus() == ChunkStatus.EMPTY || !chunk.isUnsaved()) {
            return false;
        }

        try {
            final SerializableChunkData chunkData = SerializableChunkData.copyOf(level, chunk);
            chunk.tryMarkSaved();
            MoonriseRegionFileIO.scheduleSave(
                level, chunk.getPos().x(), chunk.getPos().z(), chunkData.write(),
                MoonriseRegionFileIO.RegionFileType.CHUNK_DATA, Priority.NORMAL
            );
            return true;
        } catch (final Throwable thr) {
            LOGGER.error("Failed to write chunk {} from batch generation", chunk.getPos(), thr);
            return false;
        }
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

        private Slot(final ChunkPos pos, final ChunkAccess chunk) {
            super(pos);
            this.chunk = chunk;
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
