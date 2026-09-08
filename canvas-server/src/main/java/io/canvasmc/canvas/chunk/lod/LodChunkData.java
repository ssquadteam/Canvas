package io.canvasmc.canvas.chunk.lod;

import com.mojang.serialization.Codec;
import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;
import net.minecraft.core.Holder;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.PalettedContainerFactory;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/**
 * The subset of a saved chunk an LOD packet is built from.
 * <p>
 * {@link net.minecraft.world.level.chunk.storage.SerializableChunkData#parse} also decodes ticks, entities, block
 * entities, post processing offsets, structures, upgrade, blending and carving data. None of it reaches the client
 * in an LOD packet, and parsing it was the largest single cost in the encoder, so this reads only what is sent.
 */
@NullMarked
record LodChunkData(
    @Nullable ChunkStatus status,
    boolean lightCorrect,
    Map<Heightmap.Types, long[]> heightmaps,
    @Nullable LevelChunkSection[] sections,
    @Nullable DataLayer[] skyLight,
    @Nullable DataLayer[] blockLight
) {

    static @Nullable LodChunkData parse(final ServerLevel level, final CompoundTag tag) {
        if (tag.getString("Status").isEmpty()) {
            return null;
        }

        final ChunkStatus status = tag.read("Status", ChunkStatus.CODEC).orElse(ChunkStatus.EMPTY);
        final boolean lightCorrect = status.isOrAfter(ChunkStatus.LIGHT)
            && tag.get("isLightOn") != null
            && tag.getIntOr(ca.spottedleaf.moonrise.patches.starlight.util.SaveUtil.STARLIGHT_VERSION_TAG, -1)
            == ca.spottedleaf.moonrise.patches.starlight.util.SaveUtil.STARLIGHT_LIGHT_VERSION;

        // only the heightmaps the packet carries, the rest are never written to the wire
        final EnumMap<Heightmap.Types, long[]> heightmaps = new EnumMap<>(Heightmap.Types.class);
        tag.getCompound("Heightmaps").ifPresent(compound -> {
            for (final Heightmap.Types type : status.heightmapsAfter()) {
                if (type.sendToClient()) {
                    compound.getLongArray(type.getSerializationKey()).ifPresent(data -> heightmaps.put(type, data));
                }
            }
        });

        final int sectionCount = level.getSectionsCount();
        final LevelChunkSection[] sections = new LevelChunkSection[sectionCount];
        final DataLayer[] skyLight = new DataLayer[sectionCount];
        final DataLayer[] blockLight = new DataLayer[sectionCount];

        final PalettedContainerFactory factory = level.palettedContainerFactory();
        // the Anti-Xray preset states SerializableChunkData seeds here are pointless for LOD, hollowing is what
        // hides ore, so the plain codec is used and the palette stays smaller
        final Codec<PalettedContainer<BlockState>> blockStatesCodec = factory.blockStatesContainerCodec();
        final Codec<PalettedContainer<Holder<Biome>>> biomesCodec = factory.biomeContainerRWCodec();

        final ListTag sectionTags = tag.getListOrEmpty("sections");
        for (int i = 0, len = sectionTags.size(); i < len; ++i) {
            final Optional<CompoundTag> maybeSection = sectionTags.getCompound(i);
            if (maybeSection.isEmpty()) {
                continue;
            }

            final CompoundTag sectionTag = maybeSection.get();
            final int sectionY = sectionTag.getByteOr("Y", (byte) 0);
            final int index = level.getSectionIndexFromSectionY(sectionY);
            if (index < 0 || index >= sectionCount) {
                continue;
            }

            final PalettedContainer<BlockState> blocks = sectionTag.getCompound("block_states")
                .flatMap(container -> blockStatesCodec.parse(NbtOps.INSTANCE, container).result())
                .orElse(null);
            if (blocks == null) {
                continue;
            }

            final PalettedContainer<Holder<Biome>> biomes = sectionTag.getCompound("biomes")
                .flatMap(container -> biomesCodec.parse(NbtOps.INSTANCE, container).result())
                .orElseGet(factory::createForBiomes);

            sections[index] = new LevelChunkSection(blocks, biomes);
            sectionTag.getByteArray("SkyLight").ifPresent(data -> skyLight[index] = new DataLayer(data));
            sectionTag.getByteArray("BlockLight").ifPresent(data -> blockLight[index] = new DataLayer(data));
        }

        return new LodChunkData(status, lightCorrect, heightmaps, sections, skyLight, blockLight);
    }
}
