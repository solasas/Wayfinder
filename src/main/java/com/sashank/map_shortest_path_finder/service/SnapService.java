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
 * this service adds the region-bounds and maximum-snap-distance validation on top.
 */
@Service
public class SnapService {

    @Autowired private NodeRepository nodeRepository;
    @Autowired private RegionConfig regionConfig;

    /**
     * Returns the graph node closest to (lat, lng).
     *
     * @throws IllegalArgumentException if the point is outside the supported region, or farther from the nearest
     *                                  imported road than {@code region.max-snap-meters} (open sea, empty countryside)
     * @throws IllegalStateException    if the graph has no nodes (import not run)
     */
    public Node snapToNearest(double lat, double lng) {
        if (!regionConfig.getBbox().contains(lat, lng)) {
            throw new IllegalArgumentException(
                "Coordinates (%.5f, %.5f) are outside the supported region '%s'."
                .formatted(lat, lng, regionConfig.getName()));
        }

        Node nearest = nodeRepository.findNearestTo(lat, lng)
            .orElseThrow(() -> new IllegalStateException(
                "No graph nodes found. Run the import first: "
                + "./mvnw spring-boot:run -Dspring-boot.run.profiles=import"));

        // A bbox over a whole corridor includes sea and empty land: inside the box does not mean near a road.
        double limit = regionConfig.getMaxSnapMeters();
        if (limit > 0) {
            double away = haversineMeters(lat, lng, nearest.getLat(), nearest.getLng());
            if (away > limit) {
                throw new IllegalArgumentException((
                    "No mapped road within %.1f km of (%.5f, %.5f) — the nearest is %.1f km away. "
                    + "Choose a point closer to a road inside the supported region '%s'.")
                    .formatted(limit / 1000.0, lat, lng, away / 1000.0, regionConfig.getName()));
            }
        }
        return nearest;
    }

    static double haversineMeters(double lat1, double lng1, double lat2, double lng2) {
        double dLat = Math.toRadians(lat2 - lat1), dLng = Math.toRadians(lng2 - lng1);
        double h = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                 + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        return 6_371_000.0 * 2 * Math.atan2(Math.sqrt(h), Math.sqrt(1 - h));
    }
}
