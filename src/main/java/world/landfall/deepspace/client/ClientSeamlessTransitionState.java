package world.landfall.deepspace.client;

import java.util.concurrent.TimeUnit;

/**
 * Short-lived client gate used only while a DeepSpace respawn packet is being handled.
 */
public final class ClientSeamlessTransitionState {
    private static final long FAILSAFE_TIMEOUT_NANOS = TimeUnit.SECONDS.toNanos(20);
    private static volatile long activeUntilNanos;

    private ClientSeamlessTransitionState() {
    }

    public static void setActive(boolean active) {
        activeUntilNanos = active ? System.nanoTime() + FAILSAFE_TIMEOUT_NANOS : 0L;
    }

    public static boolean isActive() {
        return activeUntilNanos > System.nanoTime();
    }
}
