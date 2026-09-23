package world.landfall.deepspace.planet;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Locks ring damage activation, primary-section selection and one-percent repairability. */
public final class RingWorldDamageContractTest {
    private RingWorldDamageContractTest() {
    }

    public static void main(String[] arguments) throws IOException {
        String damage = Files.readString(Path.of(
                "src/main/java/world/landfall/deepspace/planet/RingWorldDamage.java"
        ));
        require(damage.contains("private static final boolean DAMAGE_VARIANTS_ENABLED = true"),
                "Generated ring damage variants must remain active");
        require(damage.contains("random.nextInt(100) == 0"),
                "Exactly one percent repairability rule must be retained");
        require(damage.contains("int[] candidates = {1, 2, 3}"),
                "Primary ring damage must exclude the healthy Overworld section");

        String integration = Files.readString(Path.of(
                "src/main/java/world/landfall/deepspace/integration/InfiniteDimensionsIntegration.java"
        ));
        require(integration.contains("Config.RING_WORLD_BROKEN_SECTION_COUNT.get()"),
                "Primary ring damage count must come from the main-world config");
        require(integration.contains("RingWorldDamage.repairableMask(damageRandom, brokenSections)"),
                "Primary ring damage must classify repairable sections separately");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
