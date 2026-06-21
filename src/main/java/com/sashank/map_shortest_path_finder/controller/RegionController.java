package com.sashank.map_shortest_path_finder.controller;

import com.sashank.map_shortest_path_finder.config.RegionConfig;
import com.sashank.map_shortest_path_finder.dto.LatLng;
import com.sashank.map_shortest_path_finder.dto.RegionResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * GET /api/region
 *
 * The frontend calls this on load to know where to centre the map and what
 * bounding box to display as the "supported area" overlay.
 */
@RestController
@RequestMapping("/api")
public class RegionController {

    @Autowired private RegionConfig regionConfig;

    @GetMapping("/region")
    public RegionResponse getRegion() {
        RegionConfig.Bbox bbox = regionConfig.getBbox();
        return new RegionResponse(
            regionConfig.getName(),
            new LatLng(bbox.getCenterLat(), bbox.getCenterLng()),
            new RegionResponse.BboxDto(bbox.getSouth(), bbox.getNorth(),
                                       bbox.getWest(), bbox.getEast())
        );
    }
}
