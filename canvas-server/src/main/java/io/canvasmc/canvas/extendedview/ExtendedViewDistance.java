package io.canvasmc.canvas.extendedview;

import ca.spottedleaf.concurrentutil.executor.PrioritisedExecutor;
import ca.spottedleaf.concurrentutil.util.Priority;
import ca.spottedleaf.moonrise.common.util.CoordinateUtils;
import ca.spottedleaf.moonrise.common.util.MoonriseCommon;
import ca.spottedleaf.moonrise.patches.chunk_system.io.MoonriseRegionFileIO;
import com.mojang.logging.LogUtils;
import io.canvasmc.canvas.GlobalConfiguration;
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
import net.minecraft.world.ticks.LevelChunkTicks;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

public final class ExtendedViewDistance {

    public static final Logger LOGGER = LogUtils.getLogger();

    // Canvas assigns MoonriseCommon.SERVER_GROUP after class load, so this cannot be a static initializer
    private static volatile PrioritisedExecutor packetBuildExecutor;

    public final VVChunkCache cache = new VVChunkCache();

    private final ServerLevel world;
    private final PalettedContainerFactory sectionFactory;

    public ExtendedViewDistance(final ServerLevel world) {
        this.world = world;
        this.sectionFactory = PalettedContainerFactory.create(this.world.registryAccess());
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
        return this.cache.computeIfAbsent(
            CoordinateUtils.getChunkKey(chunkX, chunkZ),
            (_) -> this.startBuild(chunkX, chunkZ)
        );
    }

    private CompletableFuture<Result> startBuild(final int chunkX, final int chunkZ) {
        final long chunkKey = CoordinateUtils.getChunkKey(chunkX, chunkZ);
        final CompletableFuture<Result> future = new CompletableFuture<>();

        try {
            // load the chunk data ONLY
            MoonriseRegionFileIO.loadDataAsync(
                this.world,
                chunkX,
                chunkZ,
                MoonriseRegionFileIO.RegionFileType.CHUNK_DATA,
                (tag, throwable) -> packetBuildExecutor().queueTask(
                    () -> this.completeBuild(chunkKey, chunkX, chunkZ, future, tag, throwable), Priority.IDLE
                ),
                false,
                Priority.IDLE // use idle, i dont give a fuck about this
            );
        } catch (final Throwable thrown) {
            this.cache.remove(chunkKey, future);

            // ruh roh :(
            future.complete(new Result.Failure(thrown));
        }

        return future;
    }

    private void completeBuild(
        final long chunkKey,
        final int chunkX,
        final int chunkZ,
        final CompletableFuture<Result> future,
        @Nullable
        final CompoundTag tag,
        @Nullable
        final Throwable ioThrowable
    ) {
        if (ioThrowable != null) {
            // failed, remove from cache and return
            this.cache.remove(chunkKey, future);
            future.complete(new Result.Failure(ioThrowable));
            return;
        }

        try {
            if (tag != null) {
                final CompoundTag upgraded = this.world.getChunkSource().chunkMap.upgradeChunkTag(tag);

                // parse the tag and then try and create the packet
                final SerializableChunkData data = SerializableChunkData.parse(
                    this.world,
                    this.world.palettedContainerFactory(),
                    upgraded
                );

                //noinspection ConstantValue - the data is nullable, stfu IntelliJ
                if (
                    data != null &&
                    data.chunkStatus().isOrAfter(ChunkStatus.FULL) &&
                    data.chunkPos().equals(new ChunkPos(chunkX, chunkZ))
                ) {
                    final Result.Success result = this.buildRealChunkPacket(chunkX, chunkZ, data);

                    // set "ready" bc of antixray
                    result.packet().setReady(true);
                    future.complete(result);
                    return;
                }
            }

            // not generated or something
            this.cache.remove(chunkKey, future);
            future.complete(Result.NotGenerated.INSTANCE);
        } catch (final Throwable thrown) {
            LOGGER.warn(
                "Failed to build VV chunk at ({}, {}) in '{}', treating it as ungenerated",
                chunkX,
                chunkZ,
                Util.getLevelName(this.world),
                thrown
            );

            // remove from cache and report failure
            this.cache.remove(chunkKey, future);
            future.complete(new Result.Failure(thrown));
        }
    }

    private Result.Success buildRealChunkPacket(
        final int chunkX,
        final int chunkZ,
        final SerializableChunkData data
    ) {
        final int sectionCount = this.world.getSectionsCount();
        final int minSectionY = this.world.getMinSectionY();

        @Nullable
        final LevelChunkSection[] sections = new LevelChunkSection[sectionCount];

        // truncate and read sections
        for (final SerializableChunkData.SectionData sectionData : data.sectionData()) {
            final int index = sectionData.y() - minSectionY;
            if (index >= 0 && index < sectionCount && sectionData.chunkSection() != null) {
                sections[index] = sectionData.chunkSection();
            }
        }

        // replace "null" sections with air
        for (int index = 0; index < sectionCount; ++index) {
            // this makes "sections" nonnull - required
            if (sections[index] == null) {
                sections[index] = PacketConstructorUtils.createAirSection(this.sectionFactory);
            }
        }

        // try hollow chunks before we create the actual chunk object
        if (GlobalConfiguration.getInstance().chunkSystem.visualViewDistance.hollowChunks) {
            //noinspection NullableProblems - we made "sections" nonnull above
            PacketConstructorUtils.carveChunk(sections, this.world, this.sectionFactory);
        }

        // remove the ores if asked of us
        if (GlobalConfiguration.getInstance().chunkSystem.visualViewDistance.hideOres) {
            //noinspection NullableProblems - we made "sections" nonnull above
            PacketConstructorUtils.clearOres(sections);
        }

        // carve/ores mutate in-place. write the rebuilt vanilla palettes, not those
        for (int index = 0; index < sectionCount; ++index) {
            sections[index] = PacketConstructorUtils.repackForNetwork(sections[index], this.sectionFactory);
        }

        //noinspection NullableProblems - we made "sections" nonnull above
        final LevelChunk chunk = new LevelChunk(
            this.world, new ChunkPos(chunkX, chunkZ), UpgradeData.EMPTY,
            new LevelChunkTicks<>(), new LevelChunkTicks<>(), 0L, sections, null, null
        );

        // construct and return the finished packet
        //noinspection DataFlowIssue - chunkPacketInfo can be null, Paper didnt add that annotation
        final ClientboundLevelChunkPacketData chunkData = new ClientboundLevelChunkPacketData(chunk, null);
        final ClientboundLightUpdatePacketData lightData = PacketConstructorUtils.createLightData(
            data,
            this.world
        );

        return new Result.Success(new ClientboundLevelChunkWithLightPacket(chunkX, chunkZ, chunkData, lightData));
    }
}
