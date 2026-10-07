package com.sashank.map_shortest_path_finder.pipeline;

import com.sashank.map_shortest_path_finder.config.RegionConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.ExpectedCount.times;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class OsmDataFetcherTest {

    private static final String URL = "https://overpass.test/api/interpreter";
    private static final String OK_BODY = """
        {"elements":[{"type":"node","id":1,"lat":16.5,"lon":80.6},
                     {"type":"way","id":9,"nodes":[1,2],"tags":{"highway":"primary"}}]}""";

    private MockRestServiceServer server;
    private OsmDataFetcher fetcher;
    private final List<Long> sleeps = new ArrayList<>();
    private RegionConfig.Importer settings;

    @BeforeEach
    void setUp() {
        RestTemplate rt = new RestTemplate();
        server = MockRestServiceServer.bindTo(rt).build();
        settings = new RegionConfig.Importer();
        settings.setUserAgent("WayFinder-test/1.0");
        settings.setMaxAttempts(3);
        settings.setRetryBackoffMillis(1_000);
        fetcher = new OsmDataFetcher(rt, URL, settings, sleeps::add);
    }

    private static RegionConfig.Bbox box() {
        var b = new RegionConfig.Bbox();
        b.setSouth(16.4); b.setNorth(16.6); b.setWest(80.5); b.setEast(80.7);
        return b;
    }

    @Test
    void sendsAnIdentifyingUserAgent_theFilterAndTheBox() {
        server.expect(requestTo(URL))
            .andExpect(method(HttpMethod.POST))
            .andExpect(header("User-Agent", "WayFinder-test/1.0"))
            .andExpect(header("Accept", "application/json"))
            .andExpect(request -> {
                String body = URLDecoder.decode(request.getBody().toString(), StandardCharsets.UTF_8);
                assertTrue(body.contains("[out:json]"), body);
                assertTrue(body.contains("way[\"highway\"~\"^(motorway|trunk)$\"](16.400000,80.500000,16.600000,80.700000)"), body);
                assertTrue(body.contains(">;"), "must also fetch the nodes the ways reference");
            })
            .andRespond(withSuccess(OK_BODY, MediaType.APPLICATION_JSON));

        OsmResponse r = fetcher.fetchRoadNetwork(box(), "^(motorway|trunk)$");

        assertEquals(2, r.getElements().size());
        server.verify();
    }

    @Test
    void theOneArgumentOverload_usesFullDetail() {
        server.expect(requestTo(URL)).andExpect(request -> {
            String body = URLDecoder.decode(request.getBody().toString(), StandardCharsets.UTF_8);
            assertTrue(body.contains("residential") && body.contains("_link"), body);
        }).andRespond(withSuccess(OK_BODY, MediaType.APPLICATION_JSON));
        fetcher.fetchRoadNetwork(box());
        server.verify();
    }

    @Test
    void overloadAndRateLimiting_areRetriedWithGrowingBackoff() {
        server.expect(times(1), requestTo(URL)).andRespond(withStatus(org.springframework.http.HttpStatus.GATEWAY_TIMEOUT));
        server.expect(times(1), requestTo(URL)).andRespond(withStatus(org.springframework.http.HttpStatus.TOO_MANY_REQUESTS));
        server.expect(times(1), requestTo(URL)).andRespond(withSuccess(OK_BODY, MediaType.APPLICATION_JSON));

        assertNotNull(fetcher.fetchRoadNetwork(box(), "^a$"));
        assertEquals(List.of(1_000L, 2_000L), sleeps);
        server.verify();
    }

    @Test
    void givesUpAfterTheConfiguredAttempts_withAMessageSayingHowToResume() {
        server.expect(times(3), requestTo(URL)).andRespond(withStatus(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE));

        var ex = assertThrows(IllegalStateException.class, () -> fetcher.fetchRoadNetwork(box(), "^a$"));

        assertTrue(ex.getMessage().contains("after 3 attempts"), ex.getMessage());
        assertTrue(ex.getMessage().contains("HTTP 503") && ex.getMessage().contains("resume"), ex.getMessage());
        assertEquals(2, sleeps.size());
    }

    @Test
    void notAcceptable406_isNotRetried_andExplainsTheUserAgentRequirement() {
        server.expect(times(1), requestTo(URL)).andRespond(withStatus(org.springframework.http.HttpStatus.NOT_ACCEPTABLE));

        var ex = assertThrows(IllegalStateException.class, () -> fetcher.fetchRoadNetwork(box(), "^a$"));

        assertTrue(ex.getMessage().contains("User-Agent") && ex.getMessage().contains("region.importer.user-agent"), ex.getMessage());
        assertTrue(sleeps.isEmpty());
    }

    @Test
    void aBadQuery400_isNotRetried_andShowsOverpassesComplaint() {
        server.expect(times(1), requestTo(URL))
            .andRespond(withStatus(org.springframework.http.HttpStatus.BAD_REQUEST).body("line 3: parse error: unexpected ')'").contentType(MediaType.TEXT_PLAIN));
        var ex = assertThrows(IllegalStateException.class, () -> fetcher.fetchRoadNetwork(box(), "^(a$"));
        assertTrue(ex.getMessage().contains("parse error"), ex.getMessage());
        assertTrue(sleeps.isEmpty());
    }

    @Test
    void aServerSideTimeoutReportedAs200WithARemark_isNeverAcceptedAsAPartialTile() {
        String partial = """
            {"remark":"runtime error: Query timed out in \\"query\\" at line 4 after 300 seconds.",
             "elements":[{"type":"node","id":1,"lat":16.5,"lon":80.6}]}""";
        server.expect(times(3), requestTo(URL)).andRespond(withSuccess(partial, MediaType.APPLICATION_JSON));

        var ex = assertThrows(IllegalStateException.class, () -> fetcher.fetchRoadNetwork(box(), "^a$"));

        assertTrue(ex.getMessage().contains("runtime error"), ex.getMessage());
    }

    @Test
    void aCompleteResponseWithAnEmptyElementList_isFine() {
        server.expect(requestTo(URL)).andRespond(withSuccess("{\"elements\":[]}", MediaType.APPLICATION_JSON));
        assertTrue(fetcher.fetchRoadNetwork(box(), "^a$").getElements().isEmpty());
    }

    @Test
    void theFilterIsEscapedForOverpassQl() {
        String q = OsmDataFetcher.buildOverpassQuery("1,2,3,4", "a\"b\\c");
        assertTrue(q.contains("\"a\\\"b\\\\c\""), q);
    }
}
