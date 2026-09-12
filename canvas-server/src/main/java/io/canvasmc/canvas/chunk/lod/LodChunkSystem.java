package io.canvasmc.canvas.chunk.lod;

import ca.spottedleaf.moonrise.common.util.CoordinateUtils;
import ca.spottedleaf.moonrise.common.util.MoonriseConstants;
import ca.spottedleaf.moonrise.patches.chunk_system.player.ChunkSystemServerPlayer;
import ca.spottedleaf.moonrise.patches.chunk_system.player.RegionizedPlayerChunkLoader;
import io.canvasmc.canvas.WorldConfig;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import it.unimi.dsi.fastutil.objects.Object2IntMaps;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.UUID;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.craftbukkit.CraftWorld;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Server side implementation of {@link LodChunkService}. The chunk loader asks this class, once per player update,
 * what that player's LOD view distance and Y cutoff are.
 */
@NullMarked
public final class LodChunkSystem implements LodChunkService {

    private static final Logger LOGGER = LoggerFactory.getLogger("CanvasLOD");

    private static final int UNSET = -1;

    private static final LodChunkSystem INSTANCE = new LodChunkSystem();

    public static LodChunkSystem getInstance() {
        return INSTANCE;
    }

    private volatile int serverViewDistance = UNSET;
    private volatile int serverCutoffY = UNSET;

    private final Object2IntMap<ResourceKey<Level>> worldViewDistances = synchronizedIntMap();
    private final Object2IntMap<ResourceKey<Level>> worldCutoffs = synchronizedIntMap();
    private final Object2IntMap<UUID> playerViewDistances = synchronizedIntMap();
    private final Object2IntMap<UUID> playerCutoffs = synchronizedIntMap();

    private volatile List<RegisteredResolver> resolvers = List.of();

    // lets resolve() bail before touching the override maps
    private volatile boolean hasOverrides;


    private LodChunkSystem() {
    }

    private static <K> Object2IntMap<K> synchronizedIntMap() {
        final Object2IntOpenHashMap<K> map = new Object2IntOpenHashMap<>();
        map.defaultReturnValue(UNSET);
        return Object2IntMaps.synchronize(map);
    }

    // hot path, called by the chunk loader on the player's own region thread
    public LodSettings resolve(final ServerPlayer player) {
        final ServerLevel level = player.level();
        if (!this.hasOverrides && !hasConfiguredView(level) && officialPlayerView(player) <= 0) {
            return LodSettings.DISABLED;
        }

        LodSettings settings = this.baseSettings(level);

        final UUID id = player.getUUID();
        final int playerView = this.playerViewDistances.getInt(id);
        if (playerView != UNSET) {
            settings = settings.withViewDistance(playerView);
        } else {
            final int officialPlayer = officialPlayerView(player);
            if (officialPlayer > 0) {
                settings = settings.withViewDistance(officialPlayer);
            }
        }
        final int playerCutoff = this.playerCutoffs.getInt(id);
        if (playerCutoff != UNSET) {
            settings = settings.withCutoffY(playerCutoff);
        }

        return this.applyResolvers(player.getBukkitEntity(), level, settings);
    }

    public LodSettings resolve(final ServerLevel level) {
        if (!this.hasOverrides && !hasConfiguredView(level)) {
            return LodSettings.DISABLED;
        }
        return this.applyResolvers(null, level, this.baseSettings(level));
    }

    private static boolean hasConfiguredView(final ServerLevel level) {
        return level.canvasConfig().worldChunkSystem.lodViewDistance > 0
            || level.serverLevelData.canvas$distanceConfig.visualViewDistanceOrDefault() > 0;
    }

    private static int officialPlayerView(final ServerPlayer player) {
        return ((ChunkSystemServerPlayer) player).moonrise$getViewDistanceHolder().getViewDistances().vvDistance();
    }

