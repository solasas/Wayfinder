package com.sashank.map_shortest_path_finder.intent;

import com.sashank.map_shortest_path_finder.config.RegionConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class NominatimPlaceResolverTest {

    private MockRestServiceServer server;
    private NominatimPlaceResolver resolver;

    @BeforeEach
    void setUp() {
        RegionConfig region = new RegionConfig();
        region.setName("Rajahmundry");
        region.getBbox().setSouth(16.96);
        region.getBbox().setNorth(17.01);
        region.getBbox().setWest(81.76);
        region.getBbox().setEast(81.82);

        RestClient.Builder builder = RestClient.builder().baseUrl("https://geo.test");
        server = MockRestServiceServer.bindTo(builder).build();
        resolver = new NominatimPlaceResolver(region, builder.build());
    }

    @Test
    void inRegionHit_isResolved_withBoundedViewboxQuery_andCached() {
        server.expect(once(), requestTo(org.hamcrest.Matchers.containsString("/search?q=bus%20stand")))
            .andExpect(queryParam("bounded", "1"))
            .andExpect(queryParam("viewbox", "81.760000,17.010000,81.820000,16.960000"))
            .andRespond(withSuccess("""
                [{"lat":"16.99","lon":"81.79","display_name":"Bus Stand, Rajahmundry, India"}]
                """, MediaType.APPLICATION_JSON));

        var first = resolver.resolve("bus stand");
        var second = resolver.resolve("  Bus Stand "); // cache key is normalised → no second HTTP call

        assertEquals(16.99, first.location().lat(), 1e-9);
        assertEquals("Bus Stand, Rajahmundry, India", first.displayName());
        assertSame(first, second);
        server.verify();
    }

    @Test
    void noResults_isPlaceNotFound() {
        server.expect(requestTo(org.hamcrest.Matchers.containsString("/search")))
            .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));
        assertThrows(PlaceResolver.PlaceNotFoundException.class, () -> resolver.resolve("airport"));
    }

    @Test
    void hitOutsideRegion_isRejectedEvenIfGeocoderReturnsIt() {
        server.expect(requestTo(org.hamcrest.Matchers.containsString("/search")))
            .andRespond(withSuccess("""
                [{"lat":"17.11","lon":"81.82","display_name":"Rajahmundry Airport"}]
                """, MediaType.APPLICATION_JSON));
        assertThrows(PlaceResolver.PlaceNotFoundException.class, () -> resolver.resolve("airport"));
    }

    @Test
    void upstreamFailure_isServiceUnavailable() {
        server.expect(requestTo(org.hamcrest.Matchers.containsString("/search"))).andRespond(withServerError());
        assertThrows(IllegalStateException.class, () -> resolver.resolve("station"));
    }
}
