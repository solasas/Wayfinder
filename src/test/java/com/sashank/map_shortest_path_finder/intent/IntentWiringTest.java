package com.sashank.map_shortest_path_finder.intent;

import com.sashank.map_shortest_path_finder.config.RegionConfig;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Guards against a startup failure that hand-built unit-test objects can't see: Spring must be able
 * to pick the production constructors using only beans that exist. Spring Boot 4 does not
 * auto-configure a RestClient.Builder bean without the separate spring-boot-restclient module, so
 * NominatimPlaceResolver builds its own client; injecting one would break startup of the whole app.
 * (The parser's wiring against the real Spring AI autoconfiguration is covered in
 * SpringAiProviderIntegrationTest.)
 */
class IntentWiringTest {

    @Test
    void placeResolver_resolvesItsConstructorFromAPlainSpringContext() {
        try (var ctx = new AnnotationConfigApplicationContext()) {
            ctx.registerBean(IntentConfig.class);
            ctx.registerBean(RegionConfig.class);
            ctx.register(NominatimPlaceResolver.class);
            ctx.refresh();
            assertNotNull(ctx.getBean(PlaceResolver.class));
        }
    }
}
