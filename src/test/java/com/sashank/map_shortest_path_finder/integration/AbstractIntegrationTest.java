package com.sashank.map_shortest_path_finder.integration;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

import java.nio.file.Path;

/**
 * Base class for integration tests that need a real PostgreSQL + PostGIS database,
 * as opposed to a mock repository or a hand-crafted in-memory map.
 *
 * Spins up a postgis/postgis container and runs the project's actual
 * docker/postgres/init.sql against it on startup — the same script
 * docker-compose.yml feeds to the dev database — so these tests exercise the real
 * schema (nodes, edges, GiST index, geom trigger) instead of a hand-copied one
 * that could silently drift from it.
 *
 * Container lifecycle — deliberately NOT a {@code @Container}-annotated field:
 *   The natural-looking {@code @Container static PostgreSQLContainer<?> postgres}
 *   pattern stops the container in an {@code afterAll()} once the FIRST subclass's
 *   tests finish, because the static field is shared by every subclass but the
 *   JUnit5 Testcontainers extension's start/stop hooks fire per test class. The
 *   second subclass would then run against a container that's already been torn
 *   down. Starting it once in a static initializer instead (the documented
 *   "singleton container" pattern) and never stopping it explicitly avoids that —
 *   Testcontainers' Ryuk reaper kills it when the JVM exits.
 *
 * {@code @SpringBootTest} boots the real application context (no mocks) wired to
 * this container via {@code @DynamicPropertySource}, which overrides
 * application.properties' datasource URL with the container's.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
public abstract class AbstractIntegrationTest {

    private static final Path INIT_SQL =
        Path.of(System.getProperty("user.dir"), "docker", "postgres", "init.sql");

    static final PostgreSQLContainer<?> POSTGRES =
        new PostgreSQLContainer<>(DockerImageName.parse("postgis/postgis:17-3.5")
                .asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("mapdb")
            .withUsername("postgres")
            .withPassword("password")
            .withCopyFileToContainer(MountableFile.forHostPath(INIT_SQL), "/docker-entrypoint-initdb.d/init.sql");

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void registerDatasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }
}
