package com.example.starter.tf2.domain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link RigidTransform} 复合、求逆与插值测试。
 */
class RigidTransformTest {

    private static final double EPS = 1e-9;

    @Test
    void composeAppliesTranslationAndRotation() {
        // T1：绕 Z 90°，平移 (10,0,0)
        RigidTransform t1 = new RigidTransform(
                new Vec3(10, 0, 0),
                new Quat(0, 0, Math.sin(Math.PI / 4), Math.cos(Math.PI / 4)));
        // T2：平移 (1,0,0)，无旋转
        RigidTransform t2 = new RigidTransform(new Vec3(1, 0, 0), Quat.IDENTITY);

        RigidTransform composed = t1.compose(t2);
        // t = (10,0,0) + Rz90*(1,0,0) = (10,1,0)
        assertEquals(10.0, composed.translation().x(), EPS);
        assertEquals(1.0, composed.translation().y(), EPS);
        assertEquals(0.0, composed.translation().z(), EPS);
    }

    @Test
    void inverseRoundTripsToIdentity() {
        RigidTransform t = new RigidTransform(
                new Vec3(3, -2, 5),
                new Quat(0.1, 0.2, 0.3, 0.9).normalized());
        RigidTransform roundTrip = t.compose(t.inverse());
        assertEquals(0.0, roundTrip.translation().x(), EPS);
        assertEquals(0.0, roundTrip.translation().y(), EPS);
        assertEquals(0.0, roundTrip.translation().z(), EPS);
        assertEquals(Quat.IDENTITY.w(), roundTrip.rotation().w(), EPS);
    }

    @Test
    void interpolateMidpointAveragesTranslation() {
        RigidTransform a = new RigidTransform(new Vec3(0, 0, 0), Quat.IDENTITY);
        RigidTransform b = new RigidTransform(
                new Vec3(10, 20, 30),
                new Quat(0, 0, Math.sin(Math.PI / 4), Math.cos(Math.PI / 4)));

        RigidTransform mid = RigidTransform.interpolate(a, b, 0.5);
        assertEquals(5.0, mid.translation().x(), EPS);
        assertEquals(10.0, mid.translation().y(), EPS);
        assertEquals(15.0, mid.translation().z(), EPS);
        // 旋转中点为 45°
        assertEquals(Math.sin(Math.PI / 8), mid.rotation().z(), EPS);
    }
}
