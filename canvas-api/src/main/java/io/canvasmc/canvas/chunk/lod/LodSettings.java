package io.canvasmc.canvas.chunk.lod;

/**
 * @param viewDistance
 *     in chunks, {@code 0} disables LOD
 * @param cutoffY
 *     truncates LOD columns below this level, {@code 0} sends full height
 */
public record LodSettings(int viewDistance, int cutoffY) {

    public static final LodSettings DISABLED = new LodSettings(0, 0);

    public LodSettings {
        if (viewDistance < 0) {
            throw new IllegalArgumentException("viewDistance must be greater than or equal to 0, got " + viewDistance);
        }
    }

    public boolean enabled() {
        return this.viewDistance > 0;
    }

    public boolean hasCutoff() {
        return this.cutoffY > 0;
    }

    public LodSettings withViewDistance(final int viewDistance) {
        return new LodSettings(viewDistance, this.cutoffY);
    }

    public LodSettings withCutoffY(final int cutoffY) {
        return new LodSettings(this.viewDistance, cutoffY);
    }
}
