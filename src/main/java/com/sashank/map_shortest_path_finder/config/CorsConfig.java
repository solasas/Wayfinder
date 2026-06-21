package com.sashank.map_shortest_path_finder.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;

/**
 * Allows the React frontend (localhost:3000 or localhost:5173) to call this API.
 *
 * Without this, browsers block cross-origin requests — your frontend would get
 * "Access to fetch has been blocked by CORS policy" errors in the console even
 * though the API itself works fine.
 */
@Configuration
public class CorsConfig {

    @Bean
    public CorsFilter corsFilter() {
        CorsConfiguration config = new CorsConfiguration();
        config.addAllowedOrigin("http://localhost:3000");  // Create React App
        config.addAllowedOrigin("http://localhost:5173");  // Vite
        config.addAllowedOrigin("http://localhost:5174");  // Vite (fallback port)
        config.addAllowedMethod("*");
        config.addAllowedHeader("*");

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", config);
        return new CorsFilter(source);
    }
}
