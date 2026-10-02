package world.landfall.deepspace.server;

import java.util.UUID;

/** Defines the client-side state required before a transferred rider may be remounted. */
final class SubLevelTransferReadiness {
    private SubLevelTransferReadiness() {
    }

    static boolean canRestoreRiding(boolean expectedPresent, UUID expectedSubLevel, UUID trackedSubLevel) {
        // A real destination hull and pose may arrive before Sable marks an unmounted pilot as tracking it.
        return expectedPresent
                && expectedSubLevel != null
                && (trackedSubLevel == null || expectedSubLevel.equals(trackedSubLevel));
    }
}
