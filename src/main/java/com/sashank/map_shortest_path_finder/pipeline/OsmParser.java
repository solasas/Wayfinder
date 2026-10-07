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
 *   - One-way roads (oneway=yes/-1, motorways, roundabouts) → one edge in the direction of travel.
 *
 * Each edge carries two independent weights: distanceMeters (Haversine) and a
 * typical speedKmh looked up from the way's `highway` tag. GraphService derives
 * travel-time weights from these at load time, so both distance- and time-based
 * routing are available without re-parsing OSM data.
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

    /** Typical free-flow speed by OSM `highway` tag value. Unknown tags fall back to DEFAULT_SPEED_KMH. */
    private static final Map<String, Double> HIGHWAY_SPEED_KMH = Map.ofEntries(
        Map.entry("motorway",     80.0),
        Map.entry("trunk",        70.0),   // national highways between cities: far from the 40 km/h default
        Map.entry("primary",      60.0),
        Map.entry("secondary",    50.0),
        Map.entry("tertiary",     40.0),
        Map.entry("residential",  30.0),
        Map.entry("living_street", 15.0),
        Map.entry("service",      15.0)
    );
    private static final double DEFAULT_SPEED_KMH = 40.0;
    /** Slip roads and ramps (*_link) are slower than the road they belong to. */
    private static final double LINK_SPEED_CAP_KMH = 40.0;

    /**
     * The output of parsing: Node entities ready to be saved, plus edge descriptors
     * that reference nodes by their OSM ID (DB primary keys aren't assigned yet).
     */
    public record ParsedGraph(List<Node> nodes, List<ParsedEdge> edges) {}

    /**
     * An edge described in terms of OSM IDs, before the DB assigns primary keys.
     * GraphImporter translates these into Edge entities once nodes are saved.
     */
    public record ParsedEdge(long fromOsmId, long toOsmId, double distanceMeters, double speedKmh, long osmWayId,
                             String highway, Boolean toll, Boolean lit, Boolean paved) {}

    public ParsedGraph parse(OsmResponse response) {
        return parse(response, Set.of());
    }

    /**
     * @param skipWayIds ways that have already been imported (e.g. returned by a neighbouring tile): ignored, so
     *                   a way is never turned into edges twice.
     */
    public ParsedGraph parse(OsmResponse response, Set<Long> skipWayIds) {
        // ── Step 1: split the flat element list into nodes and ways ──────────
        Map<Long, double[]> nodeCoords = new LinkedHashMap<>(); // osmId → [lat, lon]
        List<OsmResponse.OsmElement> ways = new ArrayList<>();

        for (OsmResponse.OsmElement elem : response.getElements()) {
            if ("node".equals(elem.getType())) {
                nodeCoords.put(elem.getId(), new double[]{elem.getLat(), elem.getLon()});
            } else if ("way".equals(elem.getType())
                    && elem.getNodes() != null
                    && elem.getNodes().size() >= 2
                    && !skipWayIds.contains(elem.getId())) {
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
            Direction direction = directionOf(way.getTags());
            double speedKmh = speedKmhFor(way.getTags());
            String highway = way.getTags() == null ? null : way.getTags().get("highway");
            Boolean toll = yesNoTag(way.getTags(), "toll");
            Boolean lit = yesNoTag(way.getTags(), "lit");
            Boolean paved = pavedFromSurface(way.getTags());
            List<Long> refs = way.getNodes();

            for (int i = 0; i < refs.size() - 1; i++) {
                Node from = osmIdToNode.get(refs.get(i));
                Node to   = osmIdToNode.get(refs.get(i + 1));
                if (from == null || to == null) continue; // skip cross-bbox segments

                double dist = haversine(from.getLat(), from.getLng(),
                                        to.getLat(),   to.getLng());

                if (direction != Direction.REVERSE) {
                    edges.add(new ParsedEdge(from.getOsmId(), to.getOsmId(), dist, speedKmh, way.getId(), highway, toll, lit, paved));
                }
                if (direction != Direction.FORWARD) {
                    edges.add(new ParsedEdge(to.getOsmId(), from.getOsmId(), dist, speedKmh, way.getId(), highway, toll, lit, paved));
                }
            }
        }

        log.info("Parser output: {} nodes, {} directed edges ({} ways processed).",
                 osmIdToNode.size(), edges.size(), ways.size());
        return new ParsedGraph(new ArrayList<>(osmIdToNode.values()), edges);
    }

    private enum Direction { BOTH, FORWARD, REVERSE }

    /**
     * Which way traffic may flow relative to the way's node order.
     *   oneway=yes/1/true → FORWARD;  oneway=-1/reverse → REVERSE;  oneway=no → BOTH (explicit tag wins);
     *   otherwise implied: motorways and roundabouts are one-way (forward) even without a tag.
     */
    private Direction directionOf(Map<String, String> tags) {
        if (tags == null) return Direction.BOTH;
        String oneway = tags.get("oneway");
        if ("-1".equals(oneway) || "reverse".equals(oneway)) return Direction.REVERSE;
        if ("yes".equals(oneway) || "1".equals(oneway) || "true".equals(oneway)) return Direction.FORWARD;
        if ("no".equals(oneway) || "false".equals(oneway) || "0".equals(oneway)) return Direction.BOTH;
        String highway = tags.get("highway");
        String junction = tags.get("junction");
        if ("motorway".equals(highway) || "motorway_link".equals(highway)
                || "roundabout".equals(junction) || "circular".equals(junction)) {
            return Direction.FORWARD;
        }
        return Direction.BOTH;
    }

    /**
     * Reads a yes/no OSM tag as a tri-state: TRUE for yes/true/1, FALSE for no/false/0,
     * null when absent or any other value (e.g. lit=automatic, toll=maybe) — unknown,
     * rather than guessed.
     */
    private Boolean yesNoTag(Map<String, String> tags, String key) {
        if (tags == null) return null;
        String val = tags.get(key);
        if (val == null) return null;
        return switch (val) {
            case "yes", "true", "1" -> Boolean.TRUE;
            case "no", "false", "0" -> Boolean.FALSE;
            default -> null;
        };
    }

    private static final Set<String> PAVED_SURFACES = Set.of(
        "paved", "asphalt", "concrete", "concrete:plates", "concrete:lanes", "paving_stones", "sett",
        "cobblestone", "bricks", "metal", "wood");
    private static final Set<String> UNPAVED_SURFACES = Set.of(
        "unpaved", "gravel", "fine_gravel", "dirt", "ground", "earth", "sand", "grass", "mud",
        "compacted", "pebblestone", "rock", "clay");

    /** OSM `surface` as paved (TRUE) / unpaved (FALSE); null if untagged or an unrecognised value. */
    private Boolean pavedFromSurface(Map<String, String> tags) {
        if (tags == null || tags.get("surface") == null) return null;
        String surface = tags.get("surface");
        if (PAVED_SURFACES.contains(surface)) return Boolean.TRUE;
        if (UNPAVED_SURFACES.contains(surface)) return Boolean.FALSE;
        return null;
    }

    /**
     * Looks up a typical free-flow speed for the way's `highway` tag.
     * Unrecognised or missing tags fall back to DEFAULT_SPEED_KMH.
     */
    private double speedKmhFor(Map<String, String> tags) {
        if (tags == null) return DEFAULT_SPEED_KMH;
        String highway = tags.get("highway");
        if (highway != null && highway.endsWith("_link")) {
            String base = highway.substring(0, highway.length() - "_link".length());
            return Math.min(HIGHWAY_SPEED_KMH.getOrDefault(base, DEFAULT_SPEED_KMH), LINK_SPEED_CAP_KMH);
        }
        return HIGHWAY_SPEED_KMH.getOrDefault(highway, DEFAULT_SPEED_KMH);
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
