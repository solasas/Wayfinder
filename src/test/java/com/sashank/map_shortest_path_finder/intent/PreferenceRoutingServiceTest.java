package com.sashank.map_shortest_path_finder.intent;

import com.sashank.map_shortest_path_finder.model.EdgeAttrs;
import com.sashank.map_shortest_path_finder.model.Node;
import com.sashank.map_shortest_path_finder.model.RoadClass;
import com.sashank.map_shortest_path_finder.service.DijkstraService;
import com.sashank.map_shortest_path_finder.service.GraphService.Neighbor;
import com.sashank.map_shortest_path_finder.intent.PreferenceRoutingService.Failure;
import com.sashank.map_shortest_path_finder.intent.PreferenceRoutingService.RouteAttempt;
import com.sashank.map_shortest_path_finder.intent.PreferenceRoutingService.RouteOutcome;
import org.junit.jupiter.api.Test;

import java.util.*;

import static com.sashank.map_shortest_path_finder.intent.UnknownDataPolicy.ALLOW_UNKNOWN;
import static com.sashank.map_shortest_path_finder.intent.UnknownDataPolicy.STRICT;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Pure-algorithm tests on hand-built graphs, no Spring/DB — same style as DijkstraServiceTest.
 * Edge attrs are (roadClass, toll, lit, paved); null = untagged/unknown.
 */
class PreferenceRoutingServiceTest {

    private final PreferenceRoutingService service = new PreferenceRoutingService(new DijkstraService(), new IntentConfig());

    // ── helpers ──────────────────────────────────────────────────────────────

    private static final EdgeAttrs KNOWN_OK = new EdgeAttrs(RoadClass.SECONDARY, false, null, true);
    private static final EdgeAttrs TOLL = new EdgeAttrs(RoadClass.SECONDARY, true, null, true);
    private static final EdgeAttrs UNTAGGED = new EdgeAttrs(RoadClass.SECONDARY, null, null, null);

    private static RouteIntent intent(Objective o, Constraints c, Preferences p) {
        return new RouteIntent(null, "x", o, c, p, List.of());
    }
    private static RouteIntent plain() { return intent(Objective.FASTEST, Constraints.NONE, Preferences.NONE); }
    private static RouteIntent tolls() { return intent(Objective.FASTEST, new Constraints(true, false, false), Preferences.NONE); }
    private static RouteIntent highways() { return intent(Objective.FASTEST, new Constraints(false, true, false), Preferences.NONE); }
    private static RouteIntent unpaved() { return intent(Objective.FASTEST, new Constraints(false, false, true), Preferences.NONE); }

    private static Map<Long, List<Neighbor>> graph(Object... edges) { // (from, to, d, t, attrs)*
        Map<Long, List<Neighbor>> g = new HashMap<>();
        for (int i = 0; i < edges.length; i += 5) {
            long from = ((Number) edges[i]).longValue(), to = ((Number) edges[i + 1]).longValue();
            g.computeIfAbsent(from, k -> new ArrayList<>())
             .add(new Neighbor(to, ((Number) edges[i + 2]).doubleValue(), ((Number) edges[i + 3]).doubleValue(), (EdgeAttrs) edges[i + 4]));
            g.computeIfAbsent(to, k -> new ArrayList<>());
        }
        return g;
    }

    private RouteAttempt attempt(Map<Long, List<Neighbor>> g, long s, long t, RouteIntent i, UnknownDataPolicy p) {
        return service.route(g, Map.of(), s, t, i, p);
    }

    private RouteOutcome ok(Map<Long, List<Neighbor>> g, long s, long t, RouteIntent i, UnknownDataPolicy p) {
        RouteAttempt a = attempt(g, s, t, i, p);
        assertEquals(Failure.NONE, a.failure());
        return a.outcome();
    }

    /** 1→4 directly (1000 m, 60 s, `direct` attrs) or 1→2→3→4 (3×600 m, 3×60 s, KNOWN_OK). */
    private Map<Long, List<Neighbor>> directVsDetour(EdgeAttrs direct) {
        return graph(1, 4, 1000, 60, direct,
                     1, 2, 600, 60, KNOWN_OK, 2, 3, 600, 60, KNOWN_OK, 3, 4, 600, 60, KNOWN_OK);
    }

    // ── baseline behaviour ───────────────────────────────────────────────────

