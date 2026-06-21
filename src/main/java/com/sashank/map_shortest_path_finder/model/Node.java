package com.sashank.map_shortest_path_finder.model;

import jakarta.persistence.*;
import lombok.*;

/**
 * A graph vertex: one OSM node that appears in at least one drivable road way.
 *
 * The database table also has a PostGIS `geom` column (populated by a trigger),
 * but we don't map it here — Hibernate manages only what it needs, and PostGIS
 * handles the geometry transparently for native spatial queries.
 */
@Entity
@Table(name = "nodes")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Node {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Original OpenStreetMap node ID. Unique across the planet. */
    @Column(name = "osm_id", unique = true, nullable = false)
    private Long osmId;

    @Column(nullable = false)
    private Double lat;

    @Column(nullable = false)
    private Double lng;
}
