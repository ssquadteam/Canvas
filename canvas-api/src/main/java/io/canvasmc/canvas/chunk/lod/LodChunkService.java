package io.canvasmc.canvas.chunk.lod;

import java.util.OptionalInt;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/**
 * Settings resolve per player, each layer overriding the last: world config, server override, world override, player
 * override, then every {@link LodResolver} in ascending priority. Overrides live in memory only and player overrides
 * are dropped on disconnect.
 */
public interface LodChunkService {

    LodSettings effectiveSettings(final Player player);

    LodSettings effectiveSettings(final World world);

    default int effectiveViewDistance(final Player player) {
        return this.effectiveSettings(player).viewDistance();
    }

    default int effectiveCutoffY(final Player player) {
        return this.effectiveSettings(player).cutoffY();
    }

    /**
     * {@code 0} when the player is not connected.
     */
    int advertisedRadius(final Player player);

    /**
     * Ignores every override.
     */
    LodSettings configuredSettings(final World world);

    int maxViewDistance();

    OptionalInt serverViewDistance();

    /**
     * @throws IllegalArgumentException
     *     if negative
     */
    void setServerViewDistance(final int viewDistance);

    void clearServerViewDistance();

    OptionalInt serverCutoffY();

    void setServerCutoffY(final int cutoffY);

    void clearServerCutoffY();

    void clearServerOverrides();

    OptionalInt worldViewDistance(final World world);

    /**
     * @throws IllegalArgumentException
     *     if negative
     */
    void setWorldViewDistance(final World world, final int viewDistance);

    void clearWorldViewDistance(final World world);

    OptionalInt worldCutoffY(final World world);

    void setWorldCutoffY(final World world, final int cutoffY);

    void clearWorldCutoffY(final World world);

    void clearWorldOverrides(final World world);

    OptionalInt playerViewDistance(final Player player);

    /**
     * @throws IllegalArgumentException
     *     if negative
     */
    void setPlayerViewDistance(final Player player, final int viewDistance);

    void clearPlayerViewDistance(final Player player);

    OptionalInt playerCutoffY(final Player player);

    void setPlayerCutoffY(final Player player, final int cutoffY);

    void clearPlayerCutoffY(final Player player);

    void clearPlayerOverrides(final Player player);

    /**
     * Dropped when the plugin is disabled.
     */
    void registerResolver(final Plugin plugin, final LodResolver resolver);

    boolean unregisterResolver(final LodResolver resolver);

    int unregisterResolvers(final Plugin plugin);

    /**
     * Applies override changes now rather than on the next chunk loader tick.
     */
    void refresh(final Player player);

    void refresh(final World world);

    void refreshAll();

    /**
     * Must run on the region thread owning the player.
     */
    boolean isLodChunk(final Player player, final int chunkX, final int chunkZ);

    /**
     * Must run on the region thread owning the player.
     */
    int lodChunkCount(final Player player);

    /**
     * Forces later sends to re-read from disk, for when something outside the server rewrote the region files.
     */
    void invalidateCache(final World world);

    void invalidateCache(final World world, final int chunkX, final int chunkZ);

    int cachedColumns();
}
