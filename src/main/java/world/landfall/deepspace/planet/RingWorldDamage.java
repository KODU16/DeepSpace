package world.landfall.deepspace.planet;

import java.util.Random;

/** Encodes the four independently replaceable ring-world sections as a compact bit mask. */
public final class RingWorldDamage {
    public static final int SECTION_COUNT = RingWorldDimensions.SECTION_COUNT;
    public static final int VALID_MASK = (1 << SECTION_COUNT) - 1;
    // Keep generated damage masks active so the two GeckoLib damage variants are reachable.
    private static final boolean DAMAGE_VARIANTS_ENABLED = true;

    private RingWorldDamage() {
    }

    /** Rolls every section independently with a one-half chance of being broken. */
    public static int naturalMask(Random random) {
        int mask = 0;
        for (int index = 0; index < SECTION_COUNT; index++) {
            if (random.nextBoolean()) {
                mask |= 1 << index;
            }
        }
        return mask;
    }

    /** Selects exactly {@code count} distinct broken sections using a partial Fisher-Yates shuffle. */
    public static int maskWithCount(Random random, int count) {
        if (count < 0 || count > SECTION_COUNT) {
            throw new IllegalArgumentException("Broken ring-world section count must be in [0, 4]: " + count);
        }
        int[] indices = {0, 1, 2, 3};
        int mask = 0;
        for (int selected = 0; selected < count; selected++) {
            int choice = selected + random.nextInt(SECTION_COUNT - selected);
            int temporary = indices[selected];
            indices[selected] = indices[choice];
            indices[choice] = temporary;
            mask |= 1 << indices[selected];
        }
        return mask;
    }

    /** Selects primary-system damage only from the three non-Overworld sections. */
    public static int primaryMaskWithCount(Random random, int count) {
        if (count < 0 || count >= SECTION_COUNT) {
            throw new IllegalArgumentException("Primary broken ring-world section count must be in [0, 3]: " + count);
        }
        int[] candidates = {1, 2, 3};
        int mask = 0;
        for (int selected = 0; selected < count; selected++) {
            int choice = selected + random.nextInt(candidates.length - selected);
            int temporary = candidates[selected];
            candidates[selected] = candidates[choice];
            candidates[choice] = temporary;
            mask |= 1 << candidates[selected];
        }
        return mask;
    }

    /** Marks one percent of broken sections as repairable using the supplied deterministic stream. */
    public static int repairableMask(Random random, int brokenMask) {
        validateMask(brokenMask);
        int repairable = 0;
        for (int index = 0; index < SECTION_COUNT; index++) {
            if (isBroken(brokenMask, index) && random.nextInt(100) == 0) {
                repairable |= 1 << index;
            }
        }
        return repairable;
    }

    /** Applies the temporary global damage toggle without discarding stored or generated masks. */
    public static int activeMask(int mask) {
        validateMask(mask);
        return DAMAGE_VARIANTS_ENABLED ? mask : 0;
    }

    public static boolean isBroken(int mask, int sectionIndex) {
        validateMask(mask);
        if (sectionIndex < 0 || sectionIndex >= SECTION_COUNT) {
            throw new IllegalArgumentException("Ring-world section index must be in [0, 3]: " + sectionIndex);
        }
        return (mask & (1 << sectionIndex)) != 0;
    }

    public static int count(int mask) {
        validateMask(mask);
        return Integer.bitCount(mask);
    }

    public static void validateMask(int mask) {
        if ((mask & ~VALID_MASK) != 0 || mask < 0) {
            throw new IllegalArgumentException("Invalid broken ring-world section mask: " + mask);
        }
    }
}
