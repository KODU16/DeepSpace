package world.landfall.deepspace.integration;

import java.util.List;

/** Resolves Infinite Dimensions classes across its legacy and current Java package names. */
final class InfiniteDimensionsApi {
    private static final List<String> PACKAGE_ROOTS = List.of(
            "net.codexarchonic.infinity",
            "net.lerariemann.infinity"
    );
    private static volatile String resolvedRoot;

    private InfiniteDimensionsApi() {
    }

    static List<String> classCandidates(String relativeName) {
        return PACKAGE_ROOTS.stream().map(root -> root + "." + relativeName).toList();
    }

    static Class<?> loadClass(String relativeName) throws ClassNotFoundException {
        String root = resolvedRoot;
        if (root != null) {
            return Class.forName(root + "." + relativeName);
        }
        synchronized (InfiniteDimensionsApi.class) {
            if (resolvedRoot != null) {
                return Class.forName(resolvedRoot + "." + relativeName);
            }
            ClassNotFoundException failure = null;
            for (String candidate : classCandidates(relativeName)) {
                try {
                    Class<?> resolved = Class.forName(candidate);
                    resolvedRoot = candidate.substring(0, candidate.length() - relativeName.length() - 1);
                    return resolved;
                } catch (ClassNotFoundException exception) {
                    failure = exception;
                }
            }
            throw new ClassNotFoundException(
                    "No supported Infinite Dimensions API package contains " + relativeName,
                    failure
            );
        }
    }
}
