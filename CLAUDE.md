# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Commands

```bash
# Run the application
./mvnw spring-boot:run

# Build (skip tests)
./mvnw package -DskipTests

# Run all tests
./mvnw test

# Run a single test class
./mvnw test -Dtest=MapShortestPathFinderApplicationTests

# Compile only
./mvnw compile
```

## Stack

- **Java 17**, Spring Boot 4.1.0
- **Spring Web MVC** — REST API layer
- **Spring Data JPA + PostgreSQL** — persistence
- **Lombok** — boilerplate reduction (`@Data`, `@Builder`, etc.)

## Architecture intent

This project is in its initial scaffolding stage. The goal is a map shortest-path finder service: a REST API that accepts graph/map inputs and returns shortest paths using algorithms (e.g. Dijkstra, A*).

Expected package structure under `com.sashank.map_shortest_path_finder`:
- `controller/` — REST endpoints
- `service/` — path-finding algorithm logic
- `model/` — JPA entities (nodes, edges)
- `repository/` — Spring Data JPA repositories
- `dto/` — request/response objects

## Database

PostgreSQL is the configured datasource. `application.properties` currently has no connection details — add `spring.datasource.url`, `spring.datasource.username`, and `spring.datasource.password` before running. The context-loads test (`MapShortestPathFinderApplicationTests`) will fail without a reachable database or a test slice that avoids full context startup.