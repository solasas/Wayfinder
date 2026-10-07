package com.sashank.map_shortest_path_finder.intent;

import com.sashank.map_shortest_path_finder.model.EdgeAttrs;
import com.sashank.map_shortest_path_finder.model.Node;
import com.sashank.map_shortest_path_finder.model.RoadClass;
import com.sashank.map_shortest_path_finder.service.DijkstraService;
import com.sashank.map_shortest_path_finder.service.GraphService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.function.ToDoubleFunction;

/**
 * Applies a {@link RouteIntent} to the existing routing engine. {@link DijkstraService} is
 * used unmodified; this class only prepares the graph it searches.
 *
 * HARD constraints — a pre-filtered view of the adjacency map. Edges that don't qualify are
 * simply absent, so no search can use them. Whether an edge with MISSING metadata qualifies
 * is decided by the user's {@link UnknownDataPolicy}:
 *   STRICT        → only edges positively verified compliant remain
 *   ALLOW_UNKNOWN → only edges KNOWN to violate are removed
 * Constraints are never relaxed. If no route survives, the caller gets a {@link Failure}
 * that distinguishes "none exists", "none can be verified" and "none exists even if unknown
 * data is accepted".
 *
 * Objective — FASTEST / SHORTEST map straight onto the existing {@code WeightType}.
 *
 * SOFT preferences — they change what Dijkstra minimises, so they are bounded to keep the
 * objective meaningful:
 *   preferWellLit  → each edge's cost is multiplied by a unitless factor (lit < unknown <
 *                    unlit), so costs stay in the objective's own units.
 *   minimizeTurns  → a turn depends on the previous edge, which a node-based search can't
 *                    see. The search therefore runs over an EDGE-EXPANDED graph (one state
 *                    per directed edge, transitions priced with the turn angle between the
 *                    incoming and outgoing edge) — still the same DijkstraService.
 * The preference-optimised route is accepted only if its real objective cost is within
 * {@code maxDetourFactor} of the plain route (same constraints, no preferences); otherwise
 * the plain route is returned and flagged. Reported distance/time are always the true
 * totals of the chosen edges, never penalised costs.
 */
@Service
public class PreferenceRoutingService {

    private static final long SRC = -1L;
    private static final long TGT = -2L;

    public enum Failure { NONE, NO_ROUTE, CONSTRAINTS_UNVERIFIABLE, CONSTRAINTS_UNSATISFIABLE }

    /** Real metres on segments whose compliance data is missing, lighting breakdown, and turn count. */
    public record RouteStats(double tollUnverifiedMeters, double surfaceUnverifiedMeters,
                             double highwayClassUnknownMeters, double litMeters, double unlitMeters,
                             double unknownLitMeters, int turns) {}

    /** The plain route (same hard constraints, no soft preferences) — the reference for any detour. */
    public record Baseline(double distanceMeters, double timeSeconds, int turns) {}

    /** turnMinimisationSkipped: minimizeTurns was requested but the trip is too long for the edge-expanded search. */
    public record RouteOutcome(List<Long> nodeIds, double distanceMeters, double timeSeconds,
                               RouteStats stats, Baseline baseline, boolean fellBackToBaseline,
                               boolean turnMinimisationSkipped) {}

    /** Exactly one of outcome / failure is meaningful: failure == NONE iff outcome != null. */
    public record RouteAttempt(RouteOutcome outcome, Failure failure) {}

    private record Step(long from, GraphService.Neighbor edge) {}
    private record Route(List<Long> nodeIds, List<Step> steps) {}

    private final DijkstraService dijkstraService;
    private final IntentConfig.Routing cfg;
    private final Set<RoadClass> restrictedClasses;