    @Test
    void noConstraintsOrPreferences_isPlainDijkstra_withIdenticalBaseline() {
        RouteOutcome o = ok(directVsDetour(TOLL), 1, 4, plain(), STRICT);
        assertEquals(List.of(1L, 4L), o.nodeIds());
        assertEquals(1000, o.distanceMeters(), 1e-9);
        assertEquals(60, o.timeSeconds(), 1e-9);
        assertEquals(o.distanceMeters(), o.baseline().distanceMeters(), 1e-9);
        assertFalse(o.fellBackToBaseline());
    }

    @Test
    void shortestAndFastestCanDiffer() {
        var g = graph(1, 2, 1000, 200, KNOWN_OK, 1, 3, 1500, 100, KNOWN_OK, 3, 2, 0.1, 0.1, KNOWN_OK);
        assertEquals(List.of(1L, 2L), ok(g, 1, 2, intent(Objective.SHORTEST, Constraints.NONE, Preferences.NONE), STRICT).nodeIds());
        assertEquals(List.of(1L, 3L, 2L), ok(g, 1, 2, intent(Objective.FASTEST, Constraints.NONE, Preferences.NONE), STRICT).nodeIds());
    }

    @Test
    void sameSourceAndTarget_isTrivialRoute() {
        RouteOutcome o = ok(directVsDetour(TOLL), 1, 1, plain(), STRICT);
        assertEquals(List.of(1L), o.nodeIds());
        assertEquals(0, o.distanceMeters());
    }

    // ── hard constraints: known violations are excluded ──────────────────────

    @Test
    void avoidTolls_excludesKnownTollEdge_andReportsRealTotals() {
        RouteOutcome o = ok(directVsDetour(TOLL), 1, 4, tolls(), STRICT);
        assertEquals(List.of(1L, 2L, 3L, 4L), o.nodeIds());
        assertEquals(1800, o.distanceMeters(), 1e-9);
        assertEquals(180, o.timeSeconds(), 1e-9);
        assertEquals(0, o.stats().tollUnverifiedMeters());
    }

    @Test
    void avoidHighways_usesExplicitPolicy_motorwayAndTrunkByDefault_primaryNot() {
        EdgeAttrs trunk = new EdgeAttrs(RoadClass.TRUNK, false, null, true);
        EdgeAttrs primary = new EdgeAttrs(RoadClass.PRIMARY, false, null, true);
        assertEquals(List.of(1L, 2L, 3L, 4L), ok(directVsDetour(trunk), 1, 4, highways(), STRICT).nodeIds());
        assertEquals(List.of(1L, 4L), ok(directVsDetour(primary), 1, 4, highways(), STRICT).nodeIds());

        IntentConfig cfg = new IntentConfig();
        cfg.getRouting().setHighwayClasses(List.of(RoadClass.MOTORWAY, RoadClass.TRUNK, RoadClass.PRIMARY));
        var widened = new PreferenceRoutingService(new DijkstraService(), cfg);
        assertEquals(List.of(1L, 2L, 3L, 4L),
            widened.route(directVsDetour(primary), Map.of(), 1, 4, highways(), STRICT).outcome().nodeIds());
    }

    @Test
    void avoidUnpaved_excludesKnownUnpavedEdge() {
        EdgeAttrs dirt = new EdgeAttrs(RoadClass.SECONDARY, false, null, false);
        assertEquals(List.of(1L, 2L, 3L, 4L), ok(directVsDetour(dirt), 1, 4, unpaved(), STRICT).nodeIds());
    }

    // ── unknown metadata: STRICT never guesses; ALLOW_UNKNOWN is explicit and reported ──

    @Test
    void strict_treatsMissingTagsAsNotVerified_andPrefersVerifiedDetour() {
        RouteOutcome o = ok(directVsDetour(UNTAGGED), 1, 4, tolls(), STRICT);
        assertEquals(List.of(1L, 2L, 3L, 4L), o.nodeIds()); // direct edge has toll=null → not usable
    }

    @Test
    void strict_withOnlyUnverifiableRoute_failsAsUnverifiable_notWithAViolatingRoute() {
        var g = graph(1, 2, 500, 50, UNTAGGED);
        assertEquals(Failure.CONSTRAINTS_UNVERIFIABLE, attempt(g, 1, 2, tolls(), STRICT).failure());
        assertEquals(Failure.CONSTRAINTS_UNVERIFIABLE, attempt(g, 1, 2, unpaved(), STRICT).failure());
        // road class IS known here (SECONDARY, unrestricted), so avoidHighways is verifiable
        assertEquals(Failure.NONE, attempt(g, 1, 2, highways(), STRICT).failure());
    }

