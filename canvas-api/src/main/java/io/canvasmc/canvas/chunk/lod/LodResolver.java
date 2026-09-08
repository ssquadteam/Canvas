package io.canvasmc.canvas.chunk.lod;

import org.bukkit.World;
import org.bukkit.entity.Player;
import org.jspecify.annotations.Nullable;

/**
 * Runs after the config value and every {@link LodChunkService} override. Queried often on the region thread owning
 * the player, so keep it cheap and never block.
 */
@FunctionalInterface
public interface LodResolver {

    /**
     * @param player
     *     {@code null} when resolving for a whole world
     * @param current
     *     return unchanged to abstain
     */
    LodSettings resolve(final @Nullable Player player, final World world, final LodSettings current);

    /**
     * Higher runs later and wins.
     */
    default int priority() {
        return 0;
    }
}
