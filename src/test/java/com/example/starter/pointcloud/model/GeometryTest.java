package com.example.starter.pointcloud.model;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 包围盒与轨迹走廊的闭区间几何语义测试。
 */
class GeometryTest {

    @Test
    void boxesIntersectOnFaceContact() {
        BoundingBox a = new BoundingBox(0, 0, 0, 1, 1, 1);
        BoundingBox touching = new BoundingBox(1, 0, 0, 2, 1, 1);
        BoundingBox separated = new BoundingBox(1.0001, 0, 0, 2, 1, 1);

        assertTrue(a.intersects(touching), "面接触必须视为相交");
        assertFalse(a.intersects(separated));
    }

    @Test
    void corridorContainsPointOnWidthBoundary() {
        Corridor corridor = new Corridor(
                List.of(new Vec3(0, 0, 0), new Vec3(10, 0, 0)), 1.0);

        assertTrue(corridor.contains(5, 1.0, 0), "宽度边界上的点必须包含");
        assertTrue(corridor.contains(5, -1.0, 0));
        assertTrue(corridor.contains(5, 0, 0));
        assertFalse(corridor.contains(5, 1.0001, 0));
    }

    @Test
    void corridorEndpointsAreClampedWhenPointProjectsBeyondSegment() {
        Corridor corridor = new Corridor(
                List.of(new Vec3(0, 0, 0), new Vec3(10, 0, 0)), 1.0);

        // 投影超出终点时按端点计算距离：(11,0,0) 距终点恰为 1，必须包含
        assertTrue(corridor.contains(11, 0, 0));
        assertFalse(corridor.contains(12, 0, 0));
        // 折线由多段组成，中间衔接处附近的点按最近线段判定
        Corridor bent = new Corridor(List.of(
                new Vec3(0, 0, 0), new Vec3(10, 0, 0), new Vec3(10, 10, 0)), 0.5);
        assertTrue(bent.contains(10, 0.5, 0));
        assertTrue(bent.contains(10.5, 5, 0));
        assertFalse(bent.contains(20, 20, 0));
    }

    @Test
    void corridorBoundingBoxExpandsByHalfWidth() {
        Corridor corridor = new Corridor(
                List.of(new Vec3(0, 0, 0), new Vec3(10, 5, 2)), 1.0);
        BoundingBox bbox = corridor.boundingBox();
        assertTrue(bbox.minX() <= -1.0);
        assertTrue(bbox.maxX() >= 11.0);
        assertTrue(bbox.maxY() >= 6.0);
        assertTrue(bbox.maxZ() >= 3.0);
    }
}
