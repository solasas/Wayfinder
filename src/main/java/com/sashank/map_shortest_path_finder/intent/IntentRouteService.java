package com.sashank.map_shortest_path_finder.intent;

import com.sashank.map_shortest_path_finder.config.RegionConfig;
import com.sashank.map_shortest_path_finder.dto.IntentRouteRequest;
import com.sashank.map_shortest_path_finder.exception.ConstraintsNotSatisfiableException;
import com.sashank.map_shortest_path_finder.dto.IntentRouteResponse;
import com.sashank.map_shortest_path_finder.dto.RouteIntentRequest;
import com.sashank.map_shortest_path_finder.dto.RouteIntentResponse;
import com.sashank.map_shortest_path_finder.exception.ClarificationNeededException;
import com.sashank.map_shortest_path_finder.dto.LatLng;
import com.sashank.map_shortest_path_finder.dto.PathResponse;
import com.sashank.map_shortest_path_finder.exception.RouteNotFoundException;
import com.sashank.map_shortest_path_finder.model.Node;
import com.sashank.map_shortest_path_finder.service.DijkstraService;
import com.sashank.map_shortest_path_finder.service.GraphService;
import com.sashank.map_shortest_path_finder.service.SnapService;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Orchestrates natural-language routing. Two entry points share one planning step ({@link #plan}):
 *
 *   route(...)        POST /api/intent-route — destination named in the text:
 *       text → IntentParser → PlaceResolver (name → coordinates in region) → SnapService → plan
 *   routeBetween(...) POST /api/routes/intent — start and end given as coordinates:
 *       SnapService (region check first, so bad input costs no LLM call) → IntentParser → plan
 *
 * plan = PreferenceRoutingService (existing Dijkstra) + explanation. The LLM only interprets intent;
 * every coordinate and every route number comes from the request, the geocoder or the graph engine.
 */
@Service
public class IntentRouteService {

    private final IntentConfig config;
    private final IntentParser intentParser;
    private final PlaceResolver placeResolver;
    private final SnapService snapService;
    private final GraphService graphService;
    private final PreferenceRoutingService routingService;
    private final RegionConfig regionConfig;

    public IntentRouteService(IntentConfig config, IntentParser intentParser, PlaceResolver placeResolver,
                              SnapService snapService, GraphService graphService,
                              PreferenceRoutingService routingService, RegionConfig regionConfig) {
        this.config = config;
        this.intentParser = intentParser;
        this.placeResolver = placeResolver;
        this.snapService = snapService;
        this.graphService = graphService;
        this.routingService = routingService;
        this.regionConfig = regionConfig;
    }

    public IntentRouteResponse route(IntentRouteRequest req) {
        if (graphService.isEmpty()) {
            throw new IllegalStateException(
                "Graph not loaded. Run the import first: "
                + "./mvnw spring-boot:run -Dspring-boot.run.profiles=import");
        }
        if (req == null || req.query() == null || req.query().isBlank()) {
            throw new IllegalArgumentException("query must not be empty");
        }
        String query = req.query().strip();
        if (query.length() > config.getMaxQueryLength()) {
            throw new IllegalArgumentException("query is too long (max " + config.getMaxQueryLength() + " characters)");
        }

        UnknownDataPolicy policy = UnknownDataPolicy.parse(req.unknownDataPolicy()); // 400 if invalid, before spending an LLM call
        RouteIntent intent = intentParser.parse(query);
        if (intent.destination() == null) {
            throw new ClarificationNeededException("Where would you like to go?");
        }

        IntentRouteResponse.Place origin = resolveOrigin(req.start(), intent);
        PlaceResolver.ResolvedPlace dest = placeResolver.resolve(intent.destination());

        Node startNode = snapService.snapToNearest(origin.location().lat(), origin.location().lng());
        Node endNode = snapService.snapToNearest(dest.location().lat(), dest.location().lng());

        Planned planned = plan(intent, startNode, endNode, dest.displayName(), policy);

        boolean prefsActive = intent.preferences().preferWellLit() || intent.preferences().minimizeTurns();
        return new IntentRouteResponse(intent, policy, origin,
            new IntentRouteResponse.Place(dest.displayName(), dest.location()),
            planned.route(), planned.outcome().stats(), prefsActive ? planned.outcome().baseline() : null,
            planned.explanation().text(), planned.explanation().combined());
    }

    /**
     * POST /api/routes/intent: route between two given coordinates, steered by a free-text instruction.
     *
     * @throws IllegalArgumentException     missing/invalid fields, coordinates outside the region (400)
     * @throws ClarificationNeededException contradictory instruction (422)
     */
    public RouteIntentResponse routeBetween(RouteIntentRequest req) {
        if (graphService.isEmpty()) {
            throw new IllegalStateException(
                "Graph not loaded. Run the import first: "
                + "./mvnw spring-boot:run -Dspring-boot.run.profiles=import");
        }
        if (req == null || req.start() == null || req.end() == null) {
            throw new IllegalArgumentException("start and end coordinates are required");
        }
        if (req.instruction() == null || req.instruction().isBlank()) {
            throw new IllegalArgumentException("instruction must not be empty");
        }
        String instruction = req.instruction().strip();
        if (instruction.length() > config.getMaxQueryLength()) {
            throw new IllegalArgumentException("instruction is too long (max " + config.getMaxQueryLength() + " characters)");
        }
        UnknownDataPolicy policy = UnknownDataPolicy.parse(req.unknownDataPolicy());
        Objective chosenObjective = parseObjectiveChoice(req.objective()); // 400 if invalid, before any LLM call

        // Region validation + snapping first (SnapService throws IllegalArgumentException for points outside
        // RegionConfig's bounding box → 400), so invalid coordinates never cost an LLM call.
        Node startNode = snapService.snapToNearest(req.start().lat(), req.start().lng());
        Node endNode = snapService.snapToNearest(req.end().lat(), req.end().lng());

        RouteIntent intent = intentParser.parse(instruction, false);
        if (chosenObjective != null) {
            intent = intent.withObjective(chosenObjective); // explicit choice beats what the text implied
        }
        Planned planned = plan(intent, startNode, endNode, null, policy);

        List<String> limitations = new ArrayList<>(planned.explanation().dataLimitations());
        limitations.addAll(coverageLimitations(intent, policy));
        List<String> notices = new ArrayList<>(planned.explanation().notices());
        if (intent.destination() != null) {
            notices.add("The instruction mentions a destination ('" + intent.destination()
                + "'), but the route uses the end coordinates you provided.");
        }
        if (intent.origin() != null) {
            notices.add("The instruction mentions a starting place ('" + intent.origin()
                + "'), but the route uses the start coordinates you provided.");
        }
        if (startNode.getId().equals(endNode.getId())) {
            notices.add("Start and end snap to the same road point, so the route has zero length.");
        }

        PreferenceRoutingService.RouteOutcome outcome = planned.outcome();
        boolean honoured = !outcome.fellBackToBaseline();
        // "Applied" must be evidenced: a lighting preference counts only if the chosen route actually has
        // lighting-tagged segments; with no lighting data on it the preference had nothing to act on.
        boolean lightingEvidence = outcome.stats().litMeters() + outcome.stats().unlitMeters() > 0;
        boolean prefsRequested = intent.preferences().preferWellLit() || intent.preferences().minimizeTurns();
        PathResponse route = planned.route();
        return new RouteIntentResponse(
            route.path(), route.distanceMeters(), route.estimatedTimeSecs(),
            intent.objective(), intent.constraints(), intent.preferences(),
            new Preferences(intent.preferences().preferWellLit() && honoured && lightingEvidence, intent.preferences().minimizeTurns() && honoured && !outcome.turnMinimisationSkipped()),
            policy,
            new LatLng(startNode.getLat(), startNode.getLng()), new LatLng(endNode.getLat(), endNode.getLng()),
            planned.explanation().text(), List.copyOf(limitations), List.copyOf(notices),
            outcome.stats(), prefsRequested ? outcome.baseline() : null);
    }

    /** null for blank / "AUTO" (let the instruction decide); otherwise a supported Objective, else IllegalArgumentException. */
    private static Objective parseObjectiveChoice(String value) {
        if (value == null || value.isBlank() || value.strip().equalsIgnoreCase("AUTO")) return null;
        return Objective.parse(value);
    }

    /** What routing produced, shared by both endpoints. */
    private record Planned(PreferenceRoutingService.RouteOutcome outcome, PathResponse route, RouteExplainer.Explanation explanation) {}

    /** Routes between two snapped nodes under the intent, or throws the documented failure. */
    private Planned plan(RouteIntent intent, Node startNode, Node endNode, String destinationName, UnknownDataPolicy policy) {
        PreferenceRoutingService.RouteAttempt attempt = routingService.route(
            graphService.getAdjacency(), graphService.getNodeIndex(),
            startNode.getId(), endNode.getId(), intent, policy);
        if (attempt.failure() != PreferenceRoutingService.Failure.NONE) {
            throw failureFor(attempt.failure(), intent, startNode.getId(), endNode.getId());
        }
        PreferenceRoutingService.RouteOutcome outcome = attempt.outcome();

        List<LatLng> polyline = outcome.nodeIds().stream()
            .map(id -> {
                Node n = graphService.getNodeIndex().get(id);
                return new LatLng(n.getLat(), n.getLng());
            })
            .toList();
        PathResponse route = new PathResponse(polyline, outcome.distanceMeters(), Math.round(outcome.timeSeconds()));
        return new Planned(outcome, route, RouteExplainer.explain(intent, destinationName, policy, outcome));
    }

    /**
     * How complete the imported map data is for the things this request relies on. Only reported
     * below 95% coverage; stated as fractions of ALL road segments, not of the chosen route.
     */
    private List<String> coverageLimitations(RouteIntent intent, UnknownDataPolicy policy) {
        GraphService.AttributeCoverage cov = graphService.getCoverage();
        Constraints c = intent.constraints();
        List<String> out = new ArrayList<>();
        boolean sparseConstraint = false;
        if (c.avoidTolls() && cov.toll() < 0.95) {
            out.add("Toll status is tagged on " + share(cov.toll()) + " of road segments in the map data.");
            sparseConstraint = true;
        }
        if (c.avoidHighways() && cov.roadClass() < 0.95) {
            out.add("Road class is known for " + share(cov.roadClass()) + " of road segments in the map data.");
            sparseConstraint = true;
        }
        if (c.avoidUnpaved() && cov.surface() < 0.95) {
            out.add("Surface is tagged on " + share(cov.surface()) + " of road segments in the map data.");
            sparseConstraint = true;
        }
        if (sparseConstraint && policy == UnknownDataPolicy.STRICT) {
            out.add("Under the STRICT policy only segments whose tags confirm compliance were used, so a shorter compliant "
                + "route through untagged segments may exist; resend with unknownDataPolicy=ALLOW_UNKNOWN to consider them.");
        }
        if (intent.preferences().preferWellLit() && cov.lighting() < 0.95) {
            out.add("Lighting is tagged on " + share(cov.lighting()) + " of road segments in the map data.");
        }
        return out;
    }

    private static String share(double fraction) {
        return fraction > 0 && fraction < 0.005 ? "under 1%" : String.format(Locale.ROOT, "%.0f%%", 100 * fraction);
    }

    /** Turns a routing failure into the documented 404 explaining what the user can decide. */
    private RouteNotFoundException failureFor(PreferenceRoutingService.Failure failure, RouteIntent intent,
                                              long startId, long endId) {
        if (failure == PreferenceRoutingService.Failure.NO_ROUTE) {
            return new RouteNotFoundException(startId, endId);
        }
        Constraints c = intent.constraints();
        List<String> active = new ArrayList<>();
        if (c.avoidTolls()) active.add("avoidTolls");
        if (c.avoidHighways()) active.add("avoidHighways");
        if (c.avoidUnpaved()) active.add("avoidUnpaved");
        GraphService.AttributeCoverage cov = graphService.getCoverage();

        if (failure == PreferenceRoutingService.Failure.CONSTRAINTS_UNVERIFIABLE) {
            return new ConstraintsNotSatisfiableException(true, String.format(Locale.ROOT,
                "No route can be VERIFIED to satisfy %s: the road data doesn't establish compliance for enough segments "
                + "(share of segments with data: road class %.0f%%, toll %.0f%%, surface %.0f%%). Under the STRICT policy "
                + "a segment is used only if its tags confirm it complies. Resend with unknownDataPolicy=ALLOW_UNKNOWN "
                + "to use segments whose status is unknown (segments known to violate are still excluded and the "
                + "response will report how much of the route is unverified)%s.",
                active, 100 * cov.roadClass(), 100 * cov.toll(), 100 * cov.surface(),
                cov.roadClass() == 0 ? ". Note: the graph has no road attributes at all — re-import it with --force-reimport first" : ""));
        }
        return new ConstraintsNotSatisfiableException(false,
            "No route satisfies " + active + " between these points, even when segments with unknown data are accepted. "
            + "Constraints are never relaxed automatically; remove one to get a route.");
    }

    private IntentRouteResponse.Place resolveOrigin(LatLng start, RouteIntent intent) {
        if (start != null) {
            return new IntentRouteResponse.Place("Your location", start);
        }
        if (intent.origin() != null) {
            PlaceResolver.ResolvedPlace p = placeResolver.resolve(intent.origin());
            return new IntentRouteResponse.Place(p.displayName(), p.location());
        }
        throw new IllegalArgumentException(
            "No starting point: provide 'start' coordinates or name where you're starting from.");
    }
}
