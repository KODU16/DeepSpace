package world.landfall.deepspace.physics;

/** Verifies that only Deep Space dimensions override vanilla gravity. */
public final class EntityGravityRegistryContractTest {
    private EntityGravityRegistryContractTest() {
    }

    public static void main(String[] arguments) {
        require(EntityGravityRegistry.adjustGravity("deepspace:space", 0.08D) == 0.0D,
                "The primary space dimension must be weightless");
        require(EntityGravityRegistry.adjustGravity("deepspace:galaxy_alpha", 0.08D) == 0.0D,
                "Generated galaxy dimensions must be weightless");
        require(EntityGravityRegistry.adjustGravity("deepspace:aridia", 0.08D) == 0.08D,
                "Planet dimensions must retain vanilla gravity");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
