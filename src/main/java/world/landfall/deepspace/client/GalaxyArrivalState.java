package world.landfall.deepspace.client;

import org.jetbrains.annotations.Nullable;
import world.landfall.deepspace.network.GalaxyArrivalPacket;

/** Owns the client-side one-shot fade and five-second destination card timeline. */
public final class GalaxyArrivalState {
    static final long FADE_IN_NANOS = 1_000_000_000L;
    static final long FADE_OUT_NANOS = 1_000_000_000L;
    static final long CARD_NANOS = 5_000_000_000L;
    private static volatile GalaxyArrivalPacket arrival;
    private static volatile long startedAtNanos;

    private GalaxyArrivalState() {
    }

    public static void begin(GalaxyArrivalPacket packet) {
        arrival = packet;
        startedAtNanos = System.nanoTime();
    }

    public static Frame sample() {
        GalaxyArrivalPacket current = arrival;
        if (current == null) {
            return Frame.EMPTY;
        }
        long elapsed = Math.max(0L, System.nanoTime() - startedAtNanos);
        if (elapsed < FADE_IN_NANOS) {
            return new Frame((float) elapsed / FADE_IN_NANOS, null);
        }
        long fadeOutElapsed = elapsed - FADE_IN_NANOS;
        if (fadeOutElapsed < FADE_OUT_NANOS) {
            return new Frame(1.0F - (float) fadeOutElapsed / FADE_OUT_NANOS, null);
        }
        if (fadeOutElapsed - FADE_OUT_NANOS < CARD_NANOS) {
            return new Frame(0.0F, current);
        }
        arrival = null;
        return Frame.EMPTY;
    }

    /** VSIE calls this independently of the GUI layer, so use the same monotonic timeline. */
    public static boolean isCardVisible() {
        GalaxyArrivalPacket current = arrival;
        if (current == null) {
            return false;
        }
        long elapsed = Math.max(0L, System.nanoTime() - startedAtNanos);
        long cardStart = FADE_IN_NANOS + FADE_OUT_NANOS;
        return elapsed >= cardStart && elapsed < cardStart + CARD_NANOS;
    }

    public record Frame(float whiteAlpha, @Nullable GalaxyArrivalPacket arrival) {
        private static final Frame EMPTY = new Frame(0.0F, null);
    }
}
