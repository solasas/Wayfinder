package com.sashank.map_shortest_path_finder.controller;

import com.sashank.map_shortest_path_finder.config.RegionConfig;
import com.sashank.map_shortest_path_finder.dto.LatLng;
import com.sashank.map_shortest_path_finder.dto.RegionResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

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
        List<RegionResponse.AreaDto> areas = regionConfig.getAreas().stream()
            .map(a -> new RegionResponse.AreaDto(a.getName(),
                new LatLng(a.getBbox().getCenterLat(), a.getBbox().getCenterLng()), toDto(a.getBbox())))
            .toList();
        return new RegionResponse(
            regionConfig.getName(),
            new LatLng(bbox.getCenterLat(), bbox.getCenterLng()),
            toDto(bbox),
            areas,
            regionConfig.getMaxSnapMeters()
        );
    }

    private static RegionResponse.BboxDto toDto(RegionConfig.Bbox b) {
        return new RegionResponse.BboxDto(b.getSouth(), b.getNorth(), b.getWest(), b.getEast());
    }
}
