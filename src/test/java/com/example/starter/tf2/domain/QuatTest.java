package com.example.starter.tf2.domain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link Quat} 球面插值与旋转运算测试。
 */
class QuatTest {

    private static final double EPS = 1e-9;

    @Test
    void slerpEndpointsReturnInputs() {
        // 绕 Z 轴 0° 与 90°
        Quat a = Quat.IDENTITY;
        Quat b = new Quat(0, 0, Math.sin(Math.PI / 4), Math.cos(Math.PI / 4));

        assertTrue(quatEquals(a, Quat.slerp(a, b, 0.0)));
        assertTrue(quatEquals(b, Quat.slerp(a, b, 1.0)));
    }

    @Test
    void slerpMidpointIsHalfAngle() {
        Quat a = Quat.IDENTITY;
        Quat b = new Quat(0, 0, Math.sin(Math.PI / 4), Math.cos(Math.PI / 4));

        Quat mid = Quat.slerp(a, b, 0.5);
        // 中点应为绕 Z 轴 45°
        assertEquals(Math.sin(Math.PI / 8), mid.z(), 1e-9);
        assertEquals(Math.cos(Math.PI / 8), mid.w(), 1e-9);
        assertEquals(0.0, mid.x(), EPS);
        assertEquals(0.0, mid.y(), EPS);
    }

    @Test
    void slerpTakesShortArcWhenDotNegative() {
        // b 与 -b 表示同一旋转，短弧插值不应绕远路
        Quat a = Quat.IDENTITY;
        Quat b = new Quat(0, 0, Math.sin(Math.PI / 4), Math.cos(Math.PI / 4));
        Quat bNeg = new Quat(0, 0, -Math.sin(Math.PI / 4), -Math.cos(Math.PI / 4));

        Quat viaB = Quat.slerp(a, b, 0.5);
        assertSameRotation(viaB, Quat.slerp(a, bNeg, 0.5));
    }

    private void assertSameRotation(Quat expected, Quat actual) {
        // 表示同一旋转即可（双覆盖）
        assertTrue(expected.nearlyEquals(actual, 1e-9),
                () -> "expected=" + expected + " actual=" + actual);
    }

    @Test
    void rotateMapsAxesCorrectly() {
        // 绕 Z 轴 90°：x 轴 -> y 轴
        Quat q = new Quat(0, 0, Math.sin(Math.PI / 4), Math.cos(Math.PI / 4));
        Vec3 v = q.rotate(new Vec3(1, 0, 0));
        assertEquals(0.0, v.x(), EPS);
        assertEquals(1.0, v.y(), EPS);
        assertEquals(0.0, v.z(), EPS);
    }

    @Test
    void conjugateIsInverseForUnitQuaternion() {
        Quat q = new Quat(0.1, 0.2, 0.3, 0.9).normalized();
        Quat product = Quat.multiply(q, q.conjugate());
        assertEquals(Quat.IDENTITY.x(), product.x(), EPS);
        assertEquals(Quat.IDENTITY.y(), product.y(), EPS);
        assertEquals(Quat.IDENTITY.z(), product.z(), EPS);
        assertEquals(Quat.IDENTITY.w(), product.w(), EPS);
    }

    private static boolean quatEquals(Quat a, Quat b) {
        return a.nearlyEquals(b, EPS);
    }
}
