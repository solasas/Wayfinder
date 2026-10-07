package com.sashank.map_shortest_path_finder.controller;

import com.sashank.map_shortest_path_finder.config.RegionConfig;
import com.sashank.map_shortest_path_finder.exception.ClarificationNeededException;
import com.sashank.map_shortest_path_finder.exception.GlobalExceptionHandler;
import com.sashank.map_shortest_path_finder.exception.LlmUnavailableException;
import com.sashank.map_shortest_path_finder.intent.*;
import com.sashank.map_shortest_path_finder.model.EdgeAttrs;
import com.sashank.map_shortest_path_finder.model.Node;
import com.sashank.map_shortest_path_finder.model.RoadClass;
import com.sashank.map_shortest_path_finder.repository.NodeRepository;
import com.sashank.map_shortest_path_finder.service.DijkstraService;
import com.sashank.map_shortest_path_finder.service.GraphService;
import com.sashank.map_shortest_path_finder.service.GraphService.Neighbor;
import com.sashank.map_shortest_path_finder.service.SnapService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * HTTP contract of POST /api/routes/intent: real controller, real exception handler, real SnapService +
 * RegionConfig (so region validation is the production code, not a stub), real routing engine on a
 * small graph. Only the LLM (IntentParser) and the database lookup are replaced.
 */
class IntentRouteControllerHttpTest {

    private static final String BODY = """
        {"start":{"lat":16.975,"lng":81.778},"end":{"lat":16.990,"lng":81.800},
         "instruction":"Find the fastest route without tolls"}""";

    private IntentParser parser;
    private MockMvc mvc;
    private Node a, b;

    @BeforeEach
    void setUp() {
        RegionConfig region = new RegionConfig();
        region.setName("Rajahmundry");
        region.getBbox().setSouth(16.96); region.getBbox().setNorth(17.01);
        region.getBbox().setWest(81.76);  region.getBbox().setEast(81.82);

        a = Node.builder().id(1L).osmId(10L).lat(16.9751).lng(81.7781).build();
        b = Node.builder().id(2L).osmId(20L).lat(16.9899).lng(81.7999).build();
        NodeRepository nodes = mock(NodeRepository.class);
        when(nodes.findNearestTo(anyDouble(), anyDouble())).thenAnswer(inv -> Optional.of(((double) inv.getArgument(0)) < 16.98 ? a : b));

        SnapService snap = new SnapService();
        ReflectionTestUtils.setField(snap, "nodeRepository", nodes);
        ReflectionTestUtils.setField(snap, "regionConfig", region);

        GraphService graph = mock(GraphService.class);
        when(graph.isEmpty()).thenReturn(false);
        when(graph.getAdjacency()).thenReturn(Map.of(
            1L, List.of(new Neighbor(2, 2345.6, 187.4, new EdgeAttrs(RoadClass.SECONDARY, false, null, true))),
            2L, List.of()));
        when(graph.getNodeIndex()).thenReturn(Map.of(1L, a, 2L, b));
        when(graph.getCoverage()).thenReturn(new GraphService.AttributeCoverage(2, 1.0, 0.5, 0.5, 0.0));

        parser = mock(IntentParser.class);
        IntentRouteService service = new IntentRouteService(new IntentConfig(), parser, mock(PlaceResolver.class), snap, graph,
            new PreferenceRoutingService(new DijkstraService(), new IntentConfig()), region);
        mvc = MockMvcBuilders.standaloneSetup(new IntentRouteController(service))
            .setControllerAdvice(new GlobalExceptionHandler()).build();
    }