    @Autowired
    public PreferenceRoutingService(DijkstraService dijkstraService, IntentConfig config) {
        this.dijkstraService = dijkstraService;
        this.cfg = config.getRouting();
        if (cfg.getLitFactor() <= 0 || cfg.getUnknownLitFactor() <= 0 || cfg.getUnlitFactor() <= 0) {
            throw new IllegalArgumentException("intent.routing lighting factors must be positive");
        }
        if (cfg.getTurnPenaltySeconds() < 0 || cfg.getNominalSpeedKmh() <= 0) {
            throw new IllegalArgumentException("intent.routing turn penalty must be >= 0 and nominal speed > 0");
        }
        if (cfg.getMaxDetourFactor() < 1.0) {
            throw new IllegalArgumentException("intent.routing.max-detour-factor must be >= 1.0");
        }
        this.restrictedClasses = cfg.getHighwayClasses().isEmpty()
            ? EnumSet.noneOf(RoadClass.class) : EnumSet.copyOf(cfg.getHighwayClasses());
    }

    /**
     * @param nodes node coordinates, needed only to measure turn angles (may be empty otherwise)
     */
    public RouteAttempt route(Map<Long, List<GraphService.Neighbor>> adjacency, Map<Long, Node> nodes,
                              long sourceId, long targetId, RouteIntent intent, UnknownDataPolicy policy) {
        DijkstraService.WeightType weight = intent.objective().weightType();
        DijkstraService.EdgePolicy hard = hardPolicy(intent.constraints(), policy);

        Route plain = plainRoute(adjacency, sourceId, targetId, weight, hard);
        if (plain == null) {
            return new RouteAttempt(null, diagnose(adjacency, sourceId, targetId, intent, policy, weight));
        }

        Route chosen = plain;
        boolean fellBack = false;
        boolean turnsSkipped = false;
        Preferences prefs = intent.preferences();
        if (prefs.preferWellLit() || prefs.minimizeTurns()) {
            Route preferred;
            if (prefs.minimizeTurns() && tooLongForTurnSearch(nodes, sourceId, targetId)) {
                turnsSkipped = true; // honest fallback: lighting (if asked) is still applied, turns are not
                preferred = prefs.preferWellLit() ? litWeightedRoute(adjacency, sourceId, targetId, intent, hard) : null;
            } else if (prefs.minimizeTurns()) {
                preferred = turnAwareRoute(adjacency, nodes, sourceId, targetId, intent, hard);
            } else {
                preferred = litWeightedRoute(adjacency, sourceId, targetId, intent, hard);
            }
            if (preferred != null) {
                if (cost(preferred, weight) <= cost(plain, weight) * cfg.getMaxDetourFactor()) {
                    chosen = preferred;
                } else {
                    fellBack = true;
                }
            }
        }

        double[] totals = totals(chosen);
        double[] plainTotals = totals(plain);
        return new RouteAttempt(new RouteOutcome(
            chosen.nodeIds(), totals[0], totals[1], stats(chosen, nodes),
            new Baseline(plainTotals[0], plainTotals[1], countTurns(plain, nodes)), fellBack, turnsSkipped), Failure.NONE);
    }

    // ── Hard constraints ──────────────────────────────────────────────────────

    /** Whether an edge may be used under the constraints and the unknown-data policy. */
    boolean allowed(EdgeAttrs a, Constraints c, UnknownDataPolicy policy) {
        boolean strict = policy == UnknownDataPolicy.STRICT;
        if (c.avoidTolls() && !(strict ? Boolean.FALSE.equals(a.toll()) : !Boolean.TRUE.equals(a.toll()))) {
            return false;
        }
        if (c.avoidHighways()) {
            boolean restricted = restrictedClasses.contains(a.roadClass());
            boolean verified = a.roadClass() != RoadClass.UNKNOWN;
            if (restricted || (strict && !verified)) return false;
        }
        if (c.avoidUnpaved() && !(strict ? Boolean.TRUE.equals(a.paved()) : !Boolean.FALSE.equals(a.paved()))) {
            return false;
        }
        return true;
    }

    /**
     * Hard constraints as a traversal-time policy: forbidden edges are simply skipped by the search. The shared
     * adjacency map is never copied or modified — essential at corridor scale (millions of edges).
     */
    private DijkstraService.EdgePolicy hardPolicy(Constraints c, UnknownDataPolicy policy) {
        if (!c.any()) return DijkstraService.EdgePolicy.ALL;
        return (from, nb) -> allowed(nb.attrs(), c, policy);
    }