    @Test
    void allowUnknown_usesUntaggedEdges_andReportsHowMuchIsUnverified() {
        var g = graph(1, 2, 500, 50, UNTAGGED);
        RouteOutcome o = ok(g, 1, 2, tolls(), ALLOW_UNKNOWN);
        assertEquals(500, o.stats().tollUnverifiedMeters(), 1e-9);
        assertEquals(500, o.stats().surfaceUnverifiedMeters(), 1e-9);
    }

    @Test
    void allowUnknown_stillExcludesKnownViolations() {
        var g = graph(1, 2, 500, 50, TOLL);
        assertEquals(Failure.CONSTRAINTS_UNSATISFIABLE, attempt(g, 1, 2, tolls(), ALLOW_UNKNOWN).failure());
    }

    @Test
    void unknownRoadClass_isUnverifiedForHighwaysUnderStrict_butAllowedWhenUnknownAccepted() {
        var g = graph(1, 2, 500, 50, EdgeAttrs.UNKNOWN);
        assertEquals(Failure.CONSTRAINTS_UNVERIFIABLE, attempt(g, 1, 2, highways(), STRICT).failure());
        RouteOutcome o = ok(g, 1, 2, highways(), ALLOW_UNKNOWN);
        assertEquals(500, o.stats().highwayClassUnknownMeters(), 1e-9);
    }

    // ── never silently relax ─────────────────────────────────────────────────

    @Test
    void impossibleConstraint_isAFailure_neverARouteThatViolatesIt() {
        var g = graph(1, 2, 1000, 60, TOLL);
        RouteAttempt strict = attempt(g, 1, 2, tolls(), STRICT);
        RouteAttempt lenient = attempt(g, 1, 2, tolls(), ALLOW_UNKNOWN);
        assertNull(strict.outcome());
        assertNull(lenient.outcome());
        assertEquals(Failure.CONSTRAINTS_UNSATISFIABLE, strict.failure());
        assertEquals(Failure.CONSTRAINTS_UNSATISFIABLE, lenient.failure());
    }

    @Test
    void disconnectedPoints_areNoRoute_notAConstraintProblem() {
        var g = graph(1, 2, 100, 10, KNOWN_OK);
        g.put(3L, new ArrayList<>());
        assertEquals(Failure.NO_ROUTE, attempt(g, 1, 3, tolls(), STRICT).failure());
        assertEquals(Failure.NO_ROUTE, attempt(g, 1, 3, plain(), STRICT).failure());
    }

    /**
     * Invariant: whatever the graph, a successful STRICT route has zero unverified metres for every
     * active constraint and never uses an edge that is known to violate one.
     */
    @Test
    void randomGraphs_strictSuccessIsAlwaysVerifiedCompliant() {
        Random rnd = new Random(42);
        Boolean[] tri = {true, false, null};
        RoadClass[] classes = RoadClass.values();
        int successes = 0;
        for (int iter = 0; iter < 300; iter++) {
            int n = 8;
            Map<Long, List<Neighbor>> g = new HashMap<>();
            for (long v = 0; v < n; v++) g.put(v, new ArrayList<>());
            Map<String, EdgeAttrs> attrsOf = new HashMap<>();
            for (int e = 0; e < 22; e++) {
                long a = rnd.nextInt(n), b = rnd.nextInt(n);
                if (a == b) continue;
                EdgeAttrs at = new EdgeAttrs(classes[rnd.nextInt(classes.length)], tri[rnd.nextInt(3)], tri[rnd.nextInt(3)], tri[rnd.nextInt(3)]);
                g.get(a).add(new Neighbor(b, 100 + rnd.nextInt(900), 10 + rnd.nextInt(90), at));
            }
            Constraints c = new Constraints(rnd.nextBoolean(), rnd.nextBoolean(), rnd.nextBoolean());
            RouteIntent i = intent(rnd.nextBoolean() ? Objective.FASTEST : Objective.SHORTEST, c, Preferences.NONE);
            RouteAttempt a = attempt(g, 0, n - 1, i, STRICT);
            if (a.outcome() == null) continue;
            successes++;
            var s = a.outcome().stats();
            if (c.avoidTolls()) assertEquals(0, s.tollUnverifiedMeters(), "iter " + iter);
            if (c.avoidUnpaved()) assertEquals(0, s.surfaceUnverifiedMeters(), "iter " + iter);
            if (c.avoidHighways()) assertEquals(0, s.highwayClassUnknownMeters(), "iter " + iter);
            // and every consecutive pair has an edge that genuinely satisfies the constraints
            List<Long> path = a.outcome().nodeIds();
            for (int k = 0; k < path.size() - 1; k++) {
                long to = path.get(k + 1);
                assertTrue(g.get(path.get(k)).stream().anyMatch(nb -> nb.toNodeId() == to && service.allowed(nb.attrs(), c, STRICT)),
                    "iter " + iter + " step " + k);
            }
        }
        assertTrue(successes > 20, "test graphs too restrictive to be meaningful: " + successes);
    }