    private org.springframework.test.web.servlet.ResultActions call(String body) throws Exception {
        return mvc.perform(post("/api/routes/intent").contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private static RouteIntent intent(Constraints c, Preferences p) {
        return new RouteIntent(null, null, Objective.FASTEST, c, p, List.of());
    }

    @Test
    void ok_returnsEveryRequestedField_withValuesFromTheGraphEngine() throws Exception {
        when(parser.parse("Find the fastest route without tolls", false))
            .thenReturn(intent(new Constraints(true, false, false), new Preferences(true, false)));

        call(BODY)
            .andExpect(status().isOk())
            // path, distance, time: exactly what the engine computed from the graph
            .andExpect(jsonPath("$.path.length()").value(2))
            .andExpect(jsonPath("$.path[0].lat").value(16.9751))
            .andExpect(jsonPath("$.path[0].lng").value(81.7781))
            .andExpect(jsonPath("$.path[1].lat").value(16.9899))
            .andExpect(jsonPath("$.distanceMeters").value(2345.6))
            .andExpect(jsonPath("$.estimatedTimeSecs").value(187))
            // objective, constraints, preferences
            .andExpect(jsonPath("$.objective").value("FASTEST"))
            .andExpect(jsonPath("$.constraints.avoidTolls").value(true))
            .andExpect(jsonPath("$.constraints.avoidHighways").value(false))
            .andExpect(jsonPath("$.constraints.avoidUnpaved").value(false))
            .andExpect(jsonPath("$.preferences.preferWellLit").value(true))
            // requested, but this graph has no lighting tags on the route, so it is NOT reported as applied
            .andExpect(jsonPath("$.preferencesApplied.preferWellLit").value(false))
            .andExpect(jsonPath("$.preferences.minimizeTurns").value(false))
            .andExpect(jsonPath("$.unknownDataPolicy").value("STRICT"))
            .andExpect(jsonPath("$.snappedStart.lat").value(16.9751))
            // explanation and limitations
            .andExpect(jsonPath("$.explanation").isNotEmpty())
            .andExpect(jsonPath("$.dataLimitations").isArray())
            .andExpect(jsonPath("$.dataLimitations", org.hamcrest.Matchers.hasItem(org.hamcrest.Matchers.containsString("Lighting is tagged on 0% of road segments"))))
            .andExpect(jsonPath("$.notices").isArray())
            .andExpect(jsonPath("$.details.turns").value(0))
            .andExpect(jsonPath("$.plainRoute.distanceMeters").value(2345.6));
    }

    @Test
    void ok_whenInstructionNamesNoPlace_parserIsToldDestinationIsNotRequired() throws Exception {
        when(parser.parse("Find the fastest route without tolls", false)).thenReturn(intent(Constraints.NONE, Preferences.NONE));
        call(BODY).andExpect(status().isOk()).andExpect(jsonPath("$.plainRoute").doesNotExist());
        verify(parser).parse("Find the fastest route without tolls", false);
        verify(parser, never()).parse(anyString());
    }

    private static String anyString() { return org.mockito.ArgumentMatchers.anyString(); }

    @Test
    void explicitObjectiveInTheRequest_isHonoured_andEchoedBack() throws Exception {
        when(parser.parse("Find the fastest route without tolls", false)).thenReturn(intent(Constraints.NONE, Preferences.NONE));
        call(BODY.replace("}\"", "}\"").replace("\"instruction\"", "\"objective\":\"SHORTEST\",\"instruction\""))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.objective").value("SHORTEST"));
        call(BODY.replace("\"instruction\"", "\"objective\":\"ECO_FRIENDLY\",\"instruction\""))
            .andExpect(status().isBadRequest());
    }

    /**
     * Writes real serialised responses (produced by the production DTOs + exception handler) to the directory
     * named by -Dcontract.out, for use as fixtures by the frontend's tests. A no-op in normal runs.
     */
    @Test
    void dumpContractFixtures_whenAsked() throws Exception {
        String out = System.getProperty("contract.out");
        org.junit.jupiter.api.Assumptions.assumeTrue(out != null, "set -Dcontract.out=<dir> to write frontend fixtures");
        java.nio.file.Path dir = java.nio.file.Path.of(out);
        java.nio.file.Files.createDirectories(dir);

        when(parser.parse("Find the fastest route without tolls", false))
            .thenReturn(intent(new Constraints(true, false, false), new Preferences(true, false)));
        write(dir, "route-intent-ok.json", call(BODY));

        call("{\"start\":{\"lat\":12.9,\"lng\":77.6},\"end\":{\"lat\":16.99,\"lng\":81.8},\"instruction\":\"x\"}");
        write(dir, "route-intent-400.json", call("{\"start\":{\"lat\":12.9,\"lng\":77.6},\"end\":{\"lat\":16.99,\"lng\":81.8},\"instruction\":\"x\"}"));

        doThrow(new ClarificationNeededException("Do you want the fastest or the shortest route?")).when(parser)
            .parse(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.eq(false));
        write(dir, "route-intent-422.json", call(BODY));

        doThrow(new LlmUnavailableException(LlmUnavailableException.Kind.RATE_LIMITED,
            "The language model is rate-limited right now. Please retry in a few seconds.")).when(parser)
            .parse(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.eq(false));
        write(dir, "route-intent-429.json", call(BODY));
    }

    private static void write(java.nio.file.Path dir, String name, org.springframework.test.web.servlet.ResultActions r) throws Exception {
        java.nio.file.Files.writeString(dir.resolve(name),
            r.andReturn().getResponse().getStatus() + "\n" + r.andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8) + "\n");
    }

    @Test
    void startOutsideTheRegion_is400_viaSnapServiceAndRegionConfig_andNoLlmCall() throws Exception {
        call("""
            {"start":{"lat":12.9,"lng":77.6},"end":{"lat":16.990,"lng":81.800},"instruction":"fastest"}""")
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("outside the supported region 'Rajahmundry'")));
        verifyNoInteractions(parser);
    }

    @Test
    void endOutsideTheRegion_is400() throws Exception {
        call("""
            {"start":{"lat":16.975,"lng":81.778},"end":{"lat":17.2,"lng":81.8},"instruction":"fastest"}""")
            .andExpect(status().isBadRequest());
        verifyNoInteractions(parser);
    }

    @Test
    void missingFields_are400() throws Exception {
        call("{\"start\":{\"lat\":16.975,\"lng\":81.778},\"instruction\":\"fastest\"}").andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("start and end coordinates are required"));
        call("{\"start\":{\"lat\":16.975,\"lng\":81.778},\"end\":{\"lat\":16.99,\"lng\":81.8}}").andExpect(status().isBadRequest());
        call("{\"start\":{\"lat\":16.975,\"lng\":81.778},\"end\":{\"lat\":16.99,\"lng\":81.8},\"instruction\":\"\"}").andExpect(status().isBadRequest());
        call("not json").andExpect(status().isBadRequest());
    }

    @Test
    void invalidUnknownDataPolicy_is400() throws Exception {
        call("""
            {"start":{"lat":16.975,"lng":81.778},"end":{"lat":16.99,"lng":81.8},"instruction":"x","unknownDataPolicy":"YOLO"}""")
            .andExpect(status().isBadRequest());
    }

    @Test
    void contradictoryInstruction_is422_withTheQuestion() throws Exception {
        when(parser.parse(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.eq(false)))
            .thenThrow(new ClarificationNeededException("Do you want the fastest or the shortest route?"));
        call(BODY).andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.clarificationNeeded").value(true))
            .andExpect(jsonPath("$.question").value("Do you want the fastest or the shortest route?"));
    }

    private void parserThrows(LlmUnavailableException.Kind kind) {
        // doThrow().when() form: re-stubbing with when(parser.parse(..)) would invoke the previous throwing stub
        doThrow(new LlmUnavailableException(kind, "msg")).when(parser)
            .parse(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.eq(false));
    }

    @Test
    void llmProblems_useTheirStatuses() throws Exception {
        parserThrows(LlmUnavailableException.Kind.RATE_LIMITED);
        call(BODY).andExpect(status().isTooManyRequests());
        parserThrows(LlmUnavailableException.Kind.NOT_CONFIGURED);
        call(BODY).andExpect(status().isServiceUnavailable());
        parserThrows(LlmUnavailableException.Kind.TIMEOUT);
        call(BODY).andExpect(status().isGatewayTimeout());
    }

    @Test
    void constraintThatCannotBeVerified_is404_likeOtherRouteNotFoundCases() throws Exception {
        // the only edge has toll=null (untagged): under STRICT it cannot be shown to be toll-free
        GraphService graph = mock(GraphService.class);
        when(graph.isEmpty()).thenReturn(false);
        when(graph.getAdjacency()).thenReturn(Map.of(
            1L, List.of(new Neighbor(2, 1000, 90, new EdgeAttrs(RoadClass.SECONDARY, null, null, null))), 2L, List.of()));
        when(graph.getNodeIndex()).thenReturn(Map.of(1L, a, 2L, b));
        when(graph.getCoverage()).thenReturn(new GraphService.AttributeCoverage(2, 1.0, 0.0, 0.0, 0.0));
        RegionConfig region = new RegionConfig();
        region.setName("Rajahmundry");
        region.getBbox().setSouth(16.96); region.getBbox().setNorth(17.01); region.getBbox().setWest(81.76); region.getBbox().setEast(81.82);
        NodeRepository nodes = mock(NodeRepository.class);
        when(nodes.findNearestTo(anyDouble(), anyDouble())).thenAnswer(inv -> Optional.of(((double) inv.getArgument(0)) < 16.98 ? a : b));
        SnapService snap = new SnapService();
        ReflectionTestUtils.setField(snap, "nodeRepository", nodes);
        ReflectionTestUtils.setField(snap, "regionConfig", region);
        IntentRouteService service = new IntentRouteService(new IntentConfig(), parser, mock(PlaceResolver.class), snap, graph,
            new PreferenceRoutingService(new DijkstraService(), new IntentConfig()), region);
        MockMvc strictMvc = MockMvcBuilders.standaloneSetup(new IntentRouteController(service))
            .setControllerAdvice(new GlobalExceptionHandler()).build();
        when(parser.parse(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.eq(false)))
            .thenReturn(intent(new Constraints(true, false, false), Preferences.NONE));

        String contractDir = System.getProperty("contract.out");
        if (contractDir != null) { // frontend fixture: the 404 the UI acts on (see dumpContractFixtures_whenAsked)
            write(java.nio.file.Path.of(contractDir), "route-intent-404-unverifiable.json",
                strictMvc.perform(post("/api/routes/intent").contentType(MediaType.APPLICATION_JSON).content(BODY)));
        }
        strictMvc.perform(post("/api/routes/intent").contentType(MediaType.APPLICATION_JSON).content(BODY))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("ALLOW_UNKNOWN")))
            .andExpect(jsonPath("$.code").value("CONSTRAINTS_NOT_SATISFIABLE"))
            .andExpect(jsonPath("$.retryWithAllowUnknown").value(true));
    }

    @Test
    void existingNameBasedEndpoint_isUnchanged_andStillRequiresADestinationFromTheText() throws Exception {
        when(parser.parse("fastest please")).thenReturn(intent(Constraints.NONE, Preferences.NONE)); // no destination in text
        mvc.perform(post("/api/intent-route").contentType(MediaType.APPLICATION_JSON)
                .content("{\"query\":\"fastest please\",\"start\":{\"lat\":16.975,\"lng\":81.778}}"))
            .andExpect(status().isUnprocessableEntity());
    }
}
