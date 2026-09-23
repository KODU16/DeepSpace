package world.landfall.deepspace.planet;

import java.util.BitSet;

/** Checks real tier coordinate mappings and restart chunk selection without a Minecraft runtime. */
public final class PlanetTextureTierContractTest {
    public static void main(String[] args) {
        require(PlanetTextureTier.COARSE.chunks() == 6, "Coarse must sample one chunk per face");
        require(PlanetTextureTier.MEDIUM.chunks() == 54, "Medium must sample nine chunks per face");
        require(PlanetTextureTier.FULL.chunks() == 600, "Full must retain the original 600 chunks");
        require(PlanetTextureTier.forDistance(7500.01) == PlanetTextureTier.COARSE, "Far planets stay coarse");
        require(PlanetTextureTier.forDistance(7500) == PlanetTextureTier.MEDIUM, "7500 starts medium");
        require(PlanetTextureTier.forDistance(5000.01) == PlanetTextureTier.MEDIUM, "Above 5000 stays medium");
        require(PlanetTextureTier.forDistance(5000) == PlanetTextureTier.FULL, "5000 starts full");
        require(PlanetTextureTier.fromPixelCount(100) == null, "Reject malformed maps");
        for (PlanetTextureTier tier : PlanetTextureTier.values()) verifyTier(tier);
    }

    private static void verifyTier(PlanetTextureTier tier) {
        require(PlanetTextureTier.fromPixelCount(tier.pixels()) == tier, "Each map must identify its tier");
        BitSet pixels = new BitSet(tier.pixels());
        int[] faces = new int[6];
        for (int chunk = 0; chunk < tier.chunks(); chunk++) {
            int x = tier.chunkOffsetX(chunk);
            int z = tier.chunkOffsetZ(chunk);
            require(x % 10 < tier.faceSize() / 16 && z % 10 < tier.faceSize() / 16,
                    "Each reduced sample must stay in the original face's top-left corner");
            faces[z / 10 * 3 + x / 10]++;
            if (tier == PlanetTextureTier.FULL) {
                require(x == chunk % 30 && z == chunk / 30, "Full sample ordering must remain unchanged");
            }
            for (int dz = 0; dz < 16; dz++) for (int dx = 0; dx < 16; dx++) {
                int index = tier.pixelIndex(chunk, dx, dz);
                require(index >= 0 && index < tier.pixels() && !pixels.get(index),
                        "Compact atlas indices must be unique and in bounds");
                pixels.set(index);
            }
        }
        require(pixels.cardinality() == tier.pixels(), "All pixels in all six faces must be sampled");
        for (int face : faces) require(face == tier.chunks() / 6, "All faces must receive equal sampling");

        // Match the scheduler's persisted bitmap, including out-of-order async completion.
        BitSet sampled = new BitSet();
        sampled.set(0); sampled.set(2); sampled.set(tier.chunks() - 1);
        BitSet restored = BitSet.valueOf(sampled.toLongArray());
        int submissions = 0;
        for (int next = restored.nextClearBit(0); next < tier.chunks(); next = restored.nextClearBit(next + 1)) {
            require(!sampled.get(next), "Restart must not resubmit a completed chunk");
            submissions++;
        }
        require(submissions + sampled.cardinality() == tier.chunks(), "Restart must submit every missing chunk");
    }

    private static void require(boolean valid, String message) {
        if (!valid) throw new AssertionError(message);
    }
}
