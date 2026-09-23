package world.landfall.deepspace.integration;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Requires Infinity terrain candidates to inherit every non-generator rule from the Overworld. */
public final class InfiniteDimensionTypeContractTest {
    private InfiniteDimensionTypeContractTest() {
    }

    public static void main(String[] arguments) throws IOException {
        String source = Files.readString(Path.of(
                "src/main/java/world/landfall/deepspace/integration/InfiniteDimensionsIntegration.java"
        ));
        require(source.contains("DimensionType.DIRECT_CODEC.encodeStart")
                        && source.contains("server.overworld().dimensionType()"),
                "Generated dimension-type JSON must be encoded from the live Overworld type");
        require(source.contains("typeData.merge(overworldTypeData)"),
                "Infinity's in-memory type must also be replaced by the Overworld rules");
        require(!source.contains("dimensionType.addProperty(\"fixed_time\", 6000L)"),
                "Generated ring dimensions must not retain a non-Overworld fixed-time rule");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
