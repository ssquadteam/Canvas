package io.canvasmc.canvas.chunk.lod;

import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import java.util.Arrays;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.IdMap;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.Property;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/**
 * Moves a saved paletted container to its packet form without building a {@link net.minecraft.world.level.chunk.PalettedContainer}.
 * <p>
 * Disk and network share the bit storage layout and, for every palette that fits in the section local form, the entry
 * width as well, so the packed longs are copied verbatim and only the palette entries are resolved to global ids. The
 * global palette configuration is the one exception: it stores compact local ids and sends global ids, so it is repacked.
 */
@NullMarked
final class LodSectionCodec {

    static final int BLOCK_ENTRIES = 4096;
    static final int BIOME_ENTRIES = 64;

    static final int FLAG_AIR = 1;
    static final int FLAG_FLUID = 2;
    static final int FLAG_OCCLUDES = 4;
    private static final int FLAG_RESOLVED = 8;

    // palette entries repeat across every column, so resolving one is worth remembering
    private static final int MAX_MEMO_ENTRIES = 8192;

    private static final ThreadLocal<LodSectionCodec> CACHED = ThreadLocal.withInitial(LodSectionCodec::new);

    private static final byte[] STATE_FLAGS = new byte[Block.BLOCK_STATE_REGISTRY.size()];
    private static final int LAVA_STATE_ID = Block.BLOCK_STATE_REGISTRY.getId(Blocks.LAVA.defaultBlockState());
    private static final int AIR_STATE_ID = Block.BLOCK_STATE_REGISTRY.getId(Blocks.AIR.defaultBlockState());
    private static final int BLOCK_GLOBAL_BITS = Mth.ceillog2(Block.BLOCK_STATE_REGISTRY.size());

    private static final ConcurrentHashMap<String, Block> BLOCKS_BY_NAME = new ConcurrentHashMap<>(256);
    private static final ConcurrentHashMap<PropertyMemo, Integer> SHARED_PROPERTY_IDS = new ConcurrentHashMap<>(512);

    private final Object2IntOpenHashMap<PropertyMemo> propertyIds = new Object2IntOpenHashMap<>(512);
    private final Object2IntOpenHashMap<String> biomeIds = new Object2IntOpenHashMap<>(64);
    private final PropertyProbe propertyProbe = new PropertyProbe();
    private @Nullable IdMap<Holder<Biome>> biomeIdMap;

    private LodSectionCodec() {
        this.propertyIds.defaultReturnValue(-1);
        this.biomeIds.defaultReturnValue(-1);
    }

    static LodSectionCodec get() {
        return CACHED.get();
    }

    static int airStateId() {
        return AIR_STATE_ID;
    }

    static boolean isLava(final int stateId) {
        return stateId == LAVA_STATE_ID;
    }

    /**
     * Occlusion mirrors the intersection of {@link BlockState#isSolidRender} with Anti-Xray's solidGlobal formula, so
     * hollowing never drops a block Anti-Xray would have kept visible.
     */
    static int stateFlags(final int stateId) {
        final byte cached = STATE_FLAGS[stateId];
        if (cached != 0) {
            return cached;
        }

        final BlockState state = Block.BLOCK_STATE_REGISTRY.byIdOrThrow(stateId);
        int flags = FLAG_RESOLVED;
        if (state.isAir()) {
            flags |= FLAG_AIR;
        }
        if (!state.getFluidState().isEmpty()) {
            flags |= FLAG_FLUID;
        }
        if (state.isSolidRender()
            && state.isRedstoneConductor(EmptyBlockGetter.INSTANCE, BlockPos.ZERO)
            && !state.is(Blocks.SPAWNER) && !state.is(Blocks.BARRIER) && !state.is(Blocks.SHULKER_BOX)
            && !state.is(Blocks.SLIME_BLOCK) && !state.is(Blocks.MANGROVE_ROOTS)) {
            flags |= FLAG_OCCLUDES;
        }

        STATE_FLAGS[stateId] = (byte) flags;
        return flags;
    }

