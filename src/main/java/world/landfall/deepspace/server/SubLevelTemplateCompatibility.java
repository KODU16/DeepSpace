package world.landfall.deepspace.server;

import java.util.Collection;

/** Validates Sable's saved section indexes before they are copied into a shorter dimension. */
final class SubLevelTemplateCompatibility {
    private SubLevelTemplateCompatibility() {
    }

    static int firstUnsupportedSection(Collection<String> sectionKeys, int destinationSectionCount) {
        if (destinationSectionCount < 1) {
            return Integer.MAX_VALUE;
        }
        for (String sectionKey : sectionKeys) {
            try {
                int sectionIndex = Integer.parseInt(sectionKey);
                if (sectionIndex < 0 || sectionIndex >= destinationSectionCount) {
                    return sectionIndex;
                }
            } catch (NumberFormatException exception) {
                return Integer.MAX_VALUE;
            }
        }
        return -1;
    }

}
