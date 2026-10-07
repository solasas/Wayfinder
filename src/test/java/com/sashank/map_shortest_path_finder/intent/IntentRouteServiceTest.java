package com.sashank.map_shortest_path_finder.intent;

import com.sashank.map_shortest_path_finder.config.RegionConfig;
import com.sashank.map_shortest_path_finder.dto.IntentRouteRequest;
import com.sashank.map_shortest_path_finder.dto.IntentRouteResponse;
import com.sashank.map_shortest_path_finder.dto.LatLng;
import com.sashank.map_shortest_path_finder.dto.RouteIntentRequest;
import com.sashank.map_shortest_path_finder.dto.RouteIntentResponse;
import com.sashank.map_shortest_path_finder.exception.ClarificationNeededException;
import com.sashank.map_shortest_path_finder.exception.ConstraintsNotSatisfiableException;
import com.sashank.map_shortest_path_finder.exception.RouteNotFoundException;
import com.sashank.map_shortest_path_finder.model.EdgeAttrs;
import com.sashank.map_shortest_path_finder.model.Node;
import com.sashank.map_shortest_path_finder.model.RoadClass;
import com.sashank.map_shortest_path_finder.service.DijkstraService;
import com.sashank.map_shortest_path_finder.service.GraphService;
import com.sashank.map_shortest_path_finder.service.GraphService.Neighbor;
import com.sashank.map_shortest_path_finder.service.SnapService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class IntentRouteServiceTest {

    @Mock private IntentParser parser;
    @Mock private PlaceResolver resolver;
    @Mock private SnapService snapService;
    @Mock private GraphService graphService;

    private IntentRouteService service;
    private final LatLng start = new LatLng(16.98, 81.78);
    private final LatLng destLoc = new LatLng(16.99, 81.79);

    private static final EdgeAttrs VERIFIED = new EdgeAttrs(RoadClass.SECONDARY, false, null, true);
    private static final EdgeAttrs UNTAGGED = new EdgeAttrs(RoadClass.SECONDARY, null, null, null);

    @BeforeEach
    void setUp() {
        RegionConfig region = new RegionConfig();
        region.setName("Rajahmundry");
        service = new IntentRouteService(new IntentConfig(), parser, resolver, snapService, graphService,
            new PreferenceRoutingService(new DijkstraService(), new IntentConfig()), region);
    }

    private static RouteIntent intent(Constraints c, Preferences p, List<String> unsupported) {
        return new RouteIntent(null, "railway station", Objective.FASTEST, c, p, unsupported);
    }

    private void stubGraph(EdgeAttrs attrs) {
        Map<Long, List<Neighbor>> adj = new HashMap<>();
        adj.put(1L, List.of(new Neighbor(2, 1500, 120, attrs)));
        adj.put(2L, List.of());
        stubGraph(adj);
    }

    private void stubGraph(Map<Long, List<Neighbor>> adj) {
        Node a = Node.builder().id(1L).osmId(10L).lat(16.98).lng(81.78).build();
        Node b = Node.builder().id(2L).osmId(20L).lat(16.99).lng(81.79).build();
        when(graphService.isEmpty()).thenReturn(false);
        when(graphService.getAdjacency()).thenReturn(adj);
        lenient().when(graphService.getNodeIndex()).thenReturn(Map.of(1L, a, 2L, b));
        lenient().when(graphService.getCoverage()).thenReturn(new GraphService.AttributeCoverage(2, 1.0, 0.002, 0.1, 0.05));
        when(snapService.snapToNearest(start.lat(), start.lng())).thenReturn(a);
        when(snapService.snapToNearest(destLoc.lat(), destLoc.lng())).thenReturn(b);
        lenient().when(resolver.resolve("railway station"))
            .thenReturn(new PlaceResolver.ResolvedPlace("Rajahmundry Railway Station, Andhra Pradesh, India", destLoc));
    }

    @Test
    void happyPath_returnsRouteExplanationAndEchoesIntentAndPolicy() {
        stubGraph(VERIFIED);
        when(parser.parse("q")).thenReturn(intent(new Constraints(true, false, false), Preferences.NONE, List.of("avoid traffic")));

        IntentRouteResponse r = service.route(new IntentRouteRequest("q", start));

        assertEquals(Objective.FASTEST, r.intent().objective());
        assertEquals(UnknownDataPolicy.STRICT, r.unknownDataPolicy());
        assertEquals(2, r.route().path().size());
        assertEquals(1500, r.route().distanceMeters(), 1e-9);
        assertEquals(120, r.route().estimatedTimeSecs());
        assertTrue(r.explanation().startsWith("Fastest route to Rajahmundry Railway Station"));
        assertTrue(r.explanation().contains("tagged non-toll"));
        assertEquals(List.of("Not supported, so ignored: avoid traffic"), r.warnings());
        assertEquals("Your location", r.origin().name());
        assertNull(r.plainRoute()); // no soft preferences → no comparison
    }

    @Test
    void strictPolicy_withUntaggedRoad_failsWithTheDocumentedDecision() {
        stubGraph(UNTAGGED);
        when(parser.parse("q")).thenReturn(intent(new Constraints(true, false, true), Preferences.NONE, List.of()));

        var ex = assertThrows(ConstraintsNotSatisfiableException.class,
            () -> service.route(new IntentRouteRequest("q", start)));

        assertTrue(ex.getMessage().contains("ALLOW_UNKNOWN"));
        assertTrue(ex.getMessage().contains("avoidTolls") && ex.getMessage().contains("avoidUnpaved"));
        assertTrue(ex.getMessage().contains("STRICT"));
    }

    @Test
    void emptyAttributeCoverage_tellsTheUserToReimport() {
        stubGraph(EdgeAttrs.UNKNOWN);
        when(graphService.getCoverage()).thenReturn(new GraphService.AttributeCoverage(1, 0, 0, 0, 0));
        when(parser.parse("q")).thenReturn(intent(new Constraints(false, true, false), Preferences.NONE, List.of()));

        var ex = assertThrows(ConstraintsNotSatisfiableException.class,
            () -> service.route(new IntentRouteRequest("q", start)));
        assertTrue(ex.getMessage().contains("--force-reimport"));
    }

    @Test
    void allowUnknown_succeeds_andWarnsHowMuchIsUnverified() {
        stubGraph(UNTAGGED);
        when(parser.parse("q")).thenReturn(intent(new Constraints(true, false, false), Preferences.NONE, List.of()));

        IntentRouteResponse r = service.route(new IntentRouteRequest("q", start, "allow_unknown"));

        assertEquals(UnknownDataPolicy.ALLOW_UNKNOWN, r.unknownDataPolicy());
        assertEquals(1500, r.details().tollUnverifiedMeters(), 1e-9);
        assertTrue(r.explanation().contains("not verified"));
        assertTrue(r.warnings().stream().anyMatch(w -> w.contains("partly verified")));
    }

    @Test
    void knownViolationWithNoAlternative_isNotRelaxed_evenWhenUnknownIsAllowed() {
        stubGraph(new EdgeAttrs(RoadClass.SECONDARY, true, null, true));
        when(parser.parse("q")).thenReturn(intent(new Constraints(true, false, false), Preferences.NONE, List.of()));

        var ex = assertThrows(ConstraintsNotSatisfiableException.class,
            () -> service.route(new IntentRouteRequest("q", start, "ALLOW_UNKNOWN")));
        assertTrue(ex.getMessage().contains("never relaxed"));
    }

    @Test
    void softPreferences_reportThePlainRouteForComparison_andNeverClaimSafety() {
        stubGraph(new EdgeAttrs(RoadClass.SECONDARY, false, true, true));
        when(parser.parse("q")).thenReturn(intent(Constraints.NONE, new Preferences(true, false), List.of()));

        IntentRouteResponse r = service.route(new IntentRouteRequest("q", start));

        assertNotNull(r.plainRoute());
        assertEquals(1500, r.plainRoute().distanceMeters(), 1e-9);
        assertTrue(r.explanation().contains("OpenStreetMap tags"));
        assertFalse(r.explanation().toLowerCase().contains("safe"));
        assertTrue(r.warnings().stream().noneMatch(w -> w.toLowerCase().contains("safe")));
    }

    @Test
    void invalidPolicy_isRejectedBeforeCallingTheModel() {
        when(graphService.isEmpty()).thenReturn(false);
        assertThrows(IllegalArgumentException.class, () -> service.route(new IntentRouteRequest("q", start, "YOLO")));
        verifyNoInteractions(parser);
    }

    @Test
    void namedOrigin_usedWhenNoStartCoordinates() {
        stubGraph(VERIFIED);
        when(parser.parse("q")).thenReturn(new RouteIntent("old bus stand", "railway station", Objective.FASTEST, null, null, null));
        when(resolver.resolve("old bus stand")).thenReturn(new PlaceResolver.ResolvedPlace("Old Bus Stand", start));

        assertEquals("Old Bus Stand", service.route(new IntentRouteRequest("q", null)).origin().name());
    }

    @Test
    void noStartAnywhere_isBadRequest() {
        when(graphService.isEmpty()).thenReturn(false);
        when(parser.parse("q")).thenReturn(intent(Constraints.NONE, Preferences.NONE, List.of()));
        assertThrows(IllegalArgumentException.class, () -> service.route(new IntentRouteRequest("q", null)));
        verify(resolver, never()).resolve(any());
    }

    @Test
    void blankOrOversizedQuery_rejectedBeforeCallingTheModel() {
        when(graphService.isEmpty()).thenReturn(false);
        assertThrows(IllegalArgumentException.class, () -> service.route(new IntentRouteRequest("  ", start)));
        assertThrows(IllegalArgumentException.class, () -> service.route(new IntentRouteRequest("x".repeat(501), start)));
        verifyNoInteractions(parser);
    }

    @Test
    void emptyGraph_isServiceUnavailable_beforeCallingTheModel() {
        when(graphService.isEmpty()).thenReturn(true);
        assertThrows(IllegalStateException.class, () -> service.route(new IntentRouteRequest("q", start)));
        verifyNoInteractions(parser);
    }

    @Test
    void disconnectedPoints_throwPlainRouteNotFound_notAConstraintError() {
        stubGraph(Map.of(1L, List.of(), 2L, List.of()));
        when(parser.parse("q")).thenReturn(intent(new Constraints(true, false, false), Preferences.NONE, List.of()));
        var ex = assertThrows(RouteNotFoundException.class, () -> service.route(new IntentRouteRequest("q", start)));
        assertFalse(ex instanceof ConstraintsNotSatisfiableException);
    }

    // ── routeBetween: POST /api/routes/intent ────────────────────────────────

    private RouteIntentRequest coordRequest(String instruction) {
        return new RouteIntentRequest(start, destLoc, instruction);
    }

    private static RouteIntent noPlaces(Constraints c, Preferences p) {
        return new RouteIntent(null, null, Objective.FASTEST, c, p, List.of());
    }

    @Test
    void routeBetween_returnsEngineValues_andEchoesWhatWasApplied() {
        stubGraph(VERIFIED);
        when(parser.parse("fastest without tolls", false)).thenReturn(noPlaces(new Constraints(true, false, false), Preferences.NONE));

        RouteIntentResponse r = service.routeBetween(coordRequest("fastest without tolls"));

        assertEquals(2, r.path().size());
        assertEquals(new LatLng(16.98, 81.78), r.path().get(0));   // the snapped start node's own coordinates
        assertEquals(new LatLng(16.99, 81.79), r.path().get(1));
        assertEquals(1500, r.distanceMeters(), 1e-9);               // the edge weights from the graph
        assertEquals(120, r.estimatedTimeSecs());
        assertEquals(Objective.FASTEST, r.objective());
        assertEquals(new Constraints(true, false, false), r.constraints());
        assertEquals(Preferences.NONE, r.preferences());
        assertEquals(UnknownDataPolicy.STRICT, r.unknownDataPolicy());
        assertEquals(new LatLng(16.98, 81.78), r.snappedStart());
        assertTrue(r.explanation().startsWith("Fastest route:"), r.explanation());
        assertNull(r.plainRoute());
    }

    @Test
    void routeBetween_validatesRegionBeforeSpendingAnLlmCall() {
        when(graphService.isEmpty()).thenReturn(false);
        when(snapService.snapToNearest(start.lat(), start.lng()))
            .thenThrow(new IllegalArgumentException("Coordinates (16.98000, 81.78000) are outside the supported region 'Rajahmundry'."));

        var ex = assertThrows(IllegalArgumentException.class, () -> service.routeBetween(coordRequest("fastest")));

        assertTrue(ex.getMessage().contains("outside the supported region"));
        verifyNoInteractions(parser);
    }

    @Test
    void routeBetween_snapsBothPointsBeforeParsing() {
        stubGraph(VERIFIED);
        when(parser.parse("x", false)).thenReturn(noPlaces(Constraints.NONE, Preferences.NONE));
        service.routeBetween(coordRequest("x"));
        var order = inOrder(snapService, parser);
        order.verify(snapService).snapToNearest(start.lat(), start.lng());
        order.verify(snapService).snapToNearest(destLoc.lat(), destLoc.lng());
        order.verify(parser).parse("x", false);
    }

    @Test
    void routeBetween_rejectsMissingOrInvalidInput_withoutCallingTheModel() {
        when(graphService.isEmpty()).thenReturn(false);
        assertThrows(IllegalArgumentException.class, () -> service.routeBetween(null));
        assertThrows(IllegalArgumentException.class, () -> service.routeBetween(new RouteIntentRequest(null, destLoc, "x")));
        assertThrows(IllegalArgumentException.class, () -> service.routeBetween(new RouteIntentRequest(start, null, "x")));
        assertThrows(IllegalArgumentException.class, () -> service.routeBetween(coordRequest("   ")));
        assertThrows(IllegalArgumentException.class, () -> service.routeBetween(coordRequest(null)));
        assertThrows(IllegalArgumentException.class, () -> service.routeBetween(coordRequest("x".repeat(501))));
        assertThrows(IllegalArgumentException.class, () -> service.routeBetween(new RouteIntentRequest(start, destLoc, "x", "YOLO")));
        verifyNoInteractions(parser, snapService);
    }

    @Test
    void routeBetween_reportsDataLimitations_fromTheActualMapCoverage() {
        stubGraph(VERIFIED);   // coverage stub: toll 0.2%, surface 10%, lighting 5%, road class 100%
        when(parser.parse("x", false)).thenReturn(noPlaces(new Constraints(true, false, true), new Preferences(true, false)));

        RouteIntentResponse r = service.routeBetween(coordRequest("x"));

        assertTrue(r.dataLimitations().stream().anyMatch(l -> l.contains("Toll status is tagged on under 1%")), r.dataLimitations().toString());
        assertTrue(r.dataLimitations().stream().anyMatch(l -> l.contains("Surface is tagged on 10%")));
        assertTrue(r.dataLimitations().stream().anyMatch(l -> l.contains("Lighting is tagged on 5%")));
        assertTrue(r.dataLimitations().stream().anyMatch(l -> l.contains("STRICT") && l.contains("ALLOW_UNKNOWN")));
        assertTrue(r.dataLimitations().stream().noneMatch(l -> l.contains("Road class"))); // 100% known → nothing to report
    }

    @Test
    void routeBetween_saysSoWhenAPlaceNameInTheTextWasIgnored() {
        stubGraph(VERIFIED);
        when(parser.parse("x", false)).thenReturn(new RouteIntent("old bus stand", "airport", Objective.FASTEST, null, null, null));
        RouteIntentResponse r = service.routeBetween(coordRequest("x"));
        assertTrue(r.notices().stream().anyMatch(n -> n.contains("'airport'") && n.contains("end coordinates you provided")));
        assertTrue(r.notices().stream().anyMatch(n -> n.contains("'old bus stand'") && n.contains("start coordinates")));
        verifyNoInteractions(resolver); // no geocoding on this endpoint
    }

    @Test
    void routeBetween_sameSnappedPoint_isAZeroLengthRoute_withANotice() {
        Node only = Node.builder().id(1L).osmId(10L).lat(16.98).lng(81.78).build();
        when(graphService.isEmpty()).thenReturn(false);
        when(graphService.getAdjacency()).thenReturn(Map.of(1L, List.of()));
        when(graphService.getNodeIndex()).thenReturn(Map.of(1L, only));
        when(snapService.snapToNearest(anyDouble(), anyDouble())).thenReturn(only);
        when(parser.parse("x", false)).thenReturn(noPlaces(Constraints.NONE, Preferences.NONE));

        RouteIntentResponse r = service.routeBetween(coordRequest("x"));

        assertEquals(0, r.distanceMeters());
        assertEquals(1, r.path().size());
        assertTrue(r.notices().stream().anyMatch(n -> n.contains("same road point")));
    }

    @Test
    void routeBetween_preferenceThatWouldCostTooMuch_isReportedAsNotApplied() {
        // unlit direct 1000 m vs lit detour 1400 m: the search prefers the lit road, but 1.4x exceeds the 1.3x margin
        Node a = Node.builder().id(1L).osmId(10L).lat(16.98).lng(81.78).build();
        Node m = Node.builder().id(2L).osmId(20L).lat(16.985).lng(81.785).build();
        Node b = Node.builder().id(3L).osmId(30L).lat(16.99).lng(81.79).build();
        EdgeAttrs unlit = new EdgeAttrs(RoadClass.SECONDARY, null, false, null);
        EdgeAttrs lit = new EdgeAttrs(RoadClass.SECONDARY, null, true, null);
        Map<Long, List<Neighbor>> adj = new HashMap<>();
        adj.put(1L, List.of(new Neighbor(3, 1000, 100, unlit), new Neighbor(2, 700, 70, lit)));
        adj.put(2L, List.of(new Neighbor(3, 700, 70, lit)));
        adj.put(3L, List.of());
        when(graphService.isEmpty()).thenReturn(false);
        when(graphService.getAdjacency()).thenReturn(adj);
        when(graphService.getNodeIndex()).thenReturn(Map.of(1L, a, 2L, m, 3L, b));
        lenient().when(graphService.getCoverage()).thenReturn(new GraphService.AttributeCoverage(3, 1, 1, 1, 1));
        when(snapService.snapToNearest(start.lat(), start.lng())).thenReturn(a);
        when(snapService.snapToNearest(destLoc.lat(), destLoc.lng())).thenReturn(b);
        when(parser.parse("x", false)).thenReturn(noPlaces(Constraints.NONE, new Preferences(true, false)));

        RouteIntentResponse r = service.routeBetween(coordRequest("x"));

        assertEquals(new Preferences(true, false), r.preferences());             // requested
        assertEquals(Preferences.NONE, r.preferencesApplied());                    // but not applied
        assertEquals(1000, r.distanceMeters(), 1e-9);                              // the plain route
        assertTrue(r.notices().stream().anyMatch(n -> n.contains("allowed margin")));
        assertNotNull(r.plainRoute());
    }

    @Test
    void routeBetween_appliedPreference_isReportedAsApplied_withTheCostVisible() {
        Node a = Node.builder().id(1L).osmId(10L).lat(16.98).lng(81.78).build();
        Node m = Node.builder().id(2L).osmId(20L).lat(16.985).lng(81.785).build();
        Node b = Node.builder().id(3L).osmId(30L).lat(16.99).lng(81.79).build();
        EdgeAttrs unlit = new EdgeAttrs(RoadClass.SECONDARY, null, false, null);
        EdgeAttrs lit = new EdgeAttrs(RoadClass.SECONDARY, null, true, null);
        Map<Long, List<Neighbor>> adj = new HashMap<>();
        adj.put(1L, List.of(new Neighbor(3, 1000, 100, unlit), new Neighbor(2, 600, 60, lit)));
        adj.put(2L, List.of(new Neighbor(3, 600, 60, lit)));   // lit detour 1200 m = 1.2x, inside the margin
        adj.put(3L, List.of());
        when(graphService.isEmpty()).thenReturn(false);
        when(graphService.getAdjacency()).thenReturn(adj);
        when(graphService.getNodeIndex()).thenReturn(Map.of(1L, a, 2L, m, 3L, b));
        lenient().when(graphService.getCoverage()).thenReturn(new GraphService.AttributeCoverage(3, 1, 1, 1, 1));
        when(snapService.snapToNearest(start.lat(), start.lng())).thenReturn(a);
        when(snapService.snapToNearest(destLoc.lat(), destLoc.lng())).thenReturn(b);
        when(parser.parse("x", false)).thenReturn(noPlaces(Constraints.NONE, new Preferences(true, false)));

        RouteIntentResponse r = service.routeBetween(coordRequest("x"));

        assertEquals(new Preferences(true, false), r.preferencesApplied());
        assertEquals(1200, r.distanceMeters(), 1e-9);
        assertEquals(1000, r.plainRoute().distanceMeters(), 1e-9);
        assertTrue(r.explanation().contains("+20.0% distance"), r.explanation());
    }

    @Test
    void routeBetween_failuresReuseTheDocumentedExceptions() {
        stubGraph(UNTAGGED);
        when(parser.parse("x", false)).thenReturn(noPlaces(new Constraints(true, false, false), Preferences.NONE));
        var ex = assertThrows(ConstraintsNotSatisfiableException.class, () -> service.routeBetween(coordRequest("x")));
        assertTrue(ex.getMessage().contains("ALLOW_UNKNOWN"));

        when(parser.parse("y", false)).thenThrow(new ClarificationNeededException("Fastest or shortest?"));
        assertThrows(ClarificationNeededException.class, () -> service.routeBetween(coordRequest("y")));
    }

    @Test
    void routeBetween_allowUnknownPolicy_isHonoured() {
        stubGraph(UNTAGGED);
        when(parser.parse("x", false)).thenReturn(noPlaces(new Constraints(true, false, false), Preferences.NONE));
        RouteIntentResponse r = service.routeBetween(new RouteIntentRequest(start, destLoc, "x", "allow_unknown"));
        assertEquals(UnknownDataPolicy.ALLOW_UNKNOWN, r.unknownDataPolicy());
        assertTrue(r.dataLimitations().stream().anyMatch(l -> l.contains("partly verified")));
    }

    @Test
    void nameBasedEndpoint_stillRequiresADestination() {
        when(graphService.isEmpty()).thenReturn(false);
        when(parser.parse("q")).thenReturn(noPlaces(Constraints.NONE, Preferences.NONE));
        assertThrows(ClarificationNeededException.class, () -> service.route(new IntentRouteRequest("q", start)));
    }

    // ── explicit objective (UI selector) ─────────────────────────────────────

    @Test
    void explicitObjective_overridesWhatTheInstructionImplied() {
        stubGraph(VERIFIED);
        when(parser.parse("x", false)).thenReturn(noPlaces(Constraints.NONE, Preferences.NONE)); // parser says FASTEST

        RouteIntentResponse r = service.routeBetween(new RouteIntentRequest(start, destLoc, "x", null, "shortest"));

        assertEquals(Objective.SHORTEST, r.objective());
        assertTrue(r.explanation().startsWith("Shortest route"), r.explanation());
    }

    @Test
    void autoOrAbsentObjective_leavesTheInstructionInCharge() {
        stubGraph(VERIFIED);
        when(parser.parse("x", false)).thenReturn(new RouteIntent(null, null, Objective.SHORTEST, null, null, null));
        for (String auto : new String[]{null, "", "  ", "AUTO", "auto"}) {
            assertEquals(Objective.SHORTEST, service.routeBetween(new RouteIntentRequest(start, destLoc, "x", null, auto)).objective(), auto);
        }
    }

    @Test
    void invalidOrUnavailableObjective_is400_beforeAnyLlmCallOrSnapping() {
        when(graphService.isEmpty()).thenReturn(false);
        var eco = assertThrows(IllegalArgumentException.class,
            () -> service.routeBetween(new RouteIntentRequest(start, destLoc, "x", null, "ECO_FRIENDLY")));
        assertTrue(eco.getMessage().contains("emissions model"));
        assertThrows(IllegalArgumentException.class,
            () -> service.routeBetween(new RouteIntentRequest(start, destLoc, "x", null, "SCENIC")));
        verifyNoInteractions(parser, snapService);
    }

    @Test
    void lightingPreference_withNoLightingDataOnTheRoute_isRequestedButNotReportedAsApplied() {
        stubGraph(VERIFIED);   // edge has lit = null → no lighting evidence
        when(parser.parse("x", false)).thenReturn(noPlaces(Constraints.NONE, new Preferences(true, false)));
        RouteIntentResponse r = service.routeBetween(coordRequest("x"));
        assertEquals(new Preferences(true, false), r.preferences());
        assertEquals(Preferences.NONE, r.preferencesApplied());
        assertTrue(r.dataLimitations().stream().anyMatch(l -> l.contains("had no effect")));
    }
}
