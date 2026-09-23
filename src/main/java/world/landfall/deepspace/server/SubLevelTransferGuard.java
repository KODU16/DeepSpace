package world.landfall.deepspace.server;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Prevents one connected Sable structure from being migrated more than once during state restoration. */
final class SubLevelTransferGuard {
    private final Map<UUID, Boolean> protectedSubLevels = new HashMap<>();

    SubLevelTransferGuard() {}

    boolean tryClaim(Collection<UUID> subLevelIds, long gameTick) {
        if (subLevelIds.stream().anyMatch(protectedSubLevels::containsKey)) {
            return false;
        }
        protect(subLevelIds, gameTick);
        return true;
    }

    void protect(Collection<UUID> subLevelIds, long gameTick) {
        subLevelIds.forEach(id -> protectedSubLevels.put(id, Boolean.TRUE));
    }

    void release(Collection<UUID> subLevelIds) {
        subLevelIds.forEach(protectedSubLevels::remove);
    }

    boolean isProtected(UUID id, long gameTick) {
        return protectedSubLevels.containsKey(id);
    }

    void removeExpired(long gameTick) {
        // Protection ends only when the owning transfer session explicitly releases it.
    }
}