    boolean readBlockStates(final CompoundTag container, final Container out) {
        final ListTag entries = container.getListOrEmpty("palette");
        final int size = entries.size();
        if (size == 0) {
            return false;
        }

        out.blockStates = true;
        out.globalBits = BLOCK_GLOBAL_BITS;
        out.paletteSize = size;
        out.grow(size);

        for (int i = 0; i < size; ++i) {
            final CompoundTag entry = entries.getCompound(i).orElse(null);
            if (entry == null) {
                return false;
            }

            final int id = this.blockStateId(entry);
            if (id < 0) {
                return false;
            }
            out.palette[i] = id;
        }

        return out.readStorage(container, BLOCK_ENTRIES);
    }

    boolean readBiomes(final CompoundTag container, final Registry<Biome> registry, final IdMap<Holder<Biome>> ids, final Container out) {
        if (this.biomeIdMap != ids) {
            this.biomeIds.clear();
            this.biomeIdMap = ids;
        }

        final ListTag entries = container.getListOrEmpty("palette");
        final int size = entries.size();
        if (size == 0) {
            return false;
        }

        out.blockStates = false;
        out.globalBits = Mth.ceillog2(ids.size());
        out.paletteSize = size;
        out.grow(size);

        for (int i = 0; i < size; ++i) {
            final String name = entries.getString(i).orElse(null);
            if (name == null) {
                return false;
            }

            final int id = this.biomeId(name, registry, ids);
            if (id < 0) {
                return false;
            }
            out.palette[i] = id;
        }

        return out.readStorage(container, BIOME_ENTRIES);
    }

    private int blockStateId(final CompoundTag entry) {
        final String name = entry.getStringOr("Name", "");
        if (name.isEmpty()) {
            return -1;
        }

        final Tag propertiesTag = entry.get("Properties");
        if (!(propertiesTag instanceof final CompoundTag properties) || properties.isEmpty()) {
            return defaultStateId(name);
        }

        final PropertyProbe probe = this.propertyProbe.assign(name, properties);
        final int local = this.propertyIds.getInt(probe);
        if (local >= 0) {
            return local;
        }

        final Integer shared = SHARED_PROPERTY_IDS.get(probe);
        if (shared != null) {
            this.rememberProperty(probe.freeze(), shared);
            return shared;
        }

        final int id = this.stateIdWithProperties(name, properties);
        if (id >= 0) {
            this.rememberProperty(probe.freeze(), id);
        }
        return id;
    }

    private void rememberProperty(final PropertyMemo key, final int id) {
        if (this.propertyIds.size() >= MAX_MEMO_ENTRIES) {
            this.propertyIds.clear();
        }
        this.propertyIds.put(key, id);
        if (SHARED_PROPERTY_IDS.size() < MAX_MEMO_ENTRIES) {
            SHARED_PROPERTY_IDS.putIfAbsent(key, id);
        }
    }

    private static int defaultStateId(final String name) {
        final Block block = blockByName(name);
        return block == null ? -1 : Block.BLOCK_STATE_REGISTRY.getId(block.defaultBlockState());
    }

    private int stateIdWithProperties(final String name, final CompoundTag properties) {
        final Block block = blockByName(name);
        if (block == null) {
            return -1;
        }

        BlockState state = block.defaultBlockState();
        final StateDefinition<Block, BlockState> definition = block.getStateDefinition();
        for (final String key : properties.keySet()) {
            final Property<?> property = definition.getProperty(key);
            if (property == null) {
                continue;
            }
            state = with(state, property, properties.getStringOr(key, ""));
        }
        return Block.BLOCK_STATE_REGISTRY.getId(state);
    }

    private static @Nullable Block blockByName(final String name) {
        final Block cached = BLOCKS_BY_NAME.get(name);
        if (cached != null) {
            return cached;
        }

        final Identifier key = Identifier.tryParse(name);
        if (key == null) {
            return null;
        }
        final Optional<Holder.Reference<Block>> holder = BuiltInRegistries.BLOCK.get(key);
        if (holder.isEmpty()) {
            return null;
        }

        final Block block = holder.get().value();
        BLOCKS_BY_NAME.putIfAbsent(name, block);
        return block;
    }

