package io.canvasmc.canvas.extendedview;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import java.util.BitSet;
import java.util.function.Predicate;
import net.minecraft.network.protocol.game.ClientboundLightUpdatePacketData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.storage.SerializableChunkData;
import net.minecraft.world.level.chunk.storage.SerializableChunkData.SectionData;
import net.minecraft.world.level.lighting.LevelLightEngine;
import org.jspecify.annotations.Nullable;

public final class PacketConstructorUtils {
    private static final Predicate<BlockState> IS_ORE = state -> state.is(BlockTags.COPPER_ORES)
        || state.is(BlockTags.IRON_ORES)
        || state.is(BlockTags.GOLD_ORES)
        || state.is(Blocks.DIAMOND_ORE)
        || state.is(Blocks.DEEPSLATE_DIAMOND_ORE);

    private PacketConstructorUtils() {
    }

    public static void clearOres(final LevelChunkSection[] sections) {
        for (final LevelChunkSection section : sections) {
            if (section.hasOnlyAir()) {
                continue;
            }
            for (int y = 0; y < 16; y++) {
                for (int x = 0; x < 16; x++) {
                    for (int z = 0; z < 16; z++) {
                        final BlockState state = section.getBlockState(x, y, z);
                        if (state.isAir()) {
                            continue;
                        }
                        if (IS_ORE.test(state)) {
                            section.setBlockState(x, y, z, Blocks.STONE.defaultBlockState(), false);
                        } else if (state.is(Blocks.ANCIENT_DEBRIS)) {
                            section.setBlockState(x, y, z, Blocks.NETHERRACK.defaultBlockState(), false);
                        }
                    }
                }
            }
        }
    }

    public static void carveChunk(final LevelChunkSection[] sections, final ServerLevel world, final LevelChunkSection airSection) {
        final int sectionCount = sections.length;
        final int minSectionY = world.getMinSectionY();
        final BlockState air = Blocks.AIR.defaultBlockState();
        final BitSet[] seeThrough = new BitSet[sectionCount];

        for (int index = 0; index < sectionCount; index++) {
            final int sectionMinY = (index + minSectionY) << 4;
            final LevelChunkSection section = sectionMinY + 15 <= 0 ? (sections[index] = airSection) : sections[index];
            if (section.hasOnlyAir()) {
                continue;
            }
            final BitSet mask = new BitSet(4096);
            for (int y = 0; y < 16; y++) {
                for (int x = 0; x < 16; x++) {
                    for (int z = 0; z < 16; z++) {
                        if (section.getBlockState(x, y, z).isSeeThrough()) {
                            mask.set(localPos(x, y, z));
                        }
                    }
                }
            }
            seeThrough[index] = mask;
        }

        for (int index = 0; index < sectionCount; index++) {
            final BitSet mask = seeThrough[index];
            if (mask == null) {
                continue;
            }
            final int sectionMinY = (index + minSectionY) << 4;
            final BitSet below = index > 0 ? seeThrough[index - 1] : null;
            final BitSet above = index < sectionCount - 1 ? seeThrough[index + 1] : null;
            final LevelChunkSection section = sections[index];

            for (int y = Math.max(0, 1 - sectionMinY); y < 16; y++) {
                for (int x = 1; x < 15; x++) {
                    for (int z = 1; z < 15; z++) {
                        final int pos = localPos(x, y, z);
                        final boolean exposed = mask.get(pos)
                            || mask.get(pos - 16)
                            || mask.get(pos + 16)
                            || mask.get(pos - 1)
                            || mask.get(pos + 1)
                            || (y > 0 ? mask.get(pos - 256) : inSection(below, x, 15, z))
                            || (y < 15 ? mask.get(pos + 256) : inSection(above, x, 0, z));
                        if (!exposed) {
                            section.setBlockState(x, y, z, air, false);
                        }
                    }
                }
            }
        }
    }

    public static ClientboundLightUpdatePacketData createLightData(final SerializableChunkData data, final ServerLevel world) {
        final LevelLightEngine lightEngine = world.getLightEngine();
        final int minLightSection = lightEngine.getMinLightSection();
        final int lightSectionCount = lightEngine.getLightSectionCount();
        final boolean hasSky = world.dimensionType().hasSkyLight();
        final SectionData[] byIndex = new SectionData[lightSectionCount];
        if (data.lightCorrect()) {
            for (final SectionData sectionData : data.sectionData()) {
                final int index = sectionData.y() - minLightSection;
                if (index >= 0 && index < lightSectionCount) {
                    byIndex[index] = sectionData;
                }
            }
        }

        final BitSet skyYMask = new BitSet();
        final BitSet blockYMask = new BitSet();
        final BitSet emptySkyYMask = new BitSet();
        final BitSet emptyBlockYMask = new BitSet();
        final ObjectArrayList<byte[]> skyUpdates = new ObjectArrayList<>();
        final ObjectArrayList<byte[]> blockUpdates = new ObjectArrayList<>();

        for (int index = 0; index < lightSectionCount; index++) {
            final SectionData sectionData = byIndex[index];
            recordLayer(sectionData == null ? null : sectionData.blockLight(), index, blockYMask, emptyBlockYMask, blockUpdates);
            if (hasSky) {
                recordLayer(sectionData == null ? null : sectionData.skyLight(), index, skyYMask, emptySkyYMask, skyUpdates);
            }
        }

        return new ClientboundLightUpdatePacketData(skyYMask, blockYMask, emptySkyYMask, emptyBlockYMask, skyUpdates, blockUpdates);
    }

    private static void recordLayer(
        final @Nullable DataLayer layer,
        final int index,
        final BitSet present,
        final BitSet empty,
        final ObjectArrayList<byte[]> updates
    ) {
        if (layer != null) {
            present.set(index);
            updates.add(layer.copy().getData());
        } else {
            empty.set(index);
        }
    }

    private static boolean inSection(final @Nullable BitSet mask, final int x, final int y, final int z) {
        return mask == null || mask.get(localPos(x, y, z));
    }

    private static int localPos(final int x, final int y, final int z) {
        return y << 8 | x << 4 | z;
    }
}
