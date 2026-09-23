package world.landfall.deepspace.planet;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Places a wormhole graph along its ecliptic bearings with stable per-edge length variation. */
public final class StarMapGraphLayout {
    public static final double EDGE_LENGTH = 140.0D;
    public static final double MIN_EDGE_LENGTH = 98.0D;
    public static final double MAX_EDGE_LENGTH = 182.0D;

    private StarMapGraphLayout() {
    }

    public static Layout arrange(Collection<Node> nodes, Collection<Portal> portals, String rootId) {
        Map<String, Node> nodesById = new LinkedHashMap<>();
        nodes.forEach(node -> nodesById.put(node.id(), node));
        Map<String, List<Portal>> adjacency = new HashMap<>();
        for (Portal portal : portals) {
            adjacency.computeIfAbsent(portal.sourceId(), ignored -> new ArrayList<>()).add(portal);
            adjacency.computeIfAbsent(portal.targetId(), ignored -> new ArrayList<>())
                    .add(new Portal(portal.targetId(), portal.sourceId(), normalize(portal.angleRadians() + Math.PI)));
        }

        Map<String, Point> positions = new LinkedHashMap<>();
        Set<Edge> edges = new HashSet<>();
        String root = nodesById.containsKey(rootId) ? rootId : nodesById.keySet().stream().findFirst().orElse(null);
        if (root != null) {
            placeComponent(root, new Point(0.0D, 0.0D), adjacency, positions, edges);
        }

        double componentX = EDGE_LENGTH * 2.0D;
        for (String nodeId : nodesById.keySet()) {
            if (!positions.containsKey(nodeId)) {
                placeComponent(nodeId, new Point(componentX, 0.0D), adjacency, positions, edges);
                componentX += EDGE_LENGTH * 2.0D;
            }
        }
        return new Layout(nodesById, positions, List.copyOf(edges));
    }

    private static void placeComponent(
            String root,
            Point origin,
            Map<String, List<Portal>> adjacency,
            Map<String, Point> positions,
            Set<Edge> edges
    ) {
        ArrayDeque<String> queue = new ArrayDeque<>();
        positions.put(root, origin);
        queue.add(root);
        while (!queue.isEmpty()) {
            String source = queue.removeFirst();
            Point sourcePoint = positions.get(source);
            for (Portal portal : adjacency.getOrDefault(source, List.of())) {
                edges.add(Edge.undirected(portal.sourceId(), portal.targetId()));
                if (positions.containsKey(portal.targetId())) {
                    continue;
                }
                double edgeLength = edgeLength(portal.sourceId(), portal.targetId());
                positions.put(portal.targetId(), new Point(
                        sourcePoint.x() + Math.cos(portal.angleRadians()) * edgeLength,
                        sourcePoint.y() + Math.sin(portal.angleRadians()) * edgeLength
                ));
                queue.addLast(portal.targetId());
            }
        }
    }

    private static double normalize(double radians) {
        double normalized = radians % (Math.PI * 2.0D);
        return normalized < 0.0D ? normalized + Math.PI * 2.0D : normalized;
    }

    /** Derives one reproducible length for both directions of an undirected edge. */
    public static double edgeLength(String firstId, String secondId) {
        String low = firstId.compareTo(secondId) <= 0 ? firstId : secondId;
        String high = firstId.compareTo(secondId) <= 0 ? secondId : firstId;
        long hash = 0xCBF29CE484222325L;
        for (int index = 0; index < low.length(); index++) {
            hash = (hash ^ low.charAt(index)) * 0x100000001B3L;
        }
        hash = (hash ^ 0xFFL) * 0x100000001B3L;
        for (int index = 0; index < high.length(); index++) {
            hash = (hash ^ high.charAt(index)) * 0x100000001B3L;
        }
        double sample = (mix64(hash) >>> 11) * 0x1.0p-53;
        return MIN_EDGE_LENGTH + sample * (MAX_EDGE_LENGTH - MIN_EDGE_LENGTH);
    }

    private static long mix64(long value) {
        value = (value ^ (value >>> 30)) * 0xBF58476D1CE4E5B9L;
        value = (value ^ (value >>> 27)) * 0x94D049BB133111EBL;
        return value ^ (value >>> 31);
    }

    public record Node(String id, String label, boolean explored) {
    }

    public record Portal(String sourceId, String targetId, double angleRadians) {
    }

    public record Point(double x, double y) {
    }

    public record Edge(String firstId, String secondId) {
        private static Edge undirected(String firstId, String secondId) {
            return firstId.compareTo(secondId) <= 0
                    ? new Edge(firstId, secondId)
                    : new Edge(secondId, firstId);
        }
    }

    public record Layout(Map<String, Node> nodes, Map<String, Point> positions, List<Edge> edges) {
    }
}