    private static <T extends Comparable<T>> BlockState with(final BlockState state, final Property<T> property, final String value) {
        final Optional<T> parsed = property.getValue(value);
        return parsed.isEmpty() ? state : state.setValue(property, parsed.get());
    }

    private int biomeId(final String name, final Registry<Biome> registry, final IdMap<Holder<Biome>> ids) {
        final int memo = this.biomeIds.getInt(name);
        if (memo >= 0) {
            return memo;
        }

        final Identifier key = Identifier.tryParse(name);
        if (key == null) {
            return -1;
        }

        final Holder.Reference<Biome> biome = registry.get(key).orElse(null);
        if (biome == null) {
            return -1;
        }

        final int id = ids.getId(biome);
        if (id < 0) {
            return -1;
        }

        if (this.biomeIds.size() >= MAX_MEMO_ENTRIES) {
            this.biomeIds.clear();
        }
        this.biomeIds.put(name, id);
        return id;
    }

    private static int propertyHash(final String name, final CompoundTag properties) {
        int hash = name.hashCode();
        for (final String key : properties.keySet()) {
            hash += 31 * key.hashCode() + properties.getStringOr(key, "").hashCode();
        }
        return hash;
    }

    /**
     * Lookup key that hashes a palette entry without allocating. Fastutil and ConcurrentHashMap both call
     * {@code probe.equals(stored)}, so this only has to recognise {@link PropertyMemo}.
     */
    private static final class PropertyProbe {

        private String name = "";
        private CompoundTag properties = new CompoundTag();
        private int hash;

        private PropertyProbe assign(final String name, final CompoundTag properties) {
            this.name = name;
            this.properties = properties;
            this.hash = propertyHash(name, properties);
            return this;
        }

        private PropertyMemo freeze() {
            final int size = this.properties.size();
            final String[] keys = new String[size];
            final String[] values = new String[size];
            int index = 0;
            for (final String key : this.properties.keySet()) {
                keys[index] = key;
                values[index] = this.properties.getStringOr(key, "");
                ++index;
            }
            return new PropertyMemo(this.name, keys, values, this.hash);
        }

        @Override
        public int hashCode() {
            return this.hash;
        }

        @Override
        public boolean equals(final Object other) {
            return other instanceof final PropertyMemo memo && memo.matches(this.name, this.properties, this.hash);
        }
    }

    private static final class PropertyMemo {

        private final String name;
        private final String[] keys;
        private final String[] values;
        private final int hash;

        private PropertyMemo(final String name, final String[] keys, final String[] values, final int hash) {
            this.name = name;
            this.keys = keys;
            this.values = values;
            this.hash = hash;
        }

        private boolean matches(final String name, final CompoundTag properties, final int hash) {
            if (this.hash != hash || this.keys.length != properties.size() || !this.name.equals(name)) {
                return false;
            }
            for (int i = 0; i < this.keys.length; ++i) {
                if (!this.values[i].equals(properties.getStringOr(this.keys[i], ""))) {
                    return false;
                }
            }
            return true;
        }

        @Override
        public int hashCode() {
            return this.hash;
        }

        @Override
        public boolean equals(final Object other) {
            if (other instanceof final PropertyProbe probe) {
                return this.matches(probe.name, probe.properties, probe.hash);
            }
            if (!(other instanceof final PropertyMemo memo)
                || this.hash != memo.hash
                || this.keys.length != memo.keys.length
                || !this.name.equals(memo.name)) {
                return false;
            }
            for (int i = 0; i < this.keys.length; ++i) {
                if (!this.values[i].equals(memo.valueOf(this.keys[i]))) {
                    return false;
                }
            }
            return true;
        }

        private String valueOf(final String key) {
            for (int i = 0; i < this.keys.length; ++i) {
                if (this.keys[i].equals(key)) {
                    return this.values[i];
                }
            }
            return "";
        }
    }

