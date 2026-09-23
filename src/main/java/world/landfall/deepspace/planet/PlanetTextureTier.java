package world.landfall.deepspace.planet;

/** Sampling rectangles retain the top-left world origin of each original 10x10-chunk face. */
public enum PlanetTextureTier {
    COARSE(1), MEDIUM(3), FULL(PlanetTextureLayout.FACE_SIZE / 16);

    private final int chunksPerFace;

    PlanetTextureTier(int chunksPerFace) {
        this.chunksPerFace = chunksPerFace;
    }

    public int faceSize() { return chunksPerFace * 16; }
    public int width() { return faceSize() * 3; }
    public int height() { return faceSize() * 2; }
    public int pixels() { return width() * height(); }
    public int chunks() { return 6 * chunksPerFace * chunksPerFace; }

    /** Map compact atlas chunk coordinates back onto the original full-resolution sample rectangle. */
    public int chunkOffsetX(int index) {
        int x = index % (chunksPerFace * 3);
        return x / chunksPerFace * FULL.chunksPerFace + x % chunksPerFace;
    }

    public int chunkOffsetZ(int index) {
        int z = index / (chunksPerFace * 3);
        return z / chunksPerFace * FULL.chunksPerFace + z % chunksPerFace;
    }

    /** Places a sampled source chunk into its compact atlas slot. */
    public int pixelIndex(int sampleIndex, int localX, int localZ) {
        int chunkX = sampleIndex % (chunksPerFace * 3);
        int chunkZ = sampleIndex / (chunksPerFace * 3);
        return (chunkZ * 16 + localZ) * width() + chunkX * 16 + localX;
    }

    /** The payload length identifies the tier without changing the existing network format. */
    public static PlanetTextureTier fromPixelCount(int length) {
        for (PlanetTextureTier tier : values()) {
            if (tier.pixels() == length) return tier;
        }
        return null;
    }

    public static PlanetTextureTier forDistance(double surfaceDistance) {
        return surfaceDistance <= 5000.0 ? FULL : surfaceDistance <= 7500.0 ? MEDIUM : COARSE;
    }
}
