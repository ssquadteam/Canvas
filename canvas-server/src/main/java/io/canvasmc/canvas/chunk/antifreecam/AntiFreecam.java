package io.canvasmc.canvas.chunk.antifreecam;

import ca.spottedleaf.moonrise.patches.chunk_system.player.ChunkSystemServerPlayer;
import ca.spottedleaf.moonrise.patches.chunk_system.player.RegionizedPlayerChunkLoader;
import io.canvasmc.canvas.WorldConfig;
import it.unimi.dsi.fastutil.shorts.Short2ObjectMap;
import it.unimi.dsi.fastutil.shorts.Short2ObjectOpenHashMap;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.network.protocol.game.ClientboundBlockEventPacket;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundBundlePacket;
import net.minecraft.network.protocol.game.ClientboundSectionBlocksUpdatePacket;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainerFactory;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

@NullMarked
public final class AntiFreecam {

    public static final int NO_CUTOFF = Integer.MIN_VALUE;
    public static final int RESEND_PER_TICK = 16;
    static final int SECTION_HEIGHT = 16;

    private static final ThreadLocal<IdentityHashMap<PalettedContainerFactory, LevelChunkSection>> EMPTY_SECTIONS =
        ThreadLocal.withInitial(IdentityHashMap::new);

    private AntiFreecam() {
    }

    public static boolean enabled(final ServerLevel level) {
        return level.canvasConfig().worldChunkSystem.antiFreecam;
    }

    public static int hideBelowY(final ServerLevel level) {
        final WorldConfig.WorldChunkSystem config = level.canvasConfig().worldChunkSystem;
        if (!config.antiFreecam) {
            return NO_CUTOFF;
        }
        return align(config.antiFreecamHideBelowY, level.getMinY());
    }

    public static int hideBelowY(final ServerPlayer player) {
        final WorldConfig.WorldChunkSystem config = player.level().canvasConfig().worldChunkSystem;
        if (!config.antiFreecam || player.getY() < config.antiFreecamRestoreBelowY) {
            return NO_CUTOFF;
        }
        return align(config.antiFreecamHideBelowY, player.level().getMinY());
    }

    public static int align(final int hideBelowY, final int minY) {
        if (hideBelowY <= minY) {
            return NO_CUTOFF;
        }
        return Math.floorDiv(hideBelowY, SECTION_HEIGHT) * SECTION_HEIGHT;
    }

    public static LevelChunkSection emptySection(final PalettedContainerFactory factory) {
        return EMPTY_SECTIONS.get().computeIfAbsent(factory, LevelChunkSection::new);
    }

    public static void writeEmpty(final FriendlyByteBuf buffer, final PalettedContainerFactory factory) {
        emptySection(factory).write(buffer, null, 0);
    }

    public static int emptySerializedSize(final PalettedContainerFactory factory) {
        return emptySection(factory).getSerializedSize();
    }

    public static void noteSent(final ServerPlayer player, final ChunkPos pos, final int hideY) {
        final RegionizedPlayerChunkLoader.PlayerChunkLoaderData loader =
            ((ChunkSystemServerPlayer) player).moonrise$getChunkLoader();
        if (loader != null) {
            loader.canvas$noteAntiFreecam(pos.pack(), hideY != NO_CUTOFF);
        }
    }

    public static @Nullable Packet<?> filter(final ServerPlayer player, final Packet<?> packet) {
        if (!isMaskedPacket(packet)) {
            return packet;
        }
        final int hideY = hideBelowY(player);
        if (hideY == NO_CUTOFF) {
            return packet;
        }
        return filterKnown(packet, hideY);
    }

    private static boolean isMaskedPacket(final Packet<?> packet) {
        return packet instanceof ClientboundBlockUpdatePacket
            || packet instanceof ClientboundSectionBlocksUpdatePacket
            || packet instanceof ClientboundBlockEventPacket
            || packet instanceof ClientboundBlockEntityDataPacket
            || packet instanceof ClientboundBundlePacket;
    }

    private static @Nullable Packet<?> filterKnown(final Packet<?> packet, final int hideY) {
        if (packet instanceof final ClientboundBlockUpdatePacket update) {
            return update.getPos().getY() < hideY ? null : update;
        }
        if (packet instanceof final ClientboundBlockEventPacket event) {
            return event.getPos().getY() < hideY ? null : event;
        }
        if (packet instanceof final ClientboundBlockEntityDataPacket entity) {
            return entity.getPos().getY() < hideY ? null : entity;
        }
        if (packet instanceof final ClientboundSectionBlocksUpdatePacket section) {
            return filterSection(section, hideY);
        }
        if (packet instanceof final ClientboundBundlePacket bundle) {
            return filterBundle(bundle, hideY);
        }
        return packet;
    }

    private static @Nullable Packet<?> filterSection(final ClientboundSectionBlocksUpdatePacket packet, final int hideY) {
        final Short2ObjectMap<BlockState> kept = new Short2ObjectOpenHashMap<>();
        final SectionPos[] sectionPos = new SectionPos[1];
        packet.runUpdates((final BlockPos pos, final BlockState state) -> {
            if (sectionPos[0] == null) {
                sectionPos[0] = SectionPos.of(pos);
            }
            if (pos.getY() >= hideY) {
                kept.put(SectionPos.sectionRelativePos(pos), state);
            }
        });
        if (sectionPos[0] == null || sectionPos[0].minBlockY() >= hideY) {
            return packet;
        }
        if (kept.isEmpty()) {
            return null;
        }
        return new ClientboundSectionBlocksUpdatePacket(sectionPos[0], kept);
    }

    private static @Nullable Packet<?> filterBundle(final ClientboundBundlePacket bundle, final int hideY) {
        final List<Packet<? super ClientGamePacketListener>> kept = new ArrayList<>();
        boolean changed = false;
        for (final Packet<? super ClientGamePacketListener> sub : bundle.subPackets()) {
            final Packet<?> next = filterKnown((Packet<?>) sub, hideY);
            if (next == null) {
                changed = true;
                continue;
            }
            if (next != sub) {
                changed = true;
            }
            @SuppressWarnings("unchecked")
            final Packet<? super ClientGamePacketListener> typed = (Packet<? super ClientGamePacketListener>) next;
            kept.add(typed);
        }
        if (!changed) {
            return bundle;
        }
        return kept.isEmpty() ? null : new ClientboundBundlePacket(kept);
    }
}
