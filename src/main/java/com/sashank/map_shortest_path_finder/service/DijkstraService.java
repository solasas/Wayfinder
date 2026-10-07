package com.sashank.map_shortest_path_finder.service;

import org.springframework.stereotype.Service;

import java.util.*;
import java.util.function.ToDoubleFunction;
import java.util.stream.Collectors;

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

    /** nodesExpanded = how many nodes were actually popped and relaxed (not stale-skipped) — a simple efficiency signal for comparing search strategies on the same query. */
    public record PathResult(List<Long> nodeIds, double totalDistanceMeters, double totalTimeSeconds, int nodesExpanded) {}

    /** Shared min-heap entry type for all Dijkstra variants below: tentative cost, plus which node it's for. */
    private record Entry(double d, long nodeId) implements Comparable<Entry> {
        public int compareTo(Entry o) { return Double.compare(this.d, o.d); }
    }

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

        /**
         * Parses an `optimize` request value ("distance" or "time", case-insensitive).
         *
         * @throws IllegalArgumentException for any other value
         */
        public static WeightType fromQueryParam(String value) {
            return switch (value.toLowerCase()) {
                case "distance" -> DISTANCE;
                case "time" -> TIME;
                default -> throw new IllegalArgumentException(
                    "Invalid optimize value '" + value + "': must be 'distance' or 'time'");
            };
        }
    }

    /**
     * Per-search rules applied WHILE traversing, so callers never have to copy the (potentially
     * million-node) adjacency map just to forbid or re-weight a few edges. The shared graph is
     * never mutated.
     *
     * allowed — false hides the edge from this search entirely.
     * factor  — unitless multiplier on the edge's cost in the units being minimised (must be &gt; 0);
     *           it only steers the search: reported distance/time totals stay the real ones.
     */
    public interface EdgePolicy {
        /** Every edge allowed at its true cost: the behaviour of the policy-less overloads. */
        EdgePolicy ALL = (from, edge) -> true;

        boolean allowed(long fromId, GraphService.Neighbor edge);

        default double factor(GraphService.Neighbor edge) { return 1.0; }

        /** This policy plus a cost factor. */
        default EdgePolicy withFactor(java.util.function.ToDoubleFunction<GraphService.Neighbor> f) {
            EdgePolicy base = this;
            return new EdgePolicy() {
                public boolean allowed(long fromId, GraphService.Neighbor edge) { return base.allowed(fromId, edge); }
                public double factor(GraphService.Neighbor edge) { return base.factor(edge) * f.applyAsDouble(edge); }
            };
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
        return findShortestPath(adjacency, sourceId, targetId, weightType, EdgePolicy.ALL);
    }

    /**
     * Same search, with edges filtered/re-weighted on the fly by {@code policy}. The returned totals
     * are the true distance/time of the chosen edges even when {@code policy.factor} steered the search.
     */
    public Optional<PathResult> findShortestPath(
            Map<Long, List<GraphService.Neighbor>> adjacency,
            long sourceId,
            long targetId,
            WeightType weightType,
            EdgePolicy policy) {

        if (!adjacency.containsKey(sourceId) || !adjacency.containsKey(targetId)) {
            return Optional.empty();
        }

        if (sourceId == targetId) {
            return Optional.of(new PathResult(List.of(sourceId), 0.0, 0.0, 0));
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

        PriorityQueue<Entry> pq = new PriorityQueue<>();
        dist.put(sourceId, 0.0);
        distanceMetersAcc.put(sourceId, 0.0);
        timeSecondsAcc.put(sourceId, 0.0);
        pq.offer(new Entry(0.0, sourceId));

        int nodesExpanded = 0;

        // ── Main loop ────────────────────────────────────────────────────────
        while (!pq.isEmpty()) {
            Entry cur = pq.poll();

            // Lazy deletion: if this heap entry is stale (we already found a better path),
            // skip it. This is simpler than maintaining a decrease-key operation.
            if (cur.d() > dist.getOrDefault(cur.nodeId(), Double.MAX_VALUE)) continue;
            nodesExpanded++;

            if (cur.nodeId() == targetId) break; // target settled — stop early

            for (GraphService.Neighbor nb : adjacency.getOrDefault(cur.nodeId(), List.of())) {
                if (!policy.allowed(cur.nodeId(), nb)) continue;
                double newDist = cur.d() + weightType.costOf(nb) * policy.factor(nb);
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

        return Optional.of(new PathResult(path, distanceMetersAcc.get(targetId), timeSecondsAcc.get(targetId), nodesExpanded));
    }

    /** A node reached within the cutoff, and the cumulative cost (in weightType's units) to reach it. */
    public record Reachable(long nodeId, double cost) {}

    /**
     * Single-source traversal with no target: explores outward from source and returns
     * every node reachable within {@code cutoff}, along with the cost to reach each one.
     * Used for isochrones ("everywhere reachable in N minutes").
     *
     * Same min-heap approach as findShortestPath, but instead of stopping at a target it
     * stops expanding once the tentative cost would exceed the cutoff — edges beyond the
     * cutoff are simply never relaxed, so the search naturally stays within the budget
     * rather than exploring the whole graph and filtering afterwards.
     *
     * @param adjacency  the graph (nodeId → list of outgoing neighbours)
     * @param sourceId   start node DB id
     * @param cutoff     maximum cumulative cost (inclusive), in weightType's units
     * @param weightType which weight to accumulate (distance or time)
     * @return every reached node (including the source, at cost 0) with its cumulative cost
     */
    public Set<Reachable> findReachableNodes(
            Map<Long, List<GraphService.Neighbor>> adjacency,
            long sourceId,
            double cutoff,
            WeightType weightType) {

        if (!adjacency.containsKey(sourceId)) {
            return Set.of();
        }

        Map<Long, Double> dist = new HashMap<>();

        PriorityQueue<Entry> pq = new PriorityQueue<>();
        dist.put(sourceId, 0.0);
        pq.offer(new Entry(0.0, sourceId));

        while (!pq.isEmpty()) {
            Entry cur = pq.poll();

            // Lazy deletion: skip stale heap entries, same as findShortestPath.
            if (cur.d() > dist.getOrDefault(cur.nodeId(), Double.MAX_VALUE)) continue;

            for (GraphService.Neighbor nb : adjacency.getOrDefault(cur.nodeId(), List.of())) {
                double newDist = cur.d() + weightType.costOf(nb);
                if (newDist <= cutoff && newDist < dist.getOrDefault(nb.toNodeId(), Double.MAX_VALUE)) {
                    dist.put(nb.toNodeId(), newDist);
                    pq.offer(new Entry(newDist, nb.toNodeId()));
                }
            }
        }

        return dist.entrySet().stream()
            .map(e -> new Reachable(e.getKey(), e.getValue()))
            .collect(Collectors.toUnmodifiableSet());
    }

    /**
     * Builds the reverse of a directed adjacency map: an edge A→B in the input
     * becomes B→A in the output, with the same weights. Bidirectional search needs
     * this to grow a second frontier backwards from the target, since the target's
     * incoming edges aren't otherwise discoverable from a forward-only adjacency map.
     */
    public static Map<Long, List<GraphService.Neighbor>> buildReverseAdjacency(
            Map<Long, List<GraphService.Neighbor>> adjacency) {

        Map<Long, List<GraphService.Neighbor>> reverse = new HashMap<>();
        for (Long nodeId : adjacency.keySet()) {
            reverse.putIfAbsent(nodeId, new ArrayList<>());
        }
        for (Map.Entry<Long, List<GraphService.Neighbor>> entry : adjacency.entrySet()) {
            long from = entry.getKey();
            for (GraphService.Neighbor nb : entry.getValue()) {
                reverse.computeIfAbsent(nb.toNodeId(), k -> new ArrayList<>())
                       .add(new GraphService.Neighbor(from, nb.distanceMeters(), nb.timeSeconds(), nb.attrs()));
            }
        }
        return reverse;
    }

    /**
     * One relaxation step for a single frontier: pops the next non-stale heap entry
     * and relaxes its outgoing edges into the given dist/prev/accumulator maps.
     * Shared by both directions of {@link #findShortestPathBidirectional} — forward
     * calls it with the original adjacency map, backward calls it with the reverse.
     *
     * @return the node that was settled this call, or -1 if the frontier had
     *         nothing left but stale entries (i.e. this side is exhausted)
     */
    private long expandFrontier(
            PriorityQueue<Entry> pq,
            Map<Long, Double> dist,
            Map<Long, Long> prev,
            Map<Long, Double> distanceMetersAcc,
            Map<Long, Double> timeSecondsAcc,
            Map<Long, List<GraphService.Neighbor>> adjacency,
            WeightType weightType) {

        while (!pq.isEmpty()) {
            Entry cur = pq.poll();
            if (cur.d() > dist.getOrDefault(cur.nodeId(), Double.MAX_VALUE)) continue; // stale

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
            return cur.nodeId();
        }
        return -1;
    }

    /**
     * Finds the shortest source→target path using bidirectional Dijkstra: grows a
     * forward frontier from source over {@code adjacency} and a backward frontier
     * from target over its reverse (see {@link #buildReverseAdjacency}), alternating
     * one expansion per side per iteration.
     *
     * Meeting point: whenever a node has a known distance from *both* frontiers —
     * whether or not either side has settled it yet — that's a candidate full path
     * length (forward dist + backward dist through that node). The smallest such
     * candidate seen so far is the best meeting distance.
     *
     * Termination: once the sum of the two frontiers' smallest pending distances is
     * no better than the best meeting distance found, no node either side could still
     * expand to can improve on it (both frontiers' pending distances only grow from
     * here), so the search is already optimal — the two-sided generalisation of
     * stopping single-direction Dijkstra as soon as the target is popped.
     *
     * Returns the same {@link PathResult} shape as {@link #findShortestPath} so the
     * two can be compared directly on the same query, including nodesExpanded.
     *
     * @param adjacency  the graph (nodeId → list of outgoing neighbours)
     * @param sourceId   start node DB id
     * @param targetId   end node DB id
     * @param weightType which weight to minimise (distance or time)
     * @return the shortest path, or empty if no path exists
     */
    public Optional<PathResult> findShortestPathBidirectional(
            Map<Long, List<GraphService.Neighbor>> adjacency,
            long sourceId,
            long targetId,
            WeightType weightType) {

        if (!adjacency.containsKey(sourceId) || !adjacency.containsKey(targetId)) {
            return Optional.empty();
        }

        if (sourceId == targetId) {
            return Optional.of(new PathResult(List.of(sourceId), 0.0, 0.0, 0));
        }

        Map<Long, List<GraphService.Neighbor>> reverseAdjacency = buildReverseAdjacency(adjacency);

        // ── Forward frontier: grows from source over the original adjacency ────
        Map<Long, Double> distF = new HashMap<>();
        Map<Long, Long> prevF = new HashMap<>();
        Map<Long, Double> distanceMetersAccF = new HashMap<>();
        Map<Long, Double> timeSecondsAccF = new HashMap<>();
        PriorityQueue<Entry> pqF = new PriorityQueue<>();
        distF.put(sourceId, 0.0);
        distanceMetersAccF.put(sourceId, 0.0);
        timeSecondsAccF.put(sourceId, 0.0);
        pqF.offer(new Entry(0.0, sourceId));

        // ── Backward frontier: grows from target over the reverse adjacency ────
        Map<Long, Double> distB = new HashMap<>();
        Map<Long, Long> prevB = new HashMap<>();
        Map<Long, Double> distanceMetersAccB = new HashMap<>();
        Map<Long, Double> timeSecondsAccB = new HashMap<>();
        PriorityQueue<Entry> pqB = new PriorityQueue<>();
        distB.put(targetId, 0.0);
        distanceMetersAccB.put(targetId, 0.0);
        timeSecondsAccB.put(targetId, 0.0);
        pqB.offer(new Entry(0.0, targetId));

        double bestMeetingDistance = Double.POSITIVE_INFINITY;
        long meetingNode = -1;
        int nodesExpanded = 0;
        boolean expandForwardNext = true;

        // ── Main loop: alternate expanding one node from each frontier ─────────
        while (!pqF.isEmpty() && !pqB.isEmpty()) {
            if (pqF.peek().d() + pqB.peek().d() >= bestMeetingDistance) {
                break; // neither frontier can improve on the best meeting point from here
            }

            boolean expandingForward = expandForwardNext;
            expandForwardNext = !expandForwardNext;

            long settled = expandingForward
                ? expandFrontier(pqF, distF, prevF, distanceMetersAccF, timeSecondsAccF, adjacency, weightType)
                : expandFrontier(pqB, distB, prevB, distanceMetersAccB, timeSecondsAccB, reverseAdjacency, weightType);

            if (settled == -1) continue; // that side had only stale entries left this turn
            nodesExpanded++;

            Double ownDist = expandingForward ? distF.get(settled) : distB.get(settled);
            Double otherDist = expandingForward ? distB.get(settled) : distF.get(settled);
            if (otherDist != null) {
                double candidate = ownDist + otherDist;
                if (candidate < bestMeetingDistance) {
                    bestMeetingDistance = candidate;
                    meetingNode = settled;
                }
            }
        }

        if (meetingNode == -1) {
            return Optional.empty(); // frontiers never met — no path exists
        }

        // ── Reconstruct path ─────────────────────────────────────────────────
        // Forward half: walk backwards from meetingNode to source via prevF, then reverse.
        List<Long> path = new ArrayList<>();
        long cur = meetingNode;
        while (cur != sourceId) {
            path.add(cur);
            cur = prevF.get(cur);
        }
        path.add(sourceId);
        Collections.reverse(path);

        // Backward half: prevB[x] points towards target in the original graph
        // direction (it was built by relaxing reverse edges), so walking it forward
        // from meetingNode already yields the correct source→target order.
        cur = meetingNode;
        while (cur != targetId) {
            cur = prevB.get(cur);
            path.add(cur);
        }

        return Optional.of(new PathResult(
            path,
            distanceMetersAccF.get(meetingNode) + distanceMetersAccB.get(meetingNode),
            timeSecondsAccF.get(meetingNode) + timeSecondsAccB.get(meetingNode),
            nodesExpanded));
    }
}
