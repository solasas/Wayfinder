package com.sashank.map_shortest_path_finder.pipeline;

import com.sashank.map_shortest_path_finder.model.Node;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Converts a raw Overpass API response into a graph ready for database insertion.
 *
 * Graph model used here:
 *   - Every OSM node referenced by a drivable way becomes a graph Node.
 *   - Every consecutive pair of nodes along a way becomes a directed Edge.
 *   - Bidirectional roads → two edges (A→B and B→A), same weight.
 *   - One-way roads (oneway=yes, or motorways) → one edge in the forward direction.
 *
 * Weight = Haversine distance in metres. This gives shortest-distance routing.
 * To switch to shortest-time, multiply by (1 / speed_limit_for_highway_type).
 *
 * Note on graph size:
 *   The "every OSM node = graph node" approach is simple and works well for
 *   small regions. A production router would simplify the graph by collapsing
 *   straight-line intermediate nodes (those with degree 2) into longer edges,
 *   reducing node count by ~80%. For a portfolio project this optimisation is
 *   not needed — Dijkstra on ~5k–15k nodes runs in well under 100 ms.
 */
@Component
public class OsmParser {

    private static final Logger log = LoggerFactory.getLogger(OsmParser.class);

    /**
     * The output of parsing: Node entities ready to be saved, plus edge descriptors
     * that reference nodes by their OSM ID (DB primary keys aren't assigned yet).
     */
    public record ParsedGraph(List<Node> nodes, List<ParsedEdge> edges) {}

    /**
     * An edge described in terms of OSM IDs, before the DB assigns primary keys.
     * GraphImporter translates these into Edge entities once nodes are saved.
     */
    public record ParsedEdge(long fromOsmId, long toOsmId, double weight, long osmWayId) {}

    public ParsedGraph parse(OsmResponse response) {
        // ── Step 1: split the flat element list into nodes and ways ──────────
        Map<Long, double[]> nodeCoords = new LinkedHashMap<>(); // osmId → [lat, lon]
        List<OsmResponse.OsmElement> ways = new ArrayList<>();

        for (OsmResponse.OsmElement elem : response.getElements()) {
            if ("node".equals(elem.getType())) {
                nodeCoords.put(elem.getId(), new double[]{elem.getLat(), elem.getLon()});
            } else if ("way".equals(elem.getType())
                    && elem.getNodes() != null
                    && elem.getNodes().size() >= 2) {
                ways.add(elem);
            }
        }

        // ── Step 2: keep only nodes that are actually used by ways ───────────
        // The Overpass response may contain extra nodes from nearby elements.
        Set<Long> referencedIds = ways.stream()
            .flatMap(w -> w.getNodes().stream())
            .collect(Collectors.toSet());

        Map<Long, Node> osmIdToNode = new LinkedHashMap<>();
        for (Long osmId : referencedIds) {
            double[] coords = nodeCoords.get(osmId);
            if (coords == null) continue; // referenced node not in bbox, skip

            osmIdToNode.put(osmId, Node.builder()
                .osmId(osmId)
                .lat(coords[0])
                .lng(coords[1])
                .build());
        }

        // ── Step 3: build edges from consecutive node pairs in each way ──────
        List<ParsedEdge> edges = new ArrayList<>();
        for (OsmResponse.OsmElement way : ways) {
            boolean oneway = isOneway(way.getTags());
            List<Long> refs = way.getNodes();

            for (int i = 0; i < refs.size() - 1; i++) {
                Node from = osmIdToNode.get(refs.get(i));
                Node to   = osmIdToNode.get(refs.get(i + 1));
                if (from == null || to == null) continue; // skip cross-bbox segments

                double dist = haversine(from.getLat(), from.getLng(),
                                        to.getLat(),   to.getLng());

                edges.add(new ParsedEdge(from.getOsmId(), to.getOsmId(), dist, way.getId()));
                if (!oneway) {
                    edges.add(new ParsedEdge(to.getOsmId(), from.getOsmId(), dist, way.getId()));
                }
            }
        }

        log.info("Parser output: {} nodes, {} directed edges ({} ways processed).",
                 osmIdToNode.size(), edges.size(), ways.size());
        return new ParsedGraph(new ArrayList<>(osmIdToNode.values()), edges);
    }

    /**
     * Returns true if traffic may only flow in the way's node order (forward direction).
     * Motorways are implicitly one-way in OSM even without an explicit tag.
     */
    private boolean isOneway(Map<String, String> tags) {
        if (tags == null) return false;
        String val     = tags.get("oneway");
        String highway = tags.get("highway");
        return "yes".equals(val) || "1".equals(val) || "true".equals(val)
            || "motorway".equals(highway) || "motorway_link".equals(highway);
    }

    /**
     * Great-circle distance between two WGS-84 points, in metres.
     * Haversine formula: accurate to ~0.5% for distances up to a few hundred km.
     */
    private double haversine(double lat1, double lng1, double lat2, double lng2) {
        final double R = 6_371_000.0; // Earth's mean radius in metres
        double dLat = Math.toRadians(lat2 - lat1);
        double dLng = Math.toRadians(lng2 - lng1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                 + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                 * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        return R * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }
}