    /** Works out WHY no route survived the constraints, so the user knows what (if anything) they can decide. */
    private Failure diagnose(Map<Long, List<GraphService.Neighbor>> adjacency, long src, long tgt,
                             RouteIntent intent, UnknownDataPolicy policy, DijkstraService.WeightType weight) {
        if (!intent.constraints().any()) return Failure.NO_ROUTE;
        if (plainRoute(adjacency, src, tgt, weight, DijkstraService.EdgePolicy.ALL) == null) return Failure.NO_ROUTE; // disconnected regardless
        if (policy == UnknownDataPolicy.STRICT) {
            DijkstraService.EdgePolicy lenient = hardPolicy(intent.constraints(), UnknownDataPolicy.ALLOW_UNKNOWN);
            if (plainRoute(adjacency, src, tgt, weight, lenient) != null) return Failure.CONSTRAINTS_UNVERIFIABLE;
        }
        return Failure.CONSTRAINTS_UNSATISFIABLE;
    }

    // ── Searches (all via the unmodified DijkstraService) ─────────────────────

    private Route plainRoute(Map<Long, List<GraphService.Neighbor>> adjacency, long src, long tgt,
                             DijkstraService.WeightType weight, DijkstraService.EdgePolicy hard) {
        Optional<DijkstraService.PathResult> r = dijkstraService.findShortestPath(adjacency, src, tgt, weight, hard);
        return r.map(p -> routeFromNodes(adjacency, p.nodeIds(), hard, nb -> raw(nb, weight))).orElse(null);
    }

    private Route litWeightedRoute(Map<Long, List<GraphService.Neighbor>> adjacency, long src, long tgt,
                                   RouteIntent intent, DijkstraService.EdgePolicy hard) {
        DijkstraService.WeightType weight = intent.objective().weightType();
        DijkstraService.EdgePolicy weighted = hard.withFactor(nb -> litFactor(nb.attrs()));
        Optional<DijkstraService.PathResult> r = dijkstraService.findShortestPath(adjacency, src, tgt, weight, weighted);
        return r.map(p -> routeFromNodes(adjacency, p.nodeIds(), hard, nb -> raw(nb, weight) * litFactor(nb.attrs()))).orElse(null);
    }

    /** Whether src→tgt is beyond what the edge-expanded turn search is allowed to handle (straight-line metres). */
    private boolean tooLongForTurnSearch(Map<Long, Node> nodes, long src, long tgt) {
        Node a = nodes.get(src), b = nodes.get(tgt);
        return a != null && b != null && haversineMeters(a, b) > cfg.getMaxTurnAwareDistanceMeters();
    }

