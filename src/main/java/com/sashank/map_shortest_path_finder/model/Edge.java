package com.sashank.map_shortest_path_finder.model;

import jakarta.persistence.*;
import lombok.*;

/**
 * A directed graph edge representing one road segment between two nodes.
 *
 * Bidirectional roads produce two Edge rows (A→B and B→A) with identical weight.
 * One-way roads (oneway=yes in OSM, or motorways) produce only the forward edge.
 *
 * We store node IDs as plain longs rather than @ManyToOne references to keep
 * Dijkstra's adjacency lookups simple (no lazy-loading surprises).
 */
@Entity
@Table(name = "edges")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Edge {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "from_node_id", nullable = false)
    private Long fromNodeId;

    @Column(name = "to_node_id", nullable = false)
    private Long toNodeId;

    /** Haversine distance in metres. */
    @Column(name = "distance_meters", nullable = false)
    private Double distanceMeters;

    /** Typical free-flow speed for this road segment, derived from the OSM `highway` tag. */
    @Column(name = "speed_kmh", nullable = false)
    private Double speedKmh;

    /** OSM way ID this segment was derived from. Useful for debugging imports. */
    @Column(name = "osm_way_id")
    private Long osmWayId;

    /**
     * Raw OSM `highway` tag value, plus tri-state `toll`, `lit` and `paved` flags. All nullable:
     * rows imported before these columns existed have no values until a --force-reimport,
     * and null `toll`/`lit` also means "not tagged in OSM".
     */
    @Column(name = "highway")
    private String highway;

    @Column(name = "toll")
    private Boolean toll;

    @Column(name = "lit")
    private Boolean lit;

    /** True/false from the OSM `surface` tag (paved vs unpaved); null when untagged or unrecognised. */
    @Column(name = "paved")
    private Boolean paved;
}
