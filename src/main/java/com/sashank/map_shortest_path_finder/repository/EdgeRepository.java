package com.sashank.map_shortest_path_finder.repository;

import com.sashank.map_shortest_path_finder.model.Edge;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface EdgeRepository extends JpaRepository<Edge, Long> {

    /**
     * Returns all edges leaving a given node — the adjacency list query that
     * Dijkstra calls on every node it relaxes.
     *
     * Spring Data JPA derives this query from the method name:
     *   findBy + FromNodeId  →  WHERE from_node_id = ?
     */
    List<Edge> findByFromNodeId(Long fromNodeId);
}
