package com.example.starter.lidar.spatial;

import com.example.starter.lidar.model.BBox;
import com.example.starter.lidar.model.QueryType;
import com.example.starter.lidar.model.SpatialQueryRequest;
import com.example.starter.lidar.model.Vec3;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 三类空间形状的闭区间边界判定测试。
 */
class SpatialShapeTest {

    @Test
    void boxIncludesAllSixFacesAndExcludesOutside() {
        SpatialShape shape = SpatialShape.from(new SpatialQueryRequest(
                QueryType.BOX,
                new BBox(0, 0, 0, 10, 10, 10),
                null, null, null, null, null, null));

        // 六个面上的边界点必须包含
        assertTrue(shape.contains(0, 5, 5));
        assertTrue(shape.contains(10, 5, 5));
        assertTrue(shape.contains(5, 0, 5));
        assertTrue(shape.contains(5, 10, 5));
        assertTrue(shape.contains(5, 5, 0));
        assertTrue(shape.contains(5, 5, 10));
        // 八个角点包含
        assertTrue(shape.contains(0, 0, 0));
        assertTrue(shape.contains(10, 10, 10));
        // 面外（任何极小超出）排除
        assertFalse(shape.contains(-0.000001, 5, 5));
        assertFalse(shape.contains(10.000001, 5, 5));
        assertFalse(shape.contains(5, 5, 10.000001));
    }

    @Test
    void radiusIncludesSphereSurface() {
        SpatialShape shape = SpatialShape.from(new SpatialQueryRequest(
                QueryType.RADIUS,
                null,
                new Vec3(1, 2, 3), 5.0,
                null, null, null, null));

        // 球心包含
        assertTrue(shape.contains(1, 2, 3));
        // 各坐标轴方向球面点（距离恰好为半径）必须包含
        assertTrue(shape.contains(6, 2, 3));
        assertTrue(shape.contains(-4, 2, 3));
        assertTrue(shape.contains(1, 7, 3));
        assertTrue(shape.contains(1, 2, -2));
        // 球面外一点排除
        assertFalse(shape.contains(6 + 1e-9, 2, 3));
        // 对角方向 sqrt(3^2*3)=sqrt(27)≈5.196 > 5 排除
        assertFalse(shape.contains(4, 5, 6));
    }

    @Test
    void corridorIncludesSurfaceCapsAndExcludesOutside() {
        // 沿 X 轴折线 (0,0,0)->(10,0,0)，宽度 2（半宽 1）
        SpatialShape shape = SpatialShape.from(new SpatialQueryRequest(
                QueryType.CORRIDOR,
                null, null, null,
                List.of(new Vec3(0, 0, 0), new Vec3(10, 0, 0)),
                2.0, null, null));

        // 中线上的点包含（含两端点）
        assertTrue(shape.contains(0, 0, 0));
        assertTrue(shape.contains(5, 0, 0));
        assertTrue(shape.contains(10, 0, 0));
        // 圆柱面（垂直距离恰为半宽）必须包含
        assertTrue(shape.contains(5, 1, 0));
        assertTrue(shape.contains(5, 0, -1));
        // 端点球帽：起点沿反方向 1 处恰在帽面，包含；1.0001 排除
        assertTrue(shape.contains(-1, 0, 0));
        assertFalse(shape.contains(-1.000001, 0, 0));
        // 终点外帽面
        assertTrue(shape.contains(11, 0, 0));
        assertFalse(shape.contains(11, 0.000001, 0));
        // 侧面外排除
        assertFalse(shape.contains(5, 1.000001, 0));
    }

    @Test
    void corridorBendsAtVertex() {
        // L 形折线：(0,0,0)->(5,0,0)->(5,5,0)，宽度 2
        SpatialShape shape = SpatialShape.from(new SpatialQueryRequest(
                QueryType.CORRIDOR,
                null, null, null,
                List.of(new Vec3(0, 0, 0), new Vec3(5, 0, 0), new Vec3(5, 5, 0)),
                2.0, null, null));

        // 拐角附近，两段各自管外的对角方向：点 (5,1,1) 距竖段 1，包含
        assertTrue(shape.contains(5, 1, 1));
        // 远离折线的点排除
        assertFalse(shape.contains(0, 5, 0));
    }
}
