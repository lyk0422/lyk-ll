package com.example.starter.trajectory.engine;

import com.example.starter.trajectory.domain.Vec;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GeometryTest {

    private final List<Vec> square = List.of(
            new Vec(0, 0), new Vec(10, 0), new Vec(10, 10), new Vec(0, 10));

    @Test
    void distanceToSegmentCoversInteriorAndEndpoints() {
        Vec a = new Vec(0, 0);
        Vec b = new Vec(10, 0);
        assertEquals(3.0, Geometry.distanceToSegment(new Vec(5, 3), a, b), 1e-9);
        assertEquals(2.0, Geometry.distanceToSegment(new Vec(-2, 0), a, b), 1e-9);
        assertEquals(2.0, Geometry.distanceToSegment(new Vec(12, 0), a, b), 1e-9);
        assertEquals(0.0, Geometry.distanceToSegment(new Vec(7, 0), a, b), 1e-9);
    }

    @Test
    void pointOnBoundaryIsInside() {
        assertTrue(Geometry.isInsideOrOnBoundary(new Vec(5, 0), square));
        assertTrue(Geometry.isInsideOrOnBoundary(new Vec(10, 5), square));
        assertTrue(Geometry.isInsideOrOnBoundary(new Vec(0, 0), square));
    }

    @Test
    void pointInsidePolygonIsInside() {
        assertTrue(Geometry.isInsideOrOnBoundary(new Vec(5, 5), square));
    }

    @Test
    void pointOutsidePolygonIsOutside() {
        assertFalse(Geometry.isInsideOrOnBoundary(new Vec(5, 11), square));
        assertFalse(Geometry.isInsideOrOnBoundary(new Vec(-1, 5), square));
    }

    @Test
    void distanceToPolygonIsShortestToEdges() {
        assertEquals(1.0, Geometry.distanceToPolygon(new Vec(5, 11), square), 1e-9);
        assertEquals(2.0, Geometry.distanceToPolygon(new Vec(12, 5), square), 1e-9);
    }
}
