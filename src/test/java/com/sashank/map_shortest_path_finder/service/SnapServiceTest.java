package com.sashank.map_shortest_path_finder.service;

import com.sashank.map_shortest_path_finder.config.RegionConfig;
import com.sashank.map_shortest_path_finder.model.Node;
import com.sashank.map_shortest_path_finder.repository.NodeRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for SnapService.
 *
 * We mock NodeRepository (the PostGIS call) and RegionConfig (the bbox check)
 * so these tests are fast, Spring-free, and don't need a running database.
 *
 * Mockito's @InjectMocks creates a SnapService and injects the @Mock fields
 * into it automatically, matching by field type.
 */
@ExtendWith(MockitoExtension.class)
class SnapServiceTest {

    @Mock private NodeRepository nodeRepository;
    @Mock private RegionConfig regionConfig;

    @InjectMocks private SnapService snapService;

    private RegionConfig.Bbox rajahmundryBbox;

    @BeforeEach
    void setUp() {
        rajahmundryBbox = new RegionConfig.Bbox();
        rajahmundryBbox.setSouth(16.96);
        rajahmundryBbox.setNorth(17.01);
        rajahmundryBbox.setWest(81.76);
        rajahmundryBbox.setEast(81.82);

        when(regionConfig.getBbox()).thenReturn(rajahmundryBbox);
        // regionConfig.getName() is only called inside the out-of-region error message,
        // so it's stubbed per-test where needed (Mockito strict mode flags unused stubs).
    }

    @Test
    void returnsNearestNodeForValidCoordinates() {
        Node expected = Node.builder().id(42L).lat(16.975).lng(81.778).build();
        when(nodeRepository.findNearestTo(16.975, 81.778)).thenReturn(Optional.of(expected));

        Node result = snapService.snapToNearest(16.975, 81.778);

        assertSame(expected, result);
        verify(nodeRepository).findNearestTo(16.975, 81.778);
    }

    @Test
    void throwsIllegalArgumentWhenOutsideRegion() {
        when(regionConfig.getName()).thenReturn("Rajahmundry");

        // Chennai is nowhere near Rajahmundry
        assertThrows(IllegalArgumentException.class,
            () -> snapService.snapToNearest(13.08, 80.27));

        verifyNoInteractions(nodeRepository); // DB should never be called
    }

    @Test
    void throwsIllegalStateWhenGraphIsEmpty() {
        when(nodeRepository.findNearestTo(anyDouble(), anyDouble()))
            .thenReturn(Optional.empty());

        assertThrows(IllegalStateException.class,
            () -> snapService.snapToNearest(16.98, 81.78));
    }
}
