package com.sashank.map_shortest_path_finder.repository;

import com.sashank.map_shortest_path_finder.model.Node;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface NodeRepository extends JpaRepository<Node, Long> {

    Optional<Node> findByOsmId(Long osmId);

    /**
     * Returns the graph node geographically closest to (lat, lng).
     *
     * Uses the PostGIS KNN operator {@code <->} against the GiST index on {@code nodes.geom}: an index scan that
     * touches a handful of pages instead of sorting every row, which is what keeps snapping fast with ~1M nodes
     * (the previous formulation ordered the whole table by a computed distance on every request).
     *
     * {@code <->} on geometry is planar distance in degrees, which at this latitude (~17°N) distorts east-west
     * distances by ~4% — irrelevant for choosing the nearest road point. The geom column is filled by the
     * node_geom_trigger from lat/lng (see docker/postgres/init.sql), so PostGIS is required.
     */
    @Query(value = """
            SELECT id, osm_id, lat, lng
            FROM nodes
            ORDER BY geom <-> ST_SetSRID(ST_MakePoint(:lng, :lat), 4326)
            LIMIT 1
            """, nativeQuery = true)
    Optional<Node> findNearestTo(@Param("lat") double lat, @Param("lng") double lng);
}
