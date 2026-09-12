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
import net.minecraft.world.level.lighting.LevelLightEngine;
import org.jspecify.annotations.Nullable;

public class PacketConstructorUtils {

    public static final int MASK_SIZE = 4096; // 16^3

    private static final Predicate<BlockState> IS_ORE = (state) ->
        state.is(BlockTags.COPPER_ORES)
        || state.is(BlockTags.IRON_ORES)
        || state.is(BlockTags.GOLD_ORES)
        || state.is(Blocks.COAL_ORE)
        || state.is(Blocks.DEEPSLATE_COAL_ORE)
        || state.is(Blocks.LAPIS_ORE)
        || state.is(Blocks.DEEPSLATE_LAPIS_ORE)
        || state.is(Blocks.REDSTONE_ORE)
        || state.is(Blocks.DEEPSLATE_REDSTONE_ORE)
        || state.is(Blocks.DIAMOND_ORE)
        || state.is(Blocks.DEEPSLATE_DIAMOND_ORE);

    public static void clearOres(final LevelChunkSection[] sections) {
        for (final LevelChunkSection section : sections) {
            if (section.hasOnlyAir()) {
                continue;
            }

            for (int y = 0; y < 16; ++y) {
                for (int x = 0; x < 16; ++x) {
                    for (int z = 0; z < 16; ++z) {
                        final BlockState state = section.getBlockState(x, y, z);
                        if (state.isAir()) {
                            // don't care
                            continue;
                        }

                        if (IS_ORE.test(state)) {
                            // is an ore, replace with stone
                            // we already truncate the bottom half of the world, so we can
                            // do stone without worrying about blending with deepslate
                            section.setBlockState(x, y, z, Blocks.STONE.defaultBlockState(), false);
                            continue;
                        }

                        // netherite needs a special case because that's for the nether only
                        if (state.is(Blocks.ANCIENT_DEBRIS)) {
                            section.setBlockState(x, y, z, Blocks.NETHERRACK.defaultBlockState(), false);
                        }
                    }
                }
            }
        }
    }

    public static void carveChunk(
        final LevelChunkSection[] sections,
        final ServerLevel world,
        final LevelChunkSection airSection
    ) {
        final int sectionCount = sections.length;
        final int minSectionY = world.getMinSectionY();
        final BlockState air = Blocks.AIR.defaultBlockState();

        @Nullable final BitSet[] translucentInSection = new BitSet[sectionCount];

        // collect the translucent blocks in the section first, since if we
        // modify in the same pass then we get weird inaccurate carving

        for (int index = 0; index < sectionCount; ++index) {
            final int sectionBaseY = (index + minSectionY) << 4;
            final LevelChunkSection section = sectionBaseY + 15 <= 0 ? (sections[index] = airSection) : sections[index];
            if (section.hasOnlyAir()) {
                continue;
            }

            final BitSet mask = new BitSet(MASK_SIZE);
            for (int y = 0; y < 16; ++y) {
                for (int x = 0; x < 16; ++x) {
                    for (int z = 0; z < 16; ++z) {
                        if (section.getBlockState(x, y, z).isSeeThrough()) {
                            mask.set(localPos(x, y, z));
                        }
                    }
                }
            }

            translucentInSection[index] = mask;
        }

        // iterate over the translucent blocks in the section
        for (int index = 0; index < sectionCount; ++index) {
            final BitSet translucentBlocks = translucentInSection[index];
            if (translucentBlocks == null) {
                continue;
            }

            final int sectionBaseY = (index + minSectionY) << 4;
            final BitSet below = index > 0 ? translucentInSection[index - 1] : null;
            final BitSet above = index < sectionCount - 1 ? translucentInSection[index + 1] : null;
            final LevelChunkSection section = sections[index];

            for (int y = Math.max(0, 1 - sectionBaseY); y < 16; ++y) {
                for (int x = 1; x < 15; ++x) {
                    for (int z = 1; z < 15; ++z) {
                        final int pos = localPos(x, y, z);
                        final boolean isExposed =
                            translucentBlocks.get(pos)
                            || translucentBlocks.get(pos - 16) || translucentBlocks.get(pos + 16)
                            || translucentBlocks.get(pos - 1) || translucentBlocks.get(pos + 1)
                            || (y > 0 ? translucentBlocks.get(pos - 256) : inSection(below, x, 15, z))
                            || (y < 15 ? translucentBlocks.get(pos + 256) : inSection(above, x, 0, z));

                        if (!isExposed) {
                            section.setBlockState(x, y, z, air, false);
                        }
                    }
                }
            }
        }
    }

    public static ClientboundLightUpdatePacketData createLightData(
        final SerializableChunkData data, final ServerLevel world
    ) {
        final LevelLightEngine lightEngine = world.getLightEngine();
        final int minLightSection = lightEngine.getMinLightSection();
        final int sectionCount = lightEngine.getLightSectionCount();
        final boolean hasSkyLight = world.dimensionType().hasSkyLight();

        final SerializableChunkData.@Nullable SectionData[] byY = new SerializableChunkData.SectionData[sectionCount];

        if (data.lightCorrect()) {
            for (final SerializableChunkData.SectionData section : data.sectionData()) {
                final int idx = section.y() - minLightSection;
                if (idx >= 0 && idx < sectionCount) byY[idx] = section;
            }
        }

        final BitSet skyMask = new BitSet();
        final BitSet blockMask = new BitSet();
        final BitSet emptySkyMask = new BitSet();
        final BitSet emptyBlockMask = new BitSet();

        final ObjectArrayList<byte[]> skyUpdates = new ObjectArrayList<>();
        final ObjectArrayList<byte[]> blockUpdates = new ObjectArrayList<>();

        for (int lIdx = 0; lIdx < sectionCount; ++lIdx) {
            final SerializableChunkData.SectionData stored = byY[lIdx];

            recordLayer(stored == null ? null : stored.blockLight(), lIdx, blockMask, emptyBlockMask, blockUpdates);

            // only record skylight if the dimension HAS skylight
            if (hasSkyLight) {
                recordLayer(stored == null ? null : stored.skyLight(), lIdx, skyMask, emptySkyMask, skyUpdates);
            }
        }

        return new ClientboundLightUpdatePacketData(
            skyMask,
            blockMask,
            emptySkyMask,
            emptyBlockMask,
            skyUpdates,
            blockUpdates
        );
    }

    private static void recordLayer(
        @Nullable
        final DataLayer layer,
        final int idx,
        final BitSet mask,
        final BitSet emptyMask,
        final ObjectArrayList<byte[]> updates
    ) {
        if (layer != null) {
            mask.set(idx);
            updates.add(layer.copy().getData());
        }
        else {
            emptyMask.set(idx);
        }
    }

    private static boolean inSection(
        @Nullable
        final BitSet section,
        final int x,
        final int y,
        final int z
    ) {
        return section == null || section.get(localPos(x, y, z));
    }

    private static int localPos(final int x, final int y, final int z) {
        return (y << 8) | (x << 4) | z;
    }
}
