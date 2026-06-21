package com.sashank.map_shortest_path_finder.service;

import com.sashank.map_shortest_path_finder.model.Edge;
import com.sashank.map_shortest_path_finder.model.Node;
import com.sashank.map_shortest_path_finder.repository.EdgeRepository;
import com.sashank.map_shortest_path_finder.repository.NodeRepository;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Loads the entire road graph from PostgreSQL into memory once at startup.
 *
 * Why in-memory?
 *   Running Dijkstra with live DB queries (one SELECT per edge relaxation) would
 *   produce hundreds of round-trips per route request. For a city-scale graph
 *   (~5k–15k nodes) the full load fits comfortably in a few MB of heap and makes
 *   each route calculation a pure in-memory operation taking < 100 ms.
 *
 * If the import hasn't run yet the graph will be empty. The isEmpty() check
 * in PathController lets the API return a clear 503 instead of a confusing 404.
 */
@Service
public class GraphService {

    private static final Logger log = LoggerFactory.getLogger(GraphService.class);

    /**
     * One entry in the adjacency list: the ID of a reachable neighbour and the
     * cost (metres) to reach it.
     */
    public record Neighbor(long toNodeId, double weight) {}

    @Autowired private NodeRepository nodeRepository;
    @Autowired private EdgeRepository edgeRepository;

    // nodeId → ordered list of outgoing neighbours
    private Map<Long, List<Neighbor>> adjacency = Collections.emptyMap();

    // nodeId → Node entity (for lat/lng lookups when building the path response)
    private Map<Long, Node> nodeIndex = Collections.emptyMap();

    @PostConstruct
    public void loadGraph() {
        List<Node> nodes = nodeRepository.findAll();
        List<Edge> edges = edgeRepository.findAll();

        if (nodes.isEmpty()) {
            log.warn("Graph is empty — run the import first: "
                   + "./mvnw spring-boot:run -Dspring-boot.run.profiles=import");
            return;
        }

        Map<Long, Node> index = new HashMap<>(nodes.size() * 2);
        for (Node n : nodes) index.put(n.getId(), n);

        // Pre-allocate adjacency lists to avoid repeated resizing
        Map<Long, List<Neighbor>> adj = new HashMap<>(nodes.size() * 2);
        for (Node n : nodes) adj.put(n.getId(), new ArrayList<>());

        for (Edge e : edges) {
            adj.computeIfAbsent(e.getFromNodeId(), k -> new ArrayList<>())
               .add(new Neighbor(e.getToNodeId(), e.getWeight()));
        }

        // Swap in the new graph atomically so reads during reload see a consistent view
        this.nodeIndex = Collections.unmodifiableMap(index);
        this.adjacency = Collections.unmodifiableMap(adj);

        log.info("Graph loaded into memory: {} nodes, {} directed edges.",
                 index.size(), edges.size());
    }

    public Map<Long, List<Neighbor>> getAdjacency() { return adjacency; }
    public Map<Long, Node>           getNodeIndex()  { return nodeIndex; }
    public boolean                   isEmpty()        { return nodeIndex.isEmpty(); }
}