    /**
     * Edge-expanded search. State i = directed edge i (u→v). A transition i→j (v→w) costs j's own
     * (lighting-scaled) cost plus a turn penalty if the u→v→w bend is sharp enough. Virtual SRC enters the
     * source's outgoing edges; edges ending at the target reach TGT.
     *
     * Only edges inside a WINDOW around the trip are expanded (the endpoints' bounding box plus a margin of at
     * least {@code turnAwareWindowMarginMeters}, or half the trip length if that is larger): with millions of
     * edges in the graph, expanding all of them per request would be prohibitive. A route that genuinely needs to
     * leave the window is not found by this search, in which case the plain route stands.
     */
    private Route turnAwareRoute(Map<Long, List<GraphService.Neighbor>> adjacency, Map<Long, Node> nodes,
                                 long src, long tgt, RouteIntent intent, DijkstraService.EdgePolicy hard) {
        DijkstraService.WeightType weight = intent.objective().weightType();
        boolean lit = intent.preferences().preferWellLit();
        double penaltyS = cfg.getTurnPenaltySeconds();
        double penaltyM = penaltyS * cfg.getNominalSpeedKmh() * 1000.0 / 3600.0;
        if (src == tgt) return new Route(List.of(src), List.of());

        double[] window = turnWindow(nodes.get(src), nodes.get(tgt)); // {south, north, west, east} or null
        List<Step> edges = new ArrayList<>();
        Map<Long, List<Integer>> outgoing = new HashMap<>();
        for (Map.Entry<Long, List<GraphService.Neighbor>> e : adjacency.entrySet()) {
            long from = e.getKey();
            if (!inWindow(window, nodes.get(from))) continue;
            for (GraphService.Neighbor nb : e.getValue()) {
                if (!hard.allowed(from, nb) || !inWindow(window, nodes.get(nb.toNodeId()))) continue;
                outgoing.computeIfAbsent(from, k -> new ArrayList<>()).add(edges.size());
                edges.add(new Step(from, nb));
            }
        }

        Map<Long, List<GraphService.Neighbor>> expanded = new HashMap<>();
        expanded.put(SRC, new ArrayList<>());
        expanded.put(TGT, new ArrayList<>());
        for (int i = 0; i < edges.size(); i++) expanded.put((long) i, new ArrayList<>());

        for (int j : outgoing.getOrDefault(src, List.of())) {
            GraphService.Neighbor f = edges.get(j).edge();
            double m = lit ? litFactor(f.attrs()) : 1.0;
            expanded.get(SRC).add(new GraphService.Neighbor(j, f.distanceMeters() * m, f.timeSeconds() * m, f.attrs()));
        }
        for (int i = 0; i < edges.size(); i++) {
            Step in = edges.get(i);
            long v = in.edge().toNodeId();
            if (v == tgt) expanded.get((long) i).add(new GraphService.Neighbor(TGT, 0, 0));
            for (int j : outgoing.getOrDefault(v, List.of())) {
                GraphService.Neighbor f = edges.get(j).edge();
                double m = lit ? litFactor(f.attrs()) : 1.0;
                boolean turn = isTurn(nodes, in.from(), v, f.toNodeId());
                expanded.get((long) i).add(new GraphService.Neighbor(j,
                    f.distanceMeters() * m + (turn ? penaltyM : 0),
                    f.timeSeconds() * m + (turn ? penaltyS : 0), f.attrs()));
            }
        }

        Optional<DijkstraService.PathResult> r = dijkstraService.findShortestPath(expanded, SRC, TGT, weight);
        if (r.isEmpty()) return null;
        List<Step> steps = new ArrayList<>();
        List<Long> nodeIds = new ArrayList<>(List.of(src));
        for (long state : r.get().nodeIds()) {
            if (state == SRC || state == TGT) continue;
            Step st = edges.get((int) state);
            steps.add(st);
            nodeIds.add(st.edge().toNodeId());
        }
        return new Route(nodeIds, steps);
    }

    /** {south, north, west, east} around the two endpoints, or null (= no window) when coordinates are unknown. */
    private double[] turnWindow(Node a, Node b) {
        if (a == null || b == null) return null;
        double margin = Math.max(cfg.getTurnAwareWindowMarginMeters(), 0.5 * haversineMeters(a, b));
        double dLat = margin / 111_320.0;
        double dLng = margin / (111_320.0 * Math.cos(Math.toRadians((a.getLat() + b.getLat()) / 2)));
        return new double[]{
            Math.min(a.getLat(), b.getLat()) - dLat, Math.max(a.getLat(), b.getLat()) + dLat,
            Math.min(a.getLng(), b.getLng()) - dLng, Math.max(a.getLng(), b.getLng()) + dLng};
    }

    private static boolean inWindow(double[] w, Node n) {
        return w == null || n == null
            || (n.getLat() >= w[0] && n.getLat() <= w[1] && n.getLng() >= w[2] && n.getLng() <= w[3]);
    }

    static double haversineMeters(Node a, Node b) {
        double dLat = Math.toRadians(b.getLat() - a.getLat()), dLng = Math.toRadians(b.getLng() - a.getLng());
        double h = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                 + Math.cos(Math.toRadians(a.getLat())) * Math.cos(Math.toRadians(b.getLat())) * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        return 6_371_000.0 * 2 * Math.atan2(Math.sqrt(h), Math.sqrt(1 - h));
    }

    // ── Route assembly / measurement ──────────────────────────────────────────

