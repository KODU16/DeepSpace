package world.landfall.deepspace.integration;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Ensures the galaxy space dimension stays at midnight while planet time remains dynamic. */
public final class SpaceDimensionTimeContractTest {
    private SpaceDimensionTimeContractTest() {
    }

    public static void main(String[] arguments) throws IOException {
        String source = Files.readString(Path.of(
                "src/main/resources/data/deepspace/dimension_type/space.json"
        ));
        if (!source.contains("\"fixed_time\": 18000")) {
            throw new AssertionError("Galaxy space dimension must retain its fixed midnight time");
        }

        String integration = Files.readString(Path.of(
                "src/main/java/world/landfall/deepspace/integration/InfiniteDimensionsIntegration.java"
        ));
        if (!integration.contains("typeData.remove(\"fixed_time\")")) {
            throw new AssertionError("Ordinary generated planet dimensions must remove fixed_time");
        }
        if (!integration.contains("typeData.addProperty(\"fixed_time\", RingWorldNoonTime.NOON_DAY_TIME)")) {
            throw new AssertionError("Ring-world planet dimensions must remain fixed at noon");
        }
    }
}