    /**
     * A section's palette and packed entries, held in the form the packet needs them.
     */
    static final class Container {

        int[] palette = new int[16];
        int paletteSize;
        long @Nullable [] storage;
        int storageBits;
        int memoryBits;
        boolean globalPalette;
        boolean blockStates;
        int globalBits;
        int entryCount;

        void grow(final int size) {
            if (this.palette.length < size + 1) {
                this.palette = Arrays.copyOf(this.palette, Math.max(size + 1, this.palette.length * 2));
            }
        }

        /**
         * Appends to the palette, which can move it past a bit width and so force a repack.
         */
        int add(final int globalId) {
            for (int i = 0; i < this.paletteSize; ++i) {
                if (this.palette[i] == globalId) {
                    return i;
                }
            }

            this.grow(this.paletteSize + 1);
            this.palette[this.paletteSize] = globalId;
            return this.paletteSize++;
        }

        boolean readStorage(final CompoundTag container, final int entryCount) {
            this.entryCount = entryCount;
            this.configure();

            if (this.storageBits == 0) {
                this.storage = null;
                return true;
            }

            final long[] data = container.getLongArray("data").orElse(null);
            if (data == null || data.length != packedLength(entryCount, this.storageBits)) {
                return false;
            }

            this.storage = data;
            return true;
        }

        void unpack(final int[] out) {
            final long[] data = this.storage;
            if (data == null) {
                Arrays.fill(out, 0, this.entryCount, 0);
                return;
            }

            final int bits = this.storageBits;
            final int perLong = 64 / bits;
            final long mask = (1L << bits) - 1L;

            int index = 0;
            for (int word = 0, words = data.length; word < words && index < this.entryCount; ++word) {
                final long packed = data[word];
                for (int offset = 0; offset < perLong && index < this.entryCount; ++offset) {
                    out[index++] = (int) ((packed >>> (offset * bits)) & mask);
                }
            }
        }

        // the packed entries are already in wire form, so only the palette is rewritten
        void writeVerbatim(final FriendlyByteBuf buf) {
            buf.writeByte(this.memoryBits);
            this.writePalette(buf);

            final long[] data = this.storage;
            if (data != null) {
                for (final long packed : data) {
                    buf.writeLong(packed);
                }
            }
        }

        void writeRepacked(final FriendlyByteBuf buf, final int[] values) {
            this.configure();

            buf.writeByte(this.memoryBits);
            this.writePalette(buf);

            if (this.memoryBits == 0) {
                return;
            }

            final int bits = this.memoryBits;
            final int perLong = 64 / bits;
            final int words = packedLength(this.entryCount, bits);

            int index = 0;
            for (int word = 0; word < words; ++word) {
                long packed = 0L;
                for (int offset = 0; offset < perLong && index < this.entryCount; ++offset) {
                    final int local = values[index++];
                    packed |= ((long) (this.globalPalette ? this.palette[local] : local)) << (offset * bits);
                }
                buf.writeLong(packed);
            }
        }

        private void configure() {
            final int bits = Mth.ceillog2(this.paletteSize);
            this.globalPalette = this.blockStates ? bits > 8 : bits > 3;
            this.storageBits = this.blockStates && bits != 0 && bits < 4 ? 4 : bits;
            this.memoryBits = this.globalPalette ? this.globalBits : this.storageBits;
        }

        private void writePalette(final FriendlyByteBuf buf) {
            if (this.globalPalette) {
                return;
            }
            if (this.memoryBits == 0) {
                buf.writeVarInt(this.palette[0]);
                return;
            }

            buf.writeVarInt(this.paletteSize);
            for (int i = 0; i < this.paletteSize; ++i) {
                buf.writeVarInt(this.palette[i]);
            }
        }
    }

    static void writeSingleValue(final FriendlyByteBuf buf, final int globalId) {
        buf.writeByte(0);
        buf.writeVarInt(globalId);
    }

    static int packedLength(final int entryCount, final int bits) {
        final int perLong = 64 / bits;
        return (entryCount + perLong - 1) / perLong;
    }
}
