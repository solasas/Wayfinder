package com.sashank.map_shortest_path_finder.service;

import com.sashank.map_shortest_path_finder.config.RegionConfig;
import com.sashank.map_shortest_path_finder.model.Node;
import com.sashank.map_shortest_path_finder.repository.NodeRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * "Snap to nearest node" — bridges the gap between an arbitrary map click
 * (any lat/lng) and the road graph (which only has nodes at road points).
 *
 * The actual nearest-node computation is a PostGIS KNN query in NodeRepository;
 * this service adds the region-bounds validation on top.
 */
@Service
public class SnapService {

    @Autowired private NodeRepository nodeRepository;
    @Autowired private RegionConfig regionConfig;

    /**
     * Returns the graph node closest to (lat, lng).
     *
     * @throws IllegalArgumentException if the point is outside the supported region
     * @throws IllegalStateException    if the graph has no nodes (import not run)
     */
    public Node snapToNearest(double lat, double lng) {
        if (!regionConfig.getBbox().contains(lat, lng)) {
            throw new IllegalArgumentException(
                "Coordinates (%.5f, %.5f) are outside the supported region '%s'."
                .formatted(lat, lng, regionConfig.getName()));
        }

        return nodeRepository.findNearestTo(lat, lng)
            .orElseThrow(() -> new IllegalStateException(
                "No graph nodes found. Run the import first: "
                + "./mvnw spring-boot:run -Dspring-boot.run.profiles=import"));
    }
}
