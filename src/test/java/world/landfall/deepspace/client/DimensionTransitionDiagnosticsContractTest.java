package world.landfall.deepspace.client;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Locks the bounded render-thread watchdog around client dimension replacement. */
public final class DimensionTransitionDiagnosticsContractTest {
    private DimensionTransitionDiagnosticsContractTest() {
    }

    public static void main(String[] arguments) throws IOException {
        String mixin = Files.readString(Path.of(
                "src/main/java/world/landfall/deepspace/mixin/MixinClientPacketListenerTransitionScreen.java"
        ));
        String diagnostics = Files.readString(Path.of(
                "src/main/java/world/landfall/deepspace/client/ClientDimensionTransitionDiagnostics.java"
        ));
        require(mixin.contains("at = @At(\"HEAD\")")
                        && mixin.contains("at = @At(\"RETURN\")"),
                "Client respawn handling must be timed from entry to return");
        require(diagnostics.contains("MAX_SAMPLES = 15")
                        && diagnostics.contains("renderThread.getStackTrace()")
                        && diagnostics.contains("[DEEPSPACE-TRANSITION-DIAG]"),
                "Slow transitions require a bounded independent render-thread sampler");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
