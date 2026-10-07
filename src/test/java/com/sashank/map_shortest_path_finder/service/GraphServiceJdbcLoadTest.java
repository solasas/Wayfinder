package com.sashank.map_shortest_path_finder.service;

import com.sashank.map_shortest_path_finder.model.EdgeAttrs;
import com.sashank.map_shortest_path_finder.model.Node;
import com.sashank.map_shortest_path_finder.model.RoadClass;
import com.sashank.map_shortest_path_finder.support.EmbeddedPg;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** The streaming graph loader, against real PostgreSQL. */
class GraphServiceJdbcLoadTest {

    private JdbcTemplate jdbc;
    private GraphService graph;
    private MockEnvironment env;

    @BeforeEach
    void setUp() {
        EmbeddedPg.freshSchema();
        jdbc = EmbeddedPg.jdbc();
        env = new MockEnvironment();
        graph = new GraphService();
        ReflectionTestUtils.setField(graph, "jdbc", jdbc);
        ReflectionTestUtils.setField(graph, "transactionManager", EmbeddedPg.txManager());
        ReflectionTestUtils.setField(graph, "environment", env);
    }

    private void node(long id, long osm, double lat, double lng) {
        jdbc.update("INSERT INTO nodes (id, osm_id, lat, lng) VALUES (?, ?, ?, ?)", id, osm, lat, lng);
    }

    private void edge(long from, long to, double dist, double kmh, String highway, Boolean toll, Boolean lit, Boolean paved) {
        jdbc.update("INSERT INTO edges (from_node_id, to_node_id, distance_meters, speed_kmh, osm_way_id, highway, toll, lit, paved) "
            + "VALUES (?, ?, ?, ?, 1, ?, ?, ?, ?)", from, to, dist, kmh, highway, toll, lit, paved);
    }

    @Test
    void loadsNodesAndAdjacency_withTimeDerivedFromSpeed() {
        node(1, 101, 16.50, 80.60); node(2, 102, 16.51, 80.61); node(3, 103, 16.52, 80.62);
        edge(1, 2, 360, 36, "primary", null, null, null);     // 36 km/h = 10 m/s → 36 s
        edge(2, 3, 100, 50, "residential", null, null, null);

        graph.loadGraph();

        assertFalse(graph.isEmpty());
        assertEquals(3, graph.getNodeIndex().size());
        Node n = graph.getNodeIndex().get(2L);
        assertEquals(102L, n.getOsmId());
        assertEquals(16.51, n.getLat(), 1e-12);
        assertEquals(80.61, n.getLng(), 1e-12);

        var out = graph.getAdjacency().get(1L);
        assertEquals(1, out.size());
        assertEquals(2L, out.get(0).toNodeId());
        assertEquals(360, out.get(0).distanceMeters(), 1e-9);
        assertEquals(36.0, out.get(0).timeSeconds(), 1e-9);
        assertEquals(List.of(), graph.getAdjacency().get(3L), "a node with no outgoing edges still has an (empty) entry");
    }

    @Test
    void attributes_areLoaded_withNullStayingNull_andRoadClassMapped() {
        node(1, 1, 16.5, 80.6); node(2, 2, 16.6, 80.7);
        edge(1, 2, 100, 40, "trunk_link", true, false, null);
        edge(2, 1, 100, 40, null, null, null, true);

        graph.loadGraph();

        EdgeAttrs a = graph.getAdjacency().get(1L).get(0).attrs();
        assertEquals(RoadClass.TRUNK, a.roadClass());
        assertEquals(Boolean.TRUE, a.toll());
        assertEquals(Boolean.FALSE, a.lit());
        assertNull(a.paved());

        EdgeAttrs b = graph.getAdjacency().get(2L).get(0).attrs();
        assertEquals(RoadClass.UNKNOWN, b.roadClass(), "no highway tag at all is UNKNOWN, not OTHER");
        assertNull(b.toll());
        assertEquals(Boolean.TRUE, b.paved());
    }

    @Test
    void identicalAttributes_shareOneInstance_soAMillionEdgesDoNotEachHoldACopy() {
        node(1, 1, 16.5, 80.6); node(2, 2, 16.6, 80.7); node(3, 3, 16.7, 80.8);
        edge(1, 2, 100, 40, "primary", false, null, true);
        edge(2, 3, 100, 40, "primary", false, null, true);
        edge(3, 1, 100, 40, "primary", false, null, true);

        graph.loadGraph();

        EdgeAttrs first = graph.getAdjacency().get(1L).get(0).attrs();
        assertSame(first, graph.getAdjacency().get(2L).get(0).attrs());
        assertSame(first, graph.getAdjacency().get(3L).get(0).attrs());
    }

    @Test
    void coverage_reflectsTheShareOfTaggedEdges() {
        node(1, 1, 16.5, 80.6); node(2, 2, 16.6, 80.7);
        edge(1, 2, 100, 40, "primary", true, true, true);      // fully tagged
        edge(2, 1, 100, 40, "primary", null, null, null);      // class only
        edge(1, 2, 100, 40, null, null, null, null);           // nothing
        edge(2, 1, 100, 40, "primary", false, null, null);     // class + toll

        graph.loadGraph();

        var c = graph.getCoverage();
        assertEquals(4, c.edges());
        assertEquals(0.75, c.roadClass(), 1e-9);
        assertEquals(0.50, c.toll(), 1e-9);
        assertEquals(0.25, c.surface(), 1e-9);
        assertEquals(0.25, c.lighting(), 1e-9);
    }

    @Test
    void anEmptyDatabase_givesAnEmptyGraph_notAnError() {
        graph.loadGraph();
        assertTrue(graph.isEmpty());
        assertEquals(Map.of(), graph.getAdjacency());
    }

    @Test
    void duringAnImportRun_theGraphIsNotLoaded() {
        node(1, 1, 16.5, 80.6);
        env.setActiveProfiles("import");

        graph.loadGraph();

        assertTrue(graph.isEmpty(), "the import profile must not pull the (possibly huge) graph into memory");
    }

    @Test
    void reloading_replacesTheGraph() {
        node(1, 1, 16.5, 80.6);
        graph.loadGraph();
        assertEquals(1, graph.getNodeIndex().size());

        node(2, 2, 16.6, 80.7);
        graph.loadGraph();
        assertEquals(2, graph.getNodeIndex().size());
    }

    @Test
    void fiftyThousandNodes_loadQuickly_andCompletely() {
        jdbc.execute("INSERT INTO nodes (id, osm_id, lat, lng) SELECT g, g + 1000000, 16 + g * 1e-5, 80 + g * 1e-5 FROM generate_series(1, 50000) g");
        jdbc.execute("INSERT INTO edges (from_node_id, to_node_id, distance_meters, speed_kmh, osm_way_id, highway) "
            + "SELECT g, g + 1, 50, 40, g, 'residential' FROM generate_series(1, 49999) g");

        long t0 = System.nanoTime();
        graph.loadGraph();
        long ms = (System.nanoTime() - t0) / 1_000_000;

        assertEquals(50_000, graph.getNodeIndex().size());
        assertEquals(49_999, graph.getCoverage().edges());
        assertEquals(1, graph.getAdjacency().get(25_000L).size());
        assertTrue(ms < 10_000, "took " + ms + " ms");
    }
}
