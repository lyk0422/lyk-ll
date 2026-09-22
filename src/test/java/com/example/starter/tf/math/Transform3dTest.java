package com.example.starter.tf.math;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link Transform3d} 的刚体代数与球面插值单元测试。
 */
class Transform3dTest {

    private static final double EPS = 1.0e-9;

    private static void assertTransformEquals(Transform3d expected, Transform3d actual, double eps) {
        assertEquals(expected.tx(), actual.tx(), eps, "tx");
        assertEquals(expected.ty(), actual.ty(), eps, "ty");
        assertEquals(expected.tz(), actual.tz(), eps, "tz");
        assertEquals(expected.qx(), actual.qx(), eps, "qx");
        assertEquals(expected.qy(), actual.qy(), eps, "qy");
        assertEquals(expected.qz(), actual.qz(), eps, "qz");
        assertEquals(expected.qw(), actual.qw(), eps, "qw");
    }

    @Test
    void identityLeavesTransformUnchanged() {
        Transform3d t = new Transform3d(3, -2, 5, 0, 0, 0, 1);
        assertTransformEquals(t, Transform3d.identity().multiply(t), EPS);
        assertTransformEquals(t, t.multiply(Transform3d.identity()), EPS);
    }

    @Test
    void composesTranslations() {
        Transform3d a = new Transform3d(1, 2, 3, 0, 0, 0, 1);
        Transform3d b = new Transform3d(10, 20, 30, 0, 0, 0, 1);
        Transform3d c = a.multiply(b);
        assertTransformEquals(new Transform3d(11, 22, 33, 0, 0, 0, 1), c, EPS);
    }

    @Test
    void inverseOfTranslationNegatesComponents() {
        Transform3d t = new Transform3d(1, 2, 3, 0, 0, 0, 1);
        Transform3d inv = t.inverse();
        assertTransformEquals(new Transform3d(-1, -2, -3, 0, 0, 0, 1), inv, EPS);
        assertTransformEquals(Transform3d.identity(), t.multiply(inv), EPS);
        assertTransformEquals(Transform3d.identity(), inv.multiply(t), EPS);
    }

    @Test
    void rotationAroundZByQuarterTurnMapsXToY() {
        // 绕 Z 轴旋转 90°：q = (0,0,sin45°,cos45°)
        double s = Math.sin(Math.PI / 4);
        Transform3d q = new Transform3d(0, 0, 0, 0, 0, s, s);
        Transform3d point = new Transform3d(1, 0, 0, 0, 0, 0, 1);
        Transform3d rotated = q.multiply(point);
        assertEquals(0.0, rotated.tx(), EPS, "tx");
        assertEquals(1.0, rotated.ty(), EPS, "ty");
        assertEquals(0.0, rotated.tz(), EPS, "tz");
    }

    @Test
    void inverseUndoesRotationAndTranslation() {
        double s = Math.sin(Math.PI / 8);
        Transform3d t = new Transform3d(2, -3, 4, 0, 0, s, Math.cos(Math.PI / 8)).normalized();
        assertTransformEquals(Transform3d.identity(), t.multiply(t.inverse()), EPS);
        assertTransformEquals(Transform3d.identity(), t.inverse().multiply(t), EPS);
    }

    @Test
    void slerpMidpointIsHalfwayRotationAndTranslation() {
        double half = Math.sin(Math.PI / 4);
        Transform3d a = new Transform3d(0, 0, 0, 0, 0, 0, 1);
        Transform3d b = new Transform3d(10, 0, 0, 0, 0, half, half); // 绕 Z 90°
        Transform3d mid = Transform3d.interpolate(a, b, 0.5);

        assertEquals(5.0, mid.tx(), EPS, "中点平移 X");
        assertEquals(0.0, mid.ty(), EPS, "中点平移 Y");
        // 中点为绕 Z 45°
        assertEquals(0.0, mid.qx(), EPS, "qx");
        assertEquals(0.0, mid.qy(), EPS, "qy");
        assertEquals(Math.sin(Math.PI / 8), mid.qz(), EPS, "qz 45°");
        assertEquals(Math.cos(Math.PI / 8), mid.qw(), EPS, "qw 45°");
    }

    @Test
    void slerpTakesShortArc() {
        // 两个表示接近相反的四元数编码相同旋转族；短弧插值不应绕远路
        Transform3d a = new Transform3d(0, 0, 0, 0, 0, 0, 1);
        Transform3d b = new Transform3d(0, 0, 0, 0, 0, -halfAngle(), -halfAngleW());
        Transform3d mid = Transform3d.interpolate(a, b, 0.5);
        // 短弧结果应接近单位旋转附近的小角，而非接近 180°
        assertTrue(mid.qw() > 0.9, "短弧中点 w 应接近 1，实际 " + mid.qw());
    }

    private static double halfAngle() {
        return Math.sin(Math.PI / 4);
    }

    private static double halfAngleW() {
        return Math.cos(Math.PI / 4);
    }

    @Test
    void interpolateEndpointsReturnInputs() {
        Transform3d a = new Transform3d(1, 1, 1, 0, 0, 0, 1);
        Transform3d b = new Transform3d(2, 2, 2, 0, 0, 1, 0).normalized();
        assertTransformEquals(a, Transform3d.interpolate(a, b, 0.0), EPS);
        assertTransformEquals(b, Transform3d.interpolate(a, b, 1.0), EPS);
    }

    @Test
    void normalizedProducesUnitQuaternion() {
        Transform3d t = new Transform3d(0, 0, 0, 0, 0, 1, 1).normalized();
        double norm = Math.sqrt(t.qx() * t.qx() + t.qy() * t.qy()
                + t.qz() * t.qz() + t.qw() * t.qw());
        assertEquals(1.0, norm, EPS);
    }
}