    private void updateOverrideFlag() {
        this.hasOverrides = this.serverViewDistance != UNSET
            || this.serverCutoffY != UNSET
            || !this.worldViewDistances.isEmpty()
            || !this.worldCutoffs.isEmpty()
            || !this.playerViewDistances.isEmpty()
            || !this.playerCutoffs.isEmpty()
            || !this.resolvers.isEmpty();
    }

    private LodSettings baseSettings(final ServerLevel level) {
        final WorldConfig.WorldChunkSystem config = level.canvasConfig().worldChunkSystem;
        final int official = level.serverLevelData.canvas$distanceConfig.visualViewDistanceOrDefault();
        int viewDistance = official > 0 ? official : config.lodViewDistance;
        int cutoffY = config.lodCutoffY;

        final int serverView = this.serverViewDistance;
        if (serverView != UNSET) {
            viewDistance = serverView;
        }
        final int serverCutoff = this.serverCutoffY;
        if (serverCutoff != UNSET) {
            cutoffY = serverCutoff;
        }

        final ResourceKey<Level> dimension = level.dimension();
        final int worldView = this.worldViewDistances.getInt(dimension);
        if (worldView != UNSET) {
            viewDistance = worldView;
        }
        final int worldCutoff = this.worldCutoffs.getInt(dimension);
        if (worldCutoff != UNSET) {
            cutoffY = worldCutoff;
        }

        return new LodSettings(Math.max(0, viewDistance), cutoffY);
    }

    private LodSettings applyResolvers(final @Nullable Player player, final ServerLevel level, final LodSettings base) {
        if (this.resolvers.isEmpty()) {
            return base;
        }

        final World world = level.getWorld();
        LodSettings settings = base;
        for (final RegisteredResolver registered : this.resolvers) {
            final LodSettings resolved;
            try {
                resolved = registered.resolver.resolve(player, world, settings);
            } catch (final Throwable thrown) {
                LOGGER.error("LOD resolver registered by {} threw, ignoring it for this resolution", registered.plugin.getName(), thrown);
                continue;
            }
            if (resolved != null) {
                settings = resolved;
            }
        }
        return settings;
    }

    @Override
    public LodSettings effectiveSettings(final Player player) {
        final ServerPlayer handle = handle(player);
        return handle == null ? LodSettings.DISABLED : this.resolve(handle);
    }

    @Override
    public LodSettings effectiveSettings(final World world) {
        return this.resolve(level(world));
    }

    @Override
    public int advertisedRadius(final Player player) {
        final RegionizedPlayerChunkLoader.PlayerChunkLoaderData loader = loader(player);
        return loader == null ? 0 : loader.canvas$getAdvertisedRadius();
    }

    @Override
    public LodSettings configuredSettings(final World world) {
        final ServerLevel handle = level(world);
        final WorldConfig.WorldChunkSystem config = handle.canvasConfig().worldChunkSystem;
        final int official = handle.serverLevelData.canvas$distanceConfig.visualViewDistanceOrDefault();
        return new LodSettings(Math.max(0, official > 0 ? official : config.lodViewDistance), config.lodCutoffY);
    }

    @Override
    public int maxViewDistance() {
        return MoonriseConstants.MAX_VIEW_DISTANCE;
    }

    @Override
    public OptionalInt serverViewDistance() {
        return optional(this.serverViewDistance);
    }

    @Override
    public void setServerViewDistance(final int viewDistance) {
        this.serverViewDistance = checkViewDistance(viewDistance);
        io.canvasmc.canvas.GlobalConfiguration.getInstance().chunkSystem.visualViewDistance.vvDistance = this.serverViewDistance;
        this.refreshAll();
    }

    @Override
    public void clearServerViewDistance() {
        this.serverViewDistance = UNSET;
        this.refreshAll();
    }

    @Override
    public OptionalInt serverCutoffY() {
        return optional(this.serverCutoffY);
    }

    @Override
    public void setServerCutoffY(final int cutoffY) {
        this.serverCutoffY = Math.max(0, cutoffY);
        this.refreshAll();
    }