    // ── soft preference: lighting ────────────────────────────────────────────

    private static final EdgeAttrs LIT = new EdgeAttrs(RoadClass.SECONDARY, null, true, null);
    private static final EdgeAttrs UNLIT = new EdgeAttrs(RoadClass.SECONDARY, null, false, null);

    private static RouteIntent lit(Objective o) { return intent(o, Constraints.NONE, new Preferences(true, false)); }

    @Test
    void preferWellLit_choosesSlightlyLongerLitRoute_andReportsTheCost() {
        // direct unlit 1000 m vs lit 1300 m (1.3x → exactly at, not beyond, the allowed margin of 1.3)
        var g = graph(1, 4, 1000, 100, UNLIT, 1, 3, 600, 60, LIT, 3, 4, 700, 70, LIT);
        RouteOutcome o = ok(g, 1, 4, lit(Objective.SHORTEST), STRICT);
        assertEquals(List.of(1L, 3L, 4L), o.nodeIds());
        assertEquals(1300, o.distanceMeters(), 1e-9);          // real distance, not penalised
        assertEquals(1000, o.baseline().distanceMeters(), 1e-9);
        assertEquals(1300, o.stats().litMeters(), 1e-9);
        assertFalse(o.fellBackToBaseline());
    }

    @Test
    void preferWellLit_isBoundedByMaxDetour_andFallsBackToThePlainRoute() {
        // lit route is 1.4x: the 1.5x unlit penalty makes the search PREFER it, but 1.4 > the 1.3 margin
        var g = graph(1, 4, 1000, 100, UNLIT, 1, 3, 700, 70, LIT, 3, 4, 700, 70, LIT);
        RouteOutcome o = ok(g, 1, 4, lit(Objective.SHORTEST), STRICT);
        assertEquals(List.of(1L, 4L), o.nodeIds());
        assertEquals(1000, o.distanceMeters(), 1e-9);
        assertTrue(o.fellBackToBaseline());
    }

    @Test
    void preferWellLit_doesNotLetUnknownBeatKnownLit_butRanksItBetweenLitAndUnlit() {
        var g = graph(1, 2, 100, 10, UNLIT, 1, 2, 100, 10, UNTAGGED, 1, 2, 100, 10, LIT);
        RouteOutcome o = ok(g, 1, 2, lit(Objective.FASTEST), STRICT);
        assertEquals(100, o.stats().litMeters(), 1e-9);
    }

    @Test
    void softPreferences_neverOverrideHardConstraints() {
        var g = graph(1, 4, 1000, 100, new EdgeAttrs(RoadClass.SECONDARY, true, true, null),   // lit but toll
                      1, 3, 600, 60, new EdgeAttrs(RoadClass.SECONDARY, false, false, null),
                      3, 4, 700, 70, new EdgeAttrs(RoadClass.SECONDARY, false, false, null));
        RouteIntent i = intent(Objective.SHORTEST, new Constraints(true, false, false), new Preferences(true, false));
        assertEquals(List.of(1L, 3L, 4L), ok(g, 1, 4, i, STRICT).nodeIds());
    }

    // ── soft preference: turns (edge-expanded search) ────────────────────────

    /** Nodes on a 0.001° grid. x = longitude index, y = latitude index. */
    private static Map<Long, Node> nodes(Object... xy) { // (id, x, y)*
        Map<Long, Node> m = new HashMap<>();
        for (int i = 0; i < xy.length; i += 3) {
            long id = ((Number) xy[i]).longValue();
            m.put(id, Node.builder().id(id).osmId(id)
                .lat(17.0 + 0.001 * ((Number) xy[i + 2]).doubleValue())
                .lng(81.78 + 0.001 * ((Number) xy[i + 1]).doubleValue()).build());
        }
        return m;
    }

