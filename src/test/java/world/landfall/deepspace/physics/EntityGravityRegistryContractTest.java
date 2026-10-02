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
        require(EntityGravityRegistry.adjustGravity("deepspace:galaxy_1180063_paxqueathq", -11.0D) == 0.0D,
                "Runtime galaxies must suppress downward acceleration of any magnitude");
        require(EntityGravityRegistry.adjustGravity("deepspace:space", -0.03D) == 0.0D,
                "Fixed fishing-hook acceleration must also be removed in space");
        require(EntityGravityRegistry.adjustGravity("infinity:generated_1901891405274703094", 0.08D) == 0.08D,
                "Generated planets must retain their native entity gravity");
        require(EntityGravityRegistry.adjustGravity("minecraft:the_end", 0.04D) == 0.04D,
                "Ordinary dimensions must retain native gravity");
        require(EntityGravityRegistry.adjustGravity("othermod:galaxy_alpha", 0.08D) == 0.08D,
                "A galaxy-like path in another namespace must not be mistaken for Deep Space");
        require(EntityGravityRegistry.adjustGravity("deepspace:aridia", 0.08D) == 0.08D,
                "Planet dimensions must retain vanilla gravity");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