    @Override
    public void clearServerCutoffY() {
        this.serverCutoffY = UNSET;
        this.refreshAll();
    }

    @Override
    public void clearServerOverrides() {
        this.serverViewDistance = UNSET;
        this.serverCutoffY = UNSET;
        this.refreshAll();
    }

    @Override
    public OptionalInt worldViewDistance(final World world) {
        return optional(this.worldViewDistances.getInt(level(world).dimension()));
    }

    @Override
    public void setWorldViewDistance(final World world, final int viewDistance) {
        final ServerLevel handle = level(world);
        this.worldViewDistances.put(handle.dimension(), checkViewDistance(viewDistance));
        handle.serverLevelData.canvas$distanceConfig.setVisualViewDistance(viewDistance);
        handle.moonrise$getPlayerChunkLoader().setVisualViewDistance(viewDistance);
        this.refresh(world);
    }

    @Override
    public void clearWorldViewDistance(final World world) {
        final ServerLevel handle = level(world);
        this.worldViewDistances.removeInt(handle.dimension());
        handle.serverLevelData.canvas$distanceConfig.setVisualViewDistance(-1);
        handle.moonrise$getPlayerChunkLoader().setVisualViewDistance(-1);
        this.refresh(world);
    }

    @Override
    public OptionalInt worldCutoffY(final World world) {
        return optional(this.worldCutoffs.getInt(level(world).dimension()));
    }

    @Override
    public void setWorldCutoffY(final World world, final int cutoffY) {
        this.worldCutoffs.put(level(world).dimension(), Math.max(0, cutoffY));
        this.refresh(world);
    }

    @Override
    public void clearWorldCutoffY(final World world) {
        this.worldCutoffs.removeInt(level(world).dimension());
        this.refresh(world);
    }

    @Override
    public void clearWorldOverrides(final World world) {
        final ResourceKey<Level> dimension = level(world).dimension();
        this.worldViewDistances.removeInt(dimension);
        this.worldCutoffs.removeInt(dimension);
        this.refresh(world);
    }

    @Override
    public OptionalInt playerViewDistance(final Player player) {
        return optional(this.playerViewDistances.getInt(player.getUniqueId()));
    }

    @Override
    public void setPlayerViewDistance(final Player player, final int viewDistance) {
        this.playerViewDistances.put(player.getUniqueId(), checkViewDistance(viewDistance));
        final ServerPlayer handle = handle(player);
        if (handle != null) {
            ((ChunkSystemServerPlayer) handle).moonrise$getViewDistanceHolder().setVisualViewDistance(viewDistance);
        }
        this.refresh(player);
    }

    @Override
    public void clearPlayerViewDistance(final Player player) {
        this.playerViewDistances.removeInt(player.getUniqueId());
        final ServerPlayer handle = handle(player);
        if (handle != null) {
            ((ChunkSystemServerPlayer) handle).moonrise$getViewDistanceHolder().setVisualViewDistance(-1);
        }
        this.refresh(player);
    }

    @Override
    public OptionalInt playerCutoffY(final Player player) {
        return optional(this.playerCutoffs.getInt(player.getUniqueId()));
    }

    @Override
    public void setPlayerCutoffY(final Player player, final int cutoffY) {
        this.playerCutoffs.put(player.getUniqueId(), Math.max(0, cutoffY));
        this.refresh(player);
    }

    @Override
    public void clearPlayerCutoffY(final Player player) {
        this.playerCutoffs.removeInt(player.getUniqueId());
        this.refresh(player);
    }

    @Override
    public void clearPlayerOverrides(final Player player) {
        this.forget(player.getUniqueId());
        this.refresh(player);
    }

    // called on disconnect, so overrides never outlive a session
    public void forget(final UUID playerId) {
        this.playerViewDistances.removeInt(playerId);
        this.playerCutoffs.removeInt(playerId);
        this.updateOverrideFlag();
    }

