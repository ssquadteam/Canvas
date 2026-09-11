package io.canvasmc.canvas.extendedview;

import ca.spottedleaf.concurrentutil.executor.PrioritisedExecutor;
import ca.spottedleaf.concurrentutil.util.Priority;
import ca.spottedleaf.moonrise.common.util.CoordinateUtils;
import ca.spottedleaf.moonrise.common.util.MoonriseCommon;
import ca.spottedleaf.moonrise.patches.chunk_system.io.MoonriseRegionFileIO;
import ca.spottedleaf.moonrise.patches.chunk_system.io.MoonriseRegionFileIO.RegionFileType;
import com.mojang.logging.LogUtils;
import io.canvasmc.canvas.util.Util;
import java.util.concurrent.CompletableFuture;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.game.ClientboundLevelChunkPacketData;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.network.protocol.game.ClientboundLightUpdatePacketData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainerFactory;
import net.minecraft.world.level.chunk.UpgradeData;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.chunk.storage.SerializableChunkData;
import net.minecraft.world.level.chunk.storage.SerializableChunkData.SectionData;
import net.minecraft.world.ticks.LevelChunkTicks;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

public final class ExtendedViewDistance {
    public static final Logger LOGGER = LogUtils.getLogger();

    private static volatile PrioritisedExecutor packetBuildExecutor;

    public final VVChunkCache cache = new VVChunkCache();
    private final ServerLevel world;
    private final LevelChunkSection airSection;

    public ExtendedViewDistance(final ServerLevel world) {
        this.world = world;
        final PalettedContainerFactory factory = PalettedContainerFactory.create(this.world.registryAccess());
        this.airSection = new LevelChunkSection(factory.createForBlockStates(), factory.createForBiomes());
    }

    private static PrioritisedExecutor packetBuildExecutor() {
        PrioritisedExecutor executor = packetBuildExecutor;
        if (executor == null) {
            synchronized (ExtendedViewDistance.class) {
                executor = packetBuildExecutor;
                if (executor == null) {
                    packetBuildExecutor = executor = MoonriseCommon.SERVER_GROUP.createExecutor();
                }
            }
        }
        return executor;
    }

    public CompletableFuture<Result> tryLoad(final int chunkX, final int chunkZ) {
        final long chunkKey = CoordinateUtils.getChunkKey(chunkX, chunkZ);
        return this.cache.computeIfAbsent(chunkKey, () -> this.startBuild(chunkKey, chunkX, chunkZ));
    }

    private CompletableFuture<Result> startBuild(final long chunkKey, final int chunkX, final int chunkZ) {
        final CompletableFuture<Result> future = new CompletableFuture<>();
        try {
            MoonriseRegionFileIO.loadDataAsync(
                this.world,
                chunkX,
                chunkZ,
                RegionFileType.CHUNK_DATA,
                (tag, thrown) -> packetBuildExecutor().queueTask(
                    () -> this.completeBuild(chunkKey, chunkX, chunkZ, future, tag, thrown),
                    Priority.IDLE
                ),
                false,
                Priority.IDLE
            );
        } catch (final Throwable thrown) {
            this.cache.remove(chunkKey, future);
            future.complete(new Result.Failure(thrown));
        }
        return future;
    }

    private void completeBuild(
        final long chunkKey,
        final int chunkX,
        final int chunkZ,
        final CompletableFuture<Result> future,
        final @Nullable CompoundTag tag,
        final @Nullable Throwable ioThrown
    ) {
        if (ioThrown != null) {
            this.cache.remove(chunkKey, future);
            future.complete(new Result.Failure(ioThrown));
            return;
        }
        try {
            if (tag != null) {
                final SerializableChunkData data = SerializableChunkData.parse(this.world, this.world.palettedContainerFactory(), tag);
                if (data != null
                    && data.chunkStatus().isOrAfter(ChunkStatus.FULL)
                    && data.chunkPos().equals(new ChunkPos(chunkX, chunkZ))) {
                    final Result.Success result = this.buildRealChunkPacket(chunkX, chunkZ, data);
                    result.packet().setReady(true);
                    future.complete(result);
                    return;
                }
            }
            this.cache.remove(chunkKey, future);
            future.complete(Result.NotGenerated.INSTANCE);
        } catch (final Throwable thrown) {
            LOGGER.warn(
                "Failed to build VV chunk at ({}, {}) in '{}', treating it as ungenerated",
                chunkX, chunkZ, Util.getLevelName(this.world), thrown
            );
            this.cache.remove(chunkKey, future);
            future.complete(new Result.Failure(thrown));
        }
    }

    private Result.Success buildRealChunkPacket(final int chunkX, final int chunkZ, final SerializableChunkData data) {
        final int sectionCount = this.world.getSectionsCount();
        final int minSectionY = this.world.getMinSectionY();
        final LevelChunkSection[] sections = new LevelChunkSection[sectionCount];
        for (final SectionData sectionData : data.sectionData()) {
            final int index = sectionData.y() - minSectionY;
            if (index >= 0 && index < sectionCount && sectionData.chunkSection() != null) {
                sections[index] = sectionData.chunkSection();
            }
        }
        for (int index = 0; index < sectionCount; index++) {
            if (sections[index] == null) {
                sections[index] = this.airSection;
            }
        }

        if (this.world.canvasConfig().worldChunkSystem.lodHollowChunks) {
            PacketConstructorUtils.carveChunk(sections, this.world, this.airSection);
        }
        if (this.world.canvasConfig().worldChunkSystem.hideOres) {
            PacketConstructorUtils.clearOres(sections);
        }

        final LevelChunk chunk = new LevelChunk(
            this.world, new ChunkPos(chunkX, chunkZ), UpgradeData.EMPTY,
            new LevelChunkTicks<>(), new LevelChunkTicks<>(), 0L, sections, null, null
        );
        final ClientboundLevelChunkPacketData chunkData = new ClientboundLevelChunkPacketData(chunk, null);
        final ClientboundLightUpdatePacketData lightData = PacketConstructorUtils.createLightData(data, this.world);
        return new Result.Success(new ClientboundLevelChunkWithLightPacket(chunkX, chunkZ, chunkData, lightData));
    }
}
