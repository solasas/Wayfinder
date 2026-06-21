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
     * Uses a Euclidean distance formula in lat/lng space — no PostGIS required.
     * At Rajahmundry's latitude (~17°N), 1° lat ≈ 111 km and 1° lng ≈ 106 km,
     * so the approximation error is under 3% and is perfectly fine for a demo.
     *
     * If PostGIS is installed and the geom column is populated (via PostGisSetupService),
     * the query in PostGisSetupService upgrades this to an O(log n) KNN index scan.
     */
    @Query(value = """
            SELECT id, osm_id, lat, lng
            FROM nodes
            ORDER BY (lat - :lat) * (lat - :lat) + (lng - :lng) * (lng - :lng)
            LIMIT 1
            """, nativeQuery = true)
    Optional<Node> findNearestTo(@Param("lat") double lat, @Param("lng") double lng);
}