    @Override
    public synchronized void registerResolver(final Plugin plugin, final LodResolver resolver) {
        Objects.requireNonNull(plugin, "plugin");
        Objects.requireNonNull(resolver, "resolver");

        final List<RegisteredResolver> updated = new ArrayList<>(this.resolvers);
        updated.add(new RegisteredResolver(plugin, resolver));
        updated.sort(Comparator.comparingInt(registered -> registered.resolver.priority()));

        this.resolvers = List.copyOf(updated);
        this.refreshAll();
    }

    @Override
    public synchronized boolean unregisterResolver(final LodResolver resolver) {
        final List<RegisteredResolver> updated = new ArrayList<>(this.resolvers);
        if (!updated.removeIf(registered -> registered.resolver == resolver)) {
            return false;
        }

        this.resolvers = List.copyOf(updated);
        this.refreshAll();
        return true;
    }

    @Override
    public synchronized int unregisterResolvers(final Plugin plugin) {
        final int before = this.resolvers.size();
        final List<RegisteredResolver> updated = new ArrayList<>(this.resolvers);
        updated.removeIf(registered -> registered.plugin == plugin);

        final int removed = before - updated.size();
        if (removed <= 0) {
            return 0;
        }

        this.resolvers = List.copyOf(updated);
        this.refreshAll();
        return removed;
    }

    @Override
    public void refresh(final Player player) {
        this.updateOverrideFlag();
        final RegionizedPlayerChunkLoader.PlayerChunkLoaderData loader = loader(player);
        if (loader != null) {
            loader.canvas$requestLodRefresh();
        }
    }

    @Override
    public void refresh(final World world) {
        this.updateOverrideFlag();
        for (final Player player : world.getPlayers()) {
            this.refresh(player);
        }
    }

    @Override
    public void refreshAll() {
        this.updateOverrideFlag();
        for (final World world : Bukkit.getWorlds()) {
            this.refresh(world);
        }
    }

    @Override
    public boolean isLodChunk(final Player player, final int chunkX, final int chunkZ) {
        final RegionizedPlayerChunkLoader.PlayerChunkLoaderData loader = loader(player);
        return loader != null && loader.canvas$isLodChunk(chunkX, chunkZ);
    }

    @Override
    public int lodChunkCount(final Player player) {
        final RegionizedPlayerChunkLoader.PlayerChunkLoaderData loader = loader(player);
        return loader == null ? 0 : loader.canvas$getLodChunkCount();
    }

    @Override
    public void invalidateCache(final World world) {
        level(world).extendedViewDistance().cache.invalidateAll();
    }

    @Override
    public void invalidateCache(final World world, final int chunkX, final int chunkZ) {
        level(world).extendedViewDistance().cache.invalidate(CoordinateUtils.getChunkKey(chunkX, chunkZ));
    }

    @Override
    public int cachedColumns() {
        int total = 0;
        for (final World world : Bukkit.getWorlds()) {
            total += level(world).extendedViewDistance().cache.size();
        }
        return total;
    }

    @Override
    public long cachedBytes() {
        return 0L;
    }

    private static int checkViewDistance(final int viewDistance) {
        if (viewDistance < 0) {
            throw new IllegalArgumentException("LOD view distance must be greater than or equal to 0, got " + viewDistance);
        }
        return viewDistance;
    }

    private static OptionalInt optional(final int value) {
        return value == UNSET ? OptionalInt.empty() : OptionalInt.of(value);
    }

    private static ServerLevel level(final World world) {
        return ((CraftWorld) world).getHandle();
    }

    private static @Nullable ServerPlayer handle(final Player player) {
        return player instanceof final CraftPlayer craftPlayer ? craftPlayer.getHandle() : null;
    }

    private static RegionizedPlayerChunkLoader.@Nullable PlayerChunkLoaderData loader(final Player player) {
        final ServerPlayer handle = handle(player);
        return handle == null ? null : ((ChunkSystemServerPlayer) handle).moonrise$getChunkLoader();
    }

    private record RegisteredResolver(Plugin plugin, LodResolver resolver) {
    }
}
