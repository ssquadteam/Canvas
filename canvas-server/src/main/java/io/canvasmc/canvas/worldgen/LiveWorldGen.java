package io.canvasmc.canvas.worldgen;

import ca.spottedleaf.moonrise.patches.chunk_system.level.ChunkSystemServerLevel;
import com.mojang.logging.LogUtils;
import io.canvasmc.canvas.GlobalConfiguration;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import it.unimi.dsi.fastutil.longs.LongSets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.jspecify.annotations.NullMarked;
import org.slf4j.Logger;

/**
 * Keeps terrain generated out to the extended view distance while players move, without handing the work to the chunk
 * system.
 * <p>
 * The chunk system owns every chunk it holds, so this only ever touches chunks it does not: tiles containing a live
 * holder are left alone, and a write is dropped if the server has taken the chunk in the meantime. What is left is the
 * ring between the server's own view distance and the extended one, which is exactly the terrain the extended distance
 * needs and the server will never generate on its own.
 */
@NullMarked
public final class LiveWorldGen {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static volatile LiveWorldGen instance;
    private static final java.util.Set<Anchor> ANCHORS = ConcurrentHashMap.newKeySet();
    private static final AtomicLong TICKS = new AtomicLong();

    private final int distance;
    private final int tile;
    private final int maxInFlight;
    private final ExecutorService workers;
    private final Map<ServerLevel, LongSet> done = new ConcurrentHashMap<>();
    private final java.util.Set<Claim> claimed = ConcurrentHashMap.newKeySet();
    private final int spacing;
    private final AtomicInteger inFlight = new AtomicInteger();
    private final AtomicLong chunks = new AtomicLong();
    private final AtomicLong tiles = new AtomicLong();

    private LiveWorldGen(final int distance, final int tile, final int threads) {
        this.distance = distance;
        this.tile = tile;
        this.maxInFlight = threads * 2;
        // two tiles closer than this share padding, so generating both at once would generate and write the same
        // chunks twice
        this.spacing = 1 + (((WorldGenPipeline.reach(ChunkStatus.FEATURES) * 2) + tile - 1) / tile);

        final ThreadFactory factory = new ThreadFactory() {
            private final AtomicInteger id = new AtomicInteger();

            @Override
            public Thread newThread(final Runnable run) {
                final Thread thread = new Thread(run, "canvas-worldgen-frontier-" + this.id.getAndIncrement());
                thread.setDaemon(true);
                thread.setPriority(Thread.NORM_PRIORITY - 2);
                return thread;
            }
        };
        this.workers = Executors.newFixedThreadPool(threads, factory);
    }

    public static void tick(final MinecraftServer server) {
        final GlobalConfiguration.WorldGeneration config = GlobalConfiguration.getInstance().worldGeneration;
        LiveWorldGen live = instance;

        if (!config.frontierEnabled) {
            if (live != null) {
                live.workers.shutdownNow();
                instance = null;
            }
            return;
        }

        if (live == null) {
            final int threads = config.frontierThreads > 0
                ? config.frontierThreads
                : Math.max(1, Runtime.getRuntime().availableProcessors() / 4);
            live = new LiveWorldGen(config.frontierDistance, config.frontierTileSize, threads);
            instance = live;
            LOGGER.info("Frontier world generation running out to {} chunks on {} threads", live.distance, threads);
        }

        // the server's own tick count is not readable under region threading
        if (TICKS.incrementAndGet() % 20L != 0L) {
            return;
        }
        live.submit(server);
    }

    public static String status() {
        final LiveWorldGen live = instance;
        if (live == null) {
            return "frontier generation is off";
        }
        return String.format(
            java.util.Locale.ROOT,
            "frontier: %d chunks over %d tiles, %d in flight, %d anchors, distance %d, tile %d, spacing %d",
            live.chunks.get(), live.tiles.get(), live.inFlight.get(), ANCHORS.size(), live.distance, live.tile, live.spacing
        );
    }

    /**
     * Anchors keep terrain generated around a fixed point, the way a player does. Useful around spawn, and for driving
     * the generator without a client attached.
     */
    public static void anchor(final ServerLevel level, final int chunkX, final int chunkZ) {
        ANCHORS.add(new Anchor(level, chunkX, chunkZ));
    }

    public static void clearAnchors() {
        ANCHORS.clear();
    }