    /** Resolves a node path to concrete edges, picking the cheapest parallel edge by {@code cost}. */
    private Route routeFromNodes(Map<Long, List<GraphService.Neighbor>> graph, List<Long> nodeIds,
                                 DijkstraService.EdgePolicy hard, ToDoubleFunction<GraphService.Neighbor> cost) {
        List<Step> steps = new ArrayList<>();
        for (int i = 0; i < nodeIds.size() - 1; i++) {
            long from = nodeIds.get(i), to = nodeIds.get(i + 1);
            GraphService.Neighbor best = null;
            for (GraphService.Neighbor nb : graph.getOrDefault(from, List.of())) {
                if (nb.toNodeId() == to && hard.allowed(from, nb)
                        && (best == null || cost.applyAsDouble(nb) < cost.applyAsDouble(best))) best = nb;
            }
            if (best == null) {
                throw new IllegalStateException("No edge " + from + "->" + to + " while rebuilding route totals");
            }
            steps.add(new Step(from, best));
        }
        return new Route(List.copyOf(nodeIds), steps);
    }

    private static double raw(GraphService.Neighbor nb, DijkstraService.WeightType weight) {
        return weight == DijkstraService.WeightType.TIME ? nb.timeSeconds() : nb.distanceMeters();
    }

    private static double cost(Route r, DijkstraService.WeightType weight) {
        double sum = 0;
        for (Step s : r.steps()) sum += raw(s.edge(), weight);
        return sum;
    }

    private static double[] totals(Route r) {
        double d = 0, t = 0;
        for (Step s : r.steps()) { d += s.edge().distanceMeters(); t += s.edge().timeSeconds(); }
        return new double[]{d, t};
    }

    private RouteStats stats(Route r, Map<Long, Node> nodes) {
        double toll = 0, surface = 0, cls = 0, lit = 0, unlit = 0, unknownLit = 0;
        for (Step s : r.steps()) {
            EdgeAttrs a = s.edge().attrs();
            double d = s.edge().distanceMeters();
            if (a.toll() == null) toll += d;
            if (a.paved() == null) surface += d;
            if (a.roadClass() == RoadClass.UNKNOWN) cls += d;
            if (a.lit() == null) unknownLit += d; else if (a.lit()) lit += d; else unlit += d;
        }
        return new RouteStats(toll, surface, cls, lit, unlit, unknownLit, countTurns(r, nodes));
    }

    // ── Soft-preference helpers ───────────────────────────────────────────────

    private double litFactor(EdgeAttrs a) {
        return a.lit() == null ? cfg.getUnknownLitFactor() : (a.lit() ? cfg.getLitFactor() : cfg.getUnlitFactor());
    }

    private int countTurns(Route r, Map<Long, Node> nodes) {
        int turns = 0;
        List<Long> ids = r.nodeIds();
        for (int i = 1; i < ids.size() - 1; i++) {
            if (isTurn(nodes, ids.get(i - 1), ids.get(i), ids.get(i + 1))) turns++;
        }
        return turns;
    }

    /** True when the bend a→b→c is at least the configured threshold. Unknown coordinates → not a turn. */
    private boolean isTurn(Map<Long, Node> nodes, long a, long b, long c) {
        Node na = nodes.get(a), nb = nodes.get(b), nc = nodes.get(c);
        if (na == null || nb == null || nc == null) return false;
        return turnAngleDegrees(na, nb, nc) >= cfg.getTurnAngleThresholdDegrees();
    }

    /** Deflection angle at b in degrees: 0 = straight on, 90 = right angle, 180 = reversal. */
    static double turnAngleDegrees(Node a, Node b, Node c) {
        double k = Math.cos(Math.toRadians(b.getLat())); // shrink longitude to local metres-equivalent
        double x1 = (b.getLng() - a.getLng()) * k, y1 = b.getLat() - a.getLat();
        double x2 = (c.getLng() - b.getLng()) * k, y2 = c.getLat() - b.getLat();
        return Math.toDegrees(Math.abs(Math.atan2(x1 * y2 - y1 * x2, x1 * x2 + y1 * y2)));
    }
}