    private static final Map<Long, Node> GRID = nodes(
        // staircase S(0,0) a(1,0) b(1,1) c(2,1) T(2,2) ; L-shape S e(0,1) f(0,2) g(1,2) T
        1, 0, 0, 2, 1, 0, 3, 1, 1, 4, 2, 1, 9, 2, 2,
        5, 0, 1, 6, 0, 2, 7, 1, 2);

    private Map<Long, List<Neighbor>> staircaseVsL(double staircaseEdge, double lEdge) {
        double st = staircaseEdge / 10, lt = lEdge / 10; // 10 m/s
        return graph(1, 2, staircaseEdge, st, KNOWN_OK, 2, 3, staircaseEdge, st, KNOWN_OK,
                     3, 4, staircaseEdge, st, KNOWN_OK, 4, 9, staircaseEdge, st, KNOWN_OK,
                     1, 5, lEdge, lt, KNOWN_OK, 5, 6, lEdge, lt, KNOWN_OK,
                     6, 7, lEdge, lt, KNOWN_OK, 7, 9, lEdge, lt, KNOWN_OK);
    }

    private static RouteIntent turns(Objective o) { return intent(o, Constraints.NONE, new Preferences(false, true)); }

    @Test
    void turnAngle_isGeometricallyCorrect() {
        Map<Long, Node> n = nodes(1, 0, 0, 2, 1, 0, 3, 2, 0, 4, 1, 1, 5, 0, 0);
        assertEquals(0, PreferenceRoutingService.turnAngleDegrees(n.get(1L), n.get(2L), n.get(3L)), 0.5);   // straight
        assertEquals(90, PreferenceRoutingService.turnAngleDegrees(n.get(1L), n.get(2L), n.get(4L)), 1.0);  // right angle
        assertEquals(180, PreferenceRoutingService.turnAngleDegrees(n.get(1L), n.get(2L), n.get(5L)), 0.5); // reversal
    }

    @Test
    void minimizeTurns_picksFewerTurnsWhenTheExtraLengthIsSmall() {
        var g = staircaseVsL(97.5, 100);  // staircase 390 m / 3 turns ; L 400 m / 1 turn
        RouteOutcome plainRoute = service.route(g, GRID, 1, 9, plain(), STRICT).outcome();
        assertEquals(List.of(1L, 2L, 3L, 4L, 9L), plainRoute.nodeIds());
        assertEquals(3, plainRoute.stats().turns());

        RouteOutcome o = service.route(g, GRID, 1, 9, turns(Objective.FASTEST), STRICT).outcome();
        assertEquals(List.of(1L, 5L, 6L, 7L, 9L), o.nodeIds());
        assertEquals(1, o.stats().turns());
        assertEquals(3, o.baseline().turns());
        assertEquals(400, o.distanceMeters(), 1e-9);   // true length — the turn penalty is not in the reported total
        assertEquals(40, o.timeSeconds(), 1e-9);
        assertFalse(o.fellBackToBaseline());
    }

    @Test
    void minimizeTurns_underShortest_usesAMetresPenaltyOfTheSameMagnitude() {
        var g = staircaseVsL(97.5, 100); // 390+3×66.7=590 vs 400+66.7=467 → L route
        RouteOutcome o = service.route(g, GRID, 1, 9, turns(Objective.SHORTEST), STRICT).outcome();
        assertEquals(List.of(1L, 5L, 6L, 7L, 9L), o.nodeIds());
    }

    @Test
    void minimizeTurns_isBoundedByMaxDetour() {
        // staircase 300 m (48 s with 3 turn penalties) vs L 416 m (47.6 s): the search prefers the L,
        // but it is 1.39x the plain length → over the 1.3 margin → plain route returned
        var g = staircaseVsL(75, 104);
        RouteOutcome o = service.route(g, GRID, 1, 9, turns(Objective.FASTEST), STRICT).outcome();
        assertEquals(List.of(1L, 2L, 3L, 4L, 9L), o.nodeIds());
        assertTrue(o.fellBackToBaseline());
    }

    @Test
    void minimizeTurns_respectsHardConstraints() {
        // the L-shape's first edge is a toll road
        Map<Long, List<Neighbor>> g = staircaseVsL(97.5, 100);
        g.get(1L).set(1, new Neighbor(5, 100, 10, TOLL));
        RouteIntent i = intent(Objective.FASTEST, new Constraints(true, false, false), new Preferences(false, true));
        RouteOutcome o = service.route(g, GRID, 1, 9, i, STRICT).outcome();
        assertEquals(List.of(1L, 2L, 3L, 4L, 9L), o.nodeIds());
    }

