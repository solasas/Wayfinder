package com.sashank.map_shortest_path_finder.service;

import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Yen's k-shortest-paths algorithm: finds up to k distinct, increasing-cost
 * source→target routes, built on top of the existing single-path DijkstraService
 * rather than reimplementing pathfinding from scratch.
 *
 * Constructor injection (not the field-@Autowired style used elsewhere in this
 * package) so this stays trivially testable — `new AlternateRoutesService(new
 * DijkstraService())` — the same way DijkstraServiceTest builds a plain
 * DijkstraService, no Spring context required.
 *
 * Algorithm summary:
 *   accepted[0] is just findShortestPath(source, target).
 *   To find accepted[k]: for every "spur node" along accepted[k-1] (every node
 *   except the last), split that path into a root path (source..spurNode) and
 *   everything after. On a scratch copy of the adjacency map:
 *     - for every already-accepted path that shares this same root path, remove
 *       the specific edge it takes out of spurNode — otherwise Dijkstra would
 *       just rediscover a path already found.
 *     - remove every OTHER root-path node (not spurNode itself) entirely, so the
 *       spur search can't loop back through the root and produce a path that
 *       revisits a node.
 *   Rerun Dijkstra from spurNode to target on that pruned graph, splice the
 *   result onto the root path, and stash it as a candidate (if not already seen).
 *   Once every spur node of accepted[k-1] has been tried, the cheapest
 *   not-yet-accepted candidate — from this round OR any earlier one, the
 *   candidate pool persists across rounds — becomes accepted[k]. If the pool is
 *   ever empty, stop early: there simply aren't k distinct paths in the graph.
 *
 * Time complexity: O(k * V * (Dijkstra cost)) — each round reruns Dijkstra once
 * per node in the previously accepted path.
 */
@Service
public class AlternateRoutesService {

    private final DijkstraService dijkstraService;

    public AlternateRoutesService(DijkstraService dijkstraService) {
        this.dijkstraService = dijkstraService;
    }

    /** One accepted route: its node sequence and total cost in both units. */
    public record RouteCandidate(List<Long> nodeIds, double totalDistanceMeters, double totalTimeSeconds) {}

    /**
     * Finds up to {@code k} distinct source→target paths, cheapest first.
     *
     * @param adjacency  the graph (nodeId → list of outgoing neighbours) — never mutated
     * @param sourceId   start node DB id
     * @param targetId   end node DB id
     * @param k          how many alternates to look for (k=1 just returns the shortest path)
     * @param weightType which weight to minimise (distance or time)
     * @return up to k distinct paths in increasing cost order; empty if no path exists;
     *         fewer than k if the graph doesn't have k distinct routes
     */
    public List<RouteCandidate> findKShortestPaths(
            Map<Long, List<GraphService.Neighbor>> adjacency,
            long sourceId,
            long targetId,
            int k,
            DijkstraService.WeightType weightType) {

        if (k <= 0) {
            return List.of();
        }

        Optional<DijkstraService.PathResult> shortest =
            dijkstraService.findShortestPath(adjacency, sourceId, targetId, weightType);
        if (shortest.isEmpty()) {
            return List.of();
        }

        List<DijkstraService.PathResult> accepted = new ArrayList<>();
        accepted.add(shortest.get());

        Set<List<Long>> seen = new HashSet<>();
        seen.add(shortest.get().nodeIds());

        // Candidate pool for future rounds — NOT reset each round. A candidate
        // generated but not chosen in round k is still eligible in round k+1.
        PriorityQueue<DijkstraService.PathResult> candidates =
            new PriorityQueue<>(Comparator.comparingDouble(p -> costOf(p, weightType)));

        while (accepted.size() < k) {
            List<Long> previousPath = accepted.get(accepted.size() - 1).nodeIds();

            for (int i = 0; i < previousPath.size() - 1; i++) {
                long spurNode = previousPath.get(i);
                List<Long> rootPath = previousPath.subList(0, i + 1); // source..spurNode inclusive

                Map<Long, List<GraphService.Neighbor>> pruned = copyAdjacency(adjacency);

                // Remove the edge any already-accepted path takes out of spurNode,
                // if that path shares this exact root — otherwise Dijkstra would
                // just rediscover a path we already have.
                for (DijkstraService.PathResult acceptedPath : accepted) {
                    List<Long> nodeIds = acceptedPath.nodeIds();
                    if (nodeIds.size() > i + 1 && nodeIds.subList(0, i + 1).equals(rootPath)) {
                        long nextNode = nodeIds.get(i + 1);
                        List<GraphService.Neighbor> spurNeighbors = pruned.get(spurNode);
                        if (spurNeighbors != null) {
                            spurNeighbors.removeIf(n -> n.toNodeId() == nextNode);
                        }
                    }
                }

                // Remove every other root-path node so the spur search can't loop
                // back through the root and produce a path with a repeated node.
                for (int j = 0; j < i; j++) {
                    pruned.remove(rootPath.get(j));
                }

                Optional<DijkstraService.PathResult> spurResult =
                    dijkstraService.findShortestPath(pruned, spurNode, targetId, weightType);
                if (spurResult.isEmpty()) {
                    continue;
                }

                List<Long> spliced = new ArrayList<>(rootPath.subList(0, i)); // source..node-before-spur
                spliced.addAll(spurResult.get().nodeIds());                   // spurNode..target

                if (seen.contains(spliced)) {
                    continue;
                }
                seen.add(spliced);

                double[] rootCost = sumRootPathCost(adjacency, rootPath);
                candidates.offer(new DijkstraService.PathResult(
                    spliced,
                    rootCost[0] + spurResult.get().totalDistanceMeters(),
                    rootCost[1] + spurResult.get().totalTimeSeconds(),
                    spurResult.get().nodesExpanded()));
            }

            if (candidates.isEmpty()) {
                break; // fewer than k distinct paths exist between source and target
            }
            accepted.add(candidates.poll());
        }

        return accepted.stream()
            .map(p -> new RouteCandidate(p.nodeIds(), p.totalDistanceMeters(), p.totalTimeSeconds()))
            .toList();
    }

    private static double costOf(DijkstraService.PathResult path, DijkstraService.WeightType weightType) {
        return weightType == DijkstraService.WeightType.DISTANCE
            ? path.totalDistanceMeters()
            : path.totalTimeSeconds();
    }

    private static Map<Long, List<GraphService.Neighbor>> copyAdjacency(
            Map<Long, List<GraphService.Neighbor>> adjacency) {

        Map<Long, List<GraphService.Neighbor>> copy = new HashMap<>();
        for (Map.Entry<Long, List<GraphService.Neighbor>> e : adjacency.entrySet()) {
            copy.put(e.getKey(), new ArrayList<>(e.getValue()));
        }
        return copy;
    }

    /** Sums the distance/time of each edge along rootPath, read from the ORIGINAL (unpruned) adjacency. */
    private static double[] sumRootPathCost(Map<Long, List<GraphService.Neighbor>> adjacency, List<Long> rootPath) {
        double distance = 0.0;
        double time = 0.0;
        for (int i = 0; i < rootPath.size() - 1; i++) {
            long from = rootPath.get(i);
            long to = rootPath.get(i + 1);
            GraphService.Neighbor edge = adjacency.getOrDefault(from, List.of()).stream()
                .filter(n -> n.toNodeId() == to)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                    "No edge " + from + "->" + to + " in the original graph while summing root path cost"));
            distance += edge.distanceMeters();
            time += edge.timeSeconds();
        }
        return new double[]{distance, time};
    }
}