    private void submit(final MinecraftServer server) {
        if (this.inFlight.get() >= this.maxInFlight) {
            return;
        }

        final List<Anchor> points = new ArrayList<>(ANCHORS);
        for (final ServerPlayer player : server.getPlayerList().getPlayers()) {
            final ChunkPos position = player.chunkPosition();
            points.add(new Anchor(player.level(), position.x(), position.z()));
        }

        final List<Candidate> candidates = new ArrayList<>();
        for (final Anchor point : points) {
            final ServerLevel level = point.level();
            final LongSet finished = this.done.computeIfAbsent(level, ignored -> LongSets.synchronize(new LongOpenHashSet()));
            final ChunkPos position = new ChunkPos(point.chunkX(), point.chunkZ());
            final int radius = (this.distance / this.tile) + 1;
            final int centerTileX = Math.floorDiv(position.x(), this.tile);
            final int centerTileZ = Math.floorDiv(position.z(), this.tile);

            for (int tileZ = centerTileZ - radius; tileZ <= centerTileZ + radius; ++tileZ) {
                for (int tileX = centerTileX - radius; tileX <= centerTileX + radius; ++tileX) {
                    final long key = ChunkPos.pack(tileX, tileZ);
                    if (finished.contains(key)) {
                        continue;
                    }

                    final int dx = ((tileX * this.tile) + (this.tile / 2)) - position.x();
                    final int dz = ((tileZ * this.tile) + (this.tile / 2)) - position.z();
                    final int distanceSq = (dx * dx) + (dz * dz);
                    if (distanceSq > this.distance * this.distance) {
                        continue;
                    }
                    candidates.add(new Candidate(level, tileX, tileZ, key, distanceSq));
                }
            }
        }

        candidates.sort((left, right) -> Integer.compare(left.distanceSq, right.distanceSq));

        for (final Candidate candidate : candidates) {
            if (this.inFlight.get() >= this.maxInFlight) {
                return;
            }

            final Claim claim = new Claim(candidate.level, candidate.tileX, candidate.tileZ);
            if (this.overlapsClaim(claim) || !this.claimed.add(claim)) {
                continue;
            }
            this.inFlight.incrementAndGet();
            this.workers.execute(() -> this.generate(candidate));
        }
    }

    private void generate(final Candidate candidate) {
        try {
            final int minX = candidate.tileX * this.tile;
            final int minZ = candidate.tileZ * this.tile;
            if (held(candidate.level, minX, minZ, this.tile)) {
                // the chunk system owns part of this tile, leave it and come back once the player has moved on
                return;
            }

            final WorldGenPipeline.Result result = WorldGenPipeline.generate(
                candidate.level, minX, minZ, this.tile, this.tile, ChunkStatus.FEATURES
            );
            this.done.computeIfAbsent(candidate.level, ignored -> LongSets.synchronize(new LongOpenHashSet()))
                .add(candidate.key);
            this.chunks.addAndGet(result.chunks());
            this.tiles.incrementAndGet();
        } catch (final Throwable thr) {
            LOGGER.error("Frontier generation failed for tile {}, {}", candidate.tileX, candidate.tileZ, thr);
        } finally {
            this.claimed.remove(new Claim(candidate.level, candidate.tileX, candidate.tileZ));
            this.inFlight.decrementAndGet();
        }
    }

    private boolean overlapsClaim(final Claim claim) {
        for (final Claim other : this.claimed) {
            if (other.level() == claim.level()
                && Math.abs(other.tileX() - claim.tileX()) < this.spacing
                && Math.abs(other.tileZ() - claim.tileZ()) < this.spacing) {
                return true;
            }
        }
        return false;
    }

    static boolean held(final ServerLevel level, final int minChunkX, final int minChunkZ, final int size) {
        final var manager = ((ChunkSystemServerLevel) level).moonrise$getChunkTaskScheduler().chunkHolderManager;
        for (int z = minChunkZ; z < minChunkZ + size; ++z) {
            for (int x = minChunkX; x < minChunkX + size; ++x) {
                if (manager.getChunkHolder(x, z) != null) {
                    return true;
                }
            }
        }
        return false;
    }

    private record Candidate(ServerLevel level, int tileX, int tileZ, long key, int distanceSq) {
    }

    private record Anchor(ServerLevel level, int chunkX, int chunkZ) {
    }

    private record Claim(ServerLevel level, int tileX, int tileZ) {
    }
}
