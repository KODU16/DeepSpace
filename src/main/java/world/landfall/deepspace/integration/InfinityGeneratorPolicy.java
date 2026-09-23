package world.landfall.deepspace.integration;

import java.util.function.Supplier;

/** Chooses generator settings before Infinity creates any terrain or fluid rules. */
public final class InfinityGeneratorPolicy {
    private InfinityGeneratorPolicy() {
    }

    public static String resolveNoiseSettings(boolean prescribedRingHabitat, Supplier<String> infinitySettings) {
        return prescribedRingHabitat ? "minecraft:overworld" : infinitySettings.get();
    }
}
