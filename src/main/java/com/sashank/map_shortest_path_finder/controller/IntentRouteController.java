package com.sashank.map_shortest_path_finder.controller;

import com.sashank.map_shortest_path_finder.dto.IntentRouteRequest;
import com.sashank.map_shortest_path_finder.dto.IntentRouteResponse;
import com.sashank.map_shortest_path_finder.dto.RouteIntentRequest;
import com.sashank.map_shortest_path_finder.dto.RouteIntentResponse;
import com.sashank.map_shortest_path_finder.intent.IntentRouteService;
import org.springframework.web.bind.annotation.*;

/**
 * POST /api/intent-route
 *
 * "Fastest route to the railway station, avoid highways, prefer well-lit roads" →
 * an LLM extracts validated routing preferences, the destination name is geocoded
 * inside the supported region, and the existing Dijkstra engine computes the route.
 * See IntentRouteService for the full pipeline.
 *
 * POST /api/routes/intent
 *
 * Start and end given as coordinates; the free-text instruction only says HOW to travel
 * ("fastest route without tolls"). No geocoding involved.
 */
@RestController
@RequestMapping("/api")
public class IntentRouteController {

    private final IntentRouteService intentRouteService;

    public IntentRouteController(IntentRouteService intentRouteService) {
        this.intentRouteService = intentRouteService;
    }

    @PostMapping("/intent-route")
    public IntentRouteResponse intentRoute(@RequestBody IntentRouteRequest req) {
        return intentRouteService.route(req);
    }

    @PostMapping("/routes/intent")
    public RouteIntentResponse routeIntent(@RequestBody RouteIntentRequest req) {
        return intentRouteService.routeBetween(req);
    }
}
