package com.sashank.map_shortest_path_finder.service;

import org.springframework.stereotype.Service;

import java.util.*;
import java.util.function.ToDoubleFunction;

/**
 * Dijkstra's shortest-path algorithm on an in-memory weighted directed graph.
 *
 * Design choice — adjacency map as a parameter (not @Autowired GraphService):
 *   This makes the class a pure algorithm; you can unit-test it by passing any
 *   hand-crafted adjacency map without Spring or a database.
 *
 * Algorithm summary:
 *   Use a min-heap (PriorityQueue) keyed on tentative distance.
 *   For each node popped from the heap, relax its outgoing edges.
 *   Lazy deletion handles duplicate heap entries (cheaper than a decrease-key).
 *   Stop as soon as the target node is popped (it's settled at that point).
 *   Reconstruct the path by walking the `prev` map backwards from target to source.
 *
 * Time complexity: O((V + E) log V) with a binary heap.
 */
@Service
public class DijkstraService {

    public record PathResult(List<Long> nodeIds, double totalDistanceMeters, double totalTimeSeconds) {}

    /**
     * Which of a Neighbor's two weights Dijkstra should minimise. Kept as a plain
     * enum (not GraphService) so DijkstraService stays a pure algorithm.
     */
    public enum WeightType {
        DISTANCE(GraphService.Neighbor::distanceMeters),
        TIME(GraphService.Neighbor::timeSeconds);

        private final ToDoubleFunction<GraphService.Neighbor> extractor;

        WeightType(ToDoubleFunction<GraphService.Neighbor> extractor) {
            this.extractor = extractor;
        }

        double costOf(GraphService.Neighbor neighbor) {
            return extractor.applyAsDouble(neighbor);
        }
    }

    /**
     * Finds the shortest path from source to target in the given adjacency map.
     *
     * @param adjacency  the graph (nodeId → list of outgoing neighbours)
     * @param sourceId   start node DB id
     * @param targetId   end node DB id
     * @param weightType which weight to minimise (distance or time)
     * @return the shortest path, or empty if no path exists
     */
    public Optional<PathResult> findShortestPath(
            Map<Long, List<GraphService.Neighbor>> adjacency,
            long sourceId,
            long targetId,
            WeightType weightType) {

        if (!adjacency.containsKey(sourceId) || !adjacency.containsKey(targetId)) {
            return Optional.empty();
        }

        if (sourceId == targetId) {
            return Optional.of(new PathResult(List.of(sourceId), 0.0, 0.0));
        }

        // ── Initialise ───────────────────────────────────────────────────────
        // dist[n] = best known cost from source to n, in the units of weightType
        Map<Long, Double> dist = new HashMap<>();
        // prev[n] = which node we came from on the best path to n (for reconstruction)
        Map<Long, Long> prev = new HashMap<>();
        // Running totals for the *other* weight along the best-known path to n,
        // so the response can report both distance and time regardless of which
        // one was optimised for.
        Map<Long, Double> distanceMetersAcc = new HashMap<>();
        Map<Long, Double> timeSecondsAcc = new HashMap<>();

        // Min-heap entry: [distanceAsLongBits, nodeId]
        // Encoding distance as a long lets us use a primitive array, but a record is clearer:
        record Entry(double d, long nodeId) implements Comparable<Entry> {
            public int compareTo(Entry o) { return Double.compare(this.d, o.d); }
        }

        PriorityQueue<Entry> pq = new PriorityQueue<>();
        dist.put(sourceId, 0.0);
        distanceMetersAcc.put(sourceId, 0.0);
        timeSecondsAcc.put(sourceId, 0.0);
        pq.offer(new Entry(0.0, sourceId));

        // ── Main loop ────────────────────────────────────────────────────────
        while (!pq.isEmpty()) {
            Entry cur = pq.poll();

            // Lazy deletion: if this heap entry is stale (we already found a better path),
            // skip it. This is simpler than maintaining a decrease-key operation.
            if (cur.d() > dist.getOrDefault(cur.nodeId(), Double.MAX_VALUE)) continue;

            if (cur.nodeId() == targetId) break; // target settled — stop early

            for (GraphService.Neighbor nb : adjacency.getOrDefault(cur.nodeId(), List.of())) {
                double newDist = cur.d() + weightType.costOf(nb);
                if (newDist < dist.getOrDefault(nb.toNodeId(), Double.MAX_VALUE)) {
                    dist.put(nb.toNodeId(), newDist);
                    prev.put(nb.toNodeId(), cur.nodeId());
                    distanceMetersAcc.put(nb.toNodeId(), distanceMetersAcc.get(cur.nodeId()) + nb.distanceMeters());
                    timeSecondsAcc.put(nb.toNodeId(), timeSecondsAcc.get(cur.nodeId()) + nb.timeSeconds());
                    pq.offer(new Entry(newDist, nb.toNodeId()));
                }
            }
        }

        // ── Check reachability ───────────────────────────────────────────────
        if (!dist.containsKey(targetId)) {
            return Optional.empty(); // target is in a disconnected component
        }

        // ── Reconstruct path ─────────────────────────────────────────────────
        // Walk backwards from target to source using the prev map
        List<Long> path = new ArrayList<>();
        long cur = targetId;
        while (cur != sourceId) {
            path.add(cur);
            cur = prev.get(cur);
        }
        path.add(sourceId);
        Collections.reverse(path);

        return Optional.of(new PathResult(path, distanceMetersAcc.get(targetId), timeSecondsAcc.get(targetId)));
    }
}