    @Test
    void minimizeTurns_withParallelEdges_picksTheCheaperOneAndCountsNoPhantomTurns() {
        var g = graph(1, 2, 200, 20, KNOWN_OK, 1, 2, 150, 15, KNOWN_OK, 2, 3, 100, 10, KNOWN_OK);
        Map<Long, Node> n = nodes(1, 0, 0, 2, 1, 0, 3, 2, 0);
        RouteOutcome o = service.route(g, n, 1, 3, turns(Objective.FASTEST), STRICT).outcome();
        assertEquals(250, o.distanceMeters(), 1e-9);
        assertEquals(0, o.stats().turns());
    }

    // ── scale: the shared graph is never copied or mutated ───────────────────

    /** Snapshot that records each edge list's identity and contents. */
    private static Map<Long, List<Object>> snapshot(Map<Long, List<Neighbor>> g) {
        Map<Long, List<Object>> snap = new HashMap<>();
        g.forEach((k, v) -> snap.put(k, new ArrayList<>(List.of(System.identityHashCode(v), List.copyOf(v)))));
        return snap;
    }

    @Test
    void routing_neverMutatesOrReplacesTheSharedAdjacency_whateverIsRequested() {
        var g = staircaseVsL(97.5, 100);
        g.get(1L).set(1, new Neighbor(5, 100, 10, new EdgeAttrs(RoadClass.TRUNK, true, false, false)));
        var before = snapshot(g);

        RouteIntent everything = intent(Objective.SHORTEST, new Constraints(true, true, true), new Preferences(true, true));
        for (UnknownDataPolicy p : UnknownDataPolicy.values()) service.route(g, GRID, 1, 9, everything, p);
        service.route(g, GRID, 1, 9, turns(Objective.FASTEST), STRICT);
        service.route(g, GRID, 1, 9, lit(Objective.FASTEST), STRICT);

        assertEquals(before, snapshot(g));
    }

    @Test
    void minimizeTurns_skipsHonestly_whenTheTripIsTooLongForTheEdgeExpandedSearch() {
        // two nodes ~22 km apart (0.2° of latitude) — beyond the default 12 km limit
        Map<Long, Node> far = nodes(1, 0, 0, 2, 0, 200);
        var g = graph(1, 2, 22_000, 2_000, KNOWN_OK);
        RouteOutcome o = service.route(g, far, 1, 2, turns(Objective.FASTEST), STRICT).outcome();

        assertTrue(o.turnMinimisationSkipped());
        assertEquals(List.of(1L, 2L), o.nodeIds());
        assertEquals(22_000, o.distanceMeters(), 1e-9);
    }

    @Test
    void minimizeTurns_skipped_stillAppliesLighting_whenBothWereRequested() {
        Map<Long, Node> far = nodes(1, 0, 0, 2, 0, 200, 3, 0, 100);
        // direct unlit 22 km vs lit via node 3 (1.1x): lighting should still steer the route although turns are skipped
        var g = graph(1, 2, 22_000, 2_000, UNLIT, 1, 3, 12_100, 1_100, LIT, 3, 2, 12_100, 1_100, LIT);
        RouteIntent both = intent(Objective.SHORTEST, Constraints.NONE, new Preferences(true, true));
        RouteOutcome o = service.route(g, far, 1, 2, both, STRICT).outcome();
        assertTrue(o.turnMinimisationSkipped());
        assertEquals(List.of(1L, 3L, 2L), o.nodeIds());
    }

    @Test
    void shortTrips_areNotSkipped() {
        var g = staircaseVsL(97.5, 100);
        assertFalse(service.route(g, GRID, 1, 9, turns(Objective.FASTEST), STRICT).outcome().turnMinimisationSkipped());
    }

    // ── configuration ────────────────────────────────────────────────────────

    @Test
    void invalidConfiguration_failsFast() {
        IntentConfig bad = new IntentConfig();
        bad.getRouting().setMaxDetourFactor(0.9);
        assertThrows(IllegalArgumentException.class, () -> new PreferenceRoutingService(new DijkstraService(), bad));
        IntentConfig bad2 = new IntentConfig();
        bad2.getRouting().setUnlitFactor(0);
        assertThrows(IllegalArgumentException.class, () -> new PreferenceRoutingService(new DijkstraService(), bad2));
    }
}
