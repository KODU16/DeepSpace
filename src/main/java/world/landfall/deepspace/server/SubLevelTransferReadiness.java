package world.landfall.deepspace.server;

import java.util.UUID;

/** Defines the client-side state required before a transferred rider may be remounted. */
final class SubLevelTransferReadiness {
    private SubLevelTransferReadiness() {
    }

    static boolean canRestoreRiding(boolean expectedPresent, UUID expectedSubLevel, UUID trackedSubLevel) {
        return expectedPresent
                && expectedSubLevel != null
                && expectedSubLevel.equals(trackedSubLevel);
    }
}
