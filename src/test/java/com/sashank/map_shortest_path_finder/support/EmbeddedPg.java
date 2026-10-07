package com.sashank.map_shortest_path_finder.support;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

import javax.sql.DataSource;
import java.io.IOException;

/**
 * One real PostgreSQL per test JVM (started on first use, ~20 s, no Docker needed) for tests of the importer's and
 * graph loader's SQL.
 *
 * It has NO PostGIS: the schema below mirrors docker/postgres/init.sql minus the geom column/index/trigger, none of
 * which the importer or loader touches (the trigger fills geom server-side). The nearest-node KNN query is the one
 * piece that needs PostGIS and is covered by the Testcontainers-based SnapServiceIntegrationTest.
 */
public final class EmbeddedPg {

    private static EmbeddedPostgres instance;
    /**
     * ONE DataSource for everything. EmbeddedPostgres.getPostgresDatabase() returns a new object on every call, and a
     * transaction manager only enlists JdbcTemplates that use the very same DataSource instance — with two
     * instances "transactions" silently do nothing.
     */
    private static DataSource dataSource;

    private EmbeddedPg() {}

    public static synchronized DataSource dataSource() {
        if (instance == null) {
            try {
                instance = EmbeddedPostgres.start();
                Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                    try { instance.close(); } catch (IOException ignored) { /* JVM is exiting */ }
                }));
            } catch (IOException e) {
                throw new IllegalStateException("Could not start embedded PostgreSQL", e);
            }
        }
        if (dataSource == null) dataSource = instance.getPostgresDatabase();
        return dataSource;
    }

    public static JdbcTemplate jdbc() { return new JdbcTemplate(dataSource()); }

    public static DataSourceTransactionManager txManager() { return new DataSourceTransactionManager(dataSource()); }

    /** Drops and recreates the graph tables (same columns, types and constraints as init.sql, without PostGIS). */
    public static void freshSchema() {
        JdbcTemplate jdbc = jdbc();
        jdbc.execute("DROP TABLE IF EXISTS import_tiles, edges, nodes CASCADE");
        jdbc.execute("""
            CREATE TABLE nodes (
                id      BIGSERIAL PRIMARY KEY,
                osm_id  BIGINT UNIQUE NOT NULL,
                lat     DOUBLE PRECISION NOT NULL,
                lng     DOUBLE PRECISION NOT NULL
            )""");
        jdbc.execute("""
            CREATE TABLE edges (
                id              BIGSERIAL PRIMARY KEY,
                from_node_id    BIGINT NOT NULL REFERENCES nodes(id),
                to_node_id      BIGINT NOT NULL REFERENCES nodes(id),
                distance_meters DOUBLE PRECISION NOT NULL,
                speed_kmh       DOUBLE PRECISION NOT NULL,
                osm_way_id      BIGINT,
                highway         VARCHAR(32),
                toll            BOOLEAN,
                lit             BOOLEAN,
                paved           BOOLEAN
            )""");
        jdbc.execute("CREATE INDEX edges_from_node_idx ON edges (from_node_id)");
    }
}
