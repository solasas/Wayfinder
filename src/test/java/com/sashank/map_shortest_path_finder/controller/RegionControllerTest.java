package com.sashank.map_shortest_path_finder.controller;

import com.sashank.map_shortest_path_finder.config.RegionConfig;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class RegionControllerTest {

    private static RegionConfig.Bbox box(double s, double n, double w, double e) {
        var b = new RegionConfig.Bbox();
        b.setSouth(s); b.setNorth(n); b.setWest(w); b.setEast(e);
        return b;
    }

    private MockMvc mvcFor(RegionConfig cfg) {
        RegionController c = new RegionController();
        ReflectionTestUtils.setField(c, "regionConfig", cfg);
        return MockMvcBuilders.standaloneSetup(c).build();
    }

    @Test
    void singleBoxConfig_keepsTheOriginalShape_withEmptyAreasAdded() throws Exception {
        RegionConfig cfg = new RegionConfig();
        cfg.setName("Rajahmundry");
        cfg.setBbox(box(16.96, 17.01, 81.76, 81.82));

        mvcFor(cfg).perform(get("/api/region"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.name").value("Rajahmundry"))
            .andExpect(jsonPath("$.center.lat").value(16.985))
            .andExpect(jsonPath("$.bbox.south").value(16.96))
            .andExpect(jsonPath("$.bbox.east").value(81.82))
            .andExpect(jsonPath("$.areas.length()").value(0))
            .andExpect(jsonPath("$.maxSnapMeters").value(0.0));
    }

    @Test
    void multiCityConfig_exposesTheOverallExtentAndEachArea() throws Exception {
        RegionConfig cfg = new RegionConfig();
        cfg.setName("Andhra coast");
        cfg.setBbox(box(16.30, 18.00, 80.40, 83.60));
        cfg.setMaxSnapMeters(3000);
        var vij = new RegionConfig.Area();
        vij.setName("Vijayawada");
        vij.setBbox(box(16.43, 16.62, 80.52, 80.78));
        cfg.getAreas().add(vij);

        mvcFor(cfg).perform(get("/api/region"))
            .andExpect(jsonPath("$.bbox.north").value(18.0))
            .andExpect(jsonPath("$.areas[0].name").value("Vijayawada"))
            .andExpect(jsonPath("$.areas[0].bbox.west").value(80.52))
            .andExpect(jsonPath("$.areas[0].center.lng").value(80.65))
            .andExpect(jsonPath("$.maxSnapMeters").value(3000.0));
    }
}
