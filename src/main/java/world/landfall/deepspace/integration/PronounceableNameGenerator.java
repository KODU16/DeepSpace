package world.landfall.deepspace.integration;

import java.util.Locale;
import java.util.Random;

/**
 * Builds short English-like names from consonant and vowel syllables.
 */
public final class PronounceableNameGenerator {
    private static final String[] ONSETS = {
            "b", "br", "c", "ch", "d", "dr", "f", "g", "gr", "h", "j", "k", "l", "m",
            "n", "p", "pr", "qu", "r", "s", "sh", "st", "t", "th", "tr", "v", "w", "z"
    };
    private static final String[] NUCLEI = {"a", "e", "i", "o", "u", "ae", "ai", "ea", "io", "ou"};
    private static final String[] CODAS = {"", "", "", "l", "m", "n", "r", "s", "th", "x"};

    private PronounceableNameGenerator() {
    }

    public static String generate(Random random, int minLength, int maxLength) {
        if (minLength < 1 || maxLength < minLength) {
            throw new IllegalArgumentException("Invalid generated-name length range");
        }
        int targetLength = minLength + random.nextInt(maxLength - minLength + 1);
        StringBuilder result = new StringBuilder(maxLength);
        while (result.length() < targetLength) {
            result.append(ONSETS[random.nextInt(ONSETS.length)]);
            result.append(NUCLEI[random.nextInt(NUCLEI.length)]);
            result.append(CODAS[random.nextInt(CODAS.length)]);
        }
        String normalized = result.substring(0, Math.min(result.length(), maxLength));
        if (normalized.length() < minLength) {
            normalized += "a".repeat(minLength - normalized.length());
        }
        return normalized.substring(0, 1).toUpperCase(Locale.ROOT)
                + normalized.substring(1).toLowerCase(Locale.ROOT);
    }
}
