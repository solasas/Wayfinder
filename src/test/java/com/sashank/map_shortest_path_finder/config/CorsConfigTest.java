package com.sashank.map_shortest_path_finder.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The browser frontend (Vite dev server) calls the API cross-origin with a JSON POST, which triggers a
 * CORS preflight. This drives the project's real CorsFilter with exactly that traffic.
 */
class CorsConfigTest {

    @RestController
    @RequestMapping("/api")
    static class Dummy {
        @PostMapping("/routes/intent") String intent() { return "{}"; }
        @PostMapping("/shortest-path") String path() { return "{}"; }
    }

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new Dummy()).addFilters(new CorsConfig().corsFilter()).build();
    }

    private org.springframework.test.web.servlet.ResultActions preflight(String path, String origin) throws Exception {
        return mvc.perform(options(path)
            .header("Origin", origin)
            .header("Access-Control-Request-Method", "POST")
            .header("Access-Control-Request-Headers", "content-type"));
    }

    @Test
    void viteDevServer_mayPreflightAndPostToTheNewEndpoint() throws Exception {
        preflight("/api/routes/intent", "http://localhost:5173")
            .andExpect(status().isOk())
            .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:5173"))
            .andExpect(header().string("Access-Control-Allow-Methods", org.hamcrest.Matchers.containsString("POST")))
            .andExpect(header().string("Access-Control-Allow-Headers", org.hamcrest.Matchers.containsStringIgnoringCase("content-type")));

        mvc.perform(post("/api/routes/intent").header("Origin", "http://localhost:5173").contentType("application/json").content("{}"))
            .andExpect(status().isOk())
            .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:5173"));
    }

    @Test
    void existingEndpointsKeepWorkingCrossOrigin() throws Exception {
        for (String origin : new String[]{"http://localhost:3000", "http://localhost:5173", "http://localhost:5174"}) {
            preflight("/api/shortest-path", origin)
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", origin));
        }
    }

    @Test
    void otherOrigins_areRefused() throws Exception {
        preflight("/api/routes/intent", "https://evil.example").andExpect(status().isForbidden());
    }

    /** Documents a real limitation: the browser treats 127.0.0.1 and localhost as different origins. */
    @Test
    void loopbackIpOrigin_isNotAllowed_soOpenTheFrontendViaLocalhost() throws Exception {
        preflight("/api/routes/intent", "http://127.0.0.1:5173").andExpect(status().isForbidden());
    }
}
