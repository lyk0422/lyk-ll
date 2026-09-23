package com.example.starter.calibration.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * 范围匹配与三维重叠判定单元测试。
 */
class RangeMatcherTest {

    private static CalibrationVersion version(Instant from, Instant to, VehicleScope scope,
            Integer mapFrom, Integer mapTo) {
        CalibrationVersion v = new CalibrationVersion();
        v.setId(1L);
        v.setEffectiveFrom(from);
        v.setEffectiveTo(to);
        v.setVehicleScope(scope);
        v.setMapVersionFrom(mapFrom);
        v.setMapVersionTo(mapTo);
        return v;
    }

    @Test
    void timeWindowIsHalfOpen() {
        Instant from = Instant.parse("2026-01-01T00:00:00Z");
        Instant to = Instant.parse("2026-02-01T00:00:00Z");
        CalibrationVersion v = version(from, to, new VehicleScope.All(), null, null);
        assertThat(RangeMatcher.timeMatches(v, from)).isTrue();
        assertThat(RangeMatcher.timeMatches(v, to)).isFalse();
        assertThat(RangeMatcher.timeMatches(v, Instant.parse("2026-01-15T00:00:00Z"))).isTrue();
    }

    @Test
    void openEndedTimeWindowMatchesFuture() {
        CalibrationVersion v = version(Instant.parse("2020-01-01T00:00:00Z"), null,
                new VehicleScope.All(), null, null);
        assertThat(RangeMatcher.timeMatches(v, Instant.parse("2099-01-01T00:00:00Z"))).isTrue();
    }

    @Test
    void mapRangeBoundariesAreInclusive() {
        CalibrationVersion v = version(Instant.EPOCH, null, new VehicleScope.All(), 1, 10);
        assertThat(RangeMatcher.mapMatches(v, 1)).isTrue();
        assertThat(RangeMatcher.mapMatches(v, 10)).isTrue();
        assertThat(RangeMatcher.mapMatches(v, 11)).isFalse();
    }

    @Test
    void unspecifiedMapVersionOnlyMatchesUnboundedVersion() {
        CalibrationVersion bounded = version(Instant.EPOCH, null, new VehicleScope.All(), 1, 10);
        CalibrationVersion unbounded = version(Instant.EPOCH, null, new VehicleScope.All(), null, null);
        assertThat(RangeMatcher.mapMatches(bounded, null)).isFalse();
        assertThat(RangeMatcher.mapMatches(unbounded, null)).isTrue();
    }

    @Test
    void vehicleScopesOverlapRules() {
        VehicleScope all = new VehicleScope.All();
        VehicleScope ab = new VehicleScope.Vehicles(List.of("a", "b"));
        VehicleScope cd = new VehicleScope.Vehicles(List.of("c", "d"));
        assertThat(RangeMatcher.vehicleScopesOverlap(all, cd)).isTrue();
        assertThat(RangeMatcher.vehicleScopesOverlap(ab, cd)).isFalse();
        assertThat(RangeMatcher.vehicleScopesOverlap(ab, new VehicleScope.Vehicles(List.of("b", "x"))))
                .isTrue();
    }

    @Test
    void scopesOverlapRequiresAllThreeDimensions() {
        Instant t1 = Instant.parse("2026-01-01T00:00:00Z");
        Instant t2 = Instant.parse("2026-02-01T00:00:00Z");
        Instant t3 = Instant.parse("2026-03-01T00:00:00Z");
        CalibrationVersion sameTimeSameVehiclesDifferentMap =
                version(t1, t3, new VehicleScope.Vehicles(List.of("a")), 1, 5);
        CalibrationVersion otherMap =
                version(t1, t3, new VehicleScope.Vehicles(List.of("a")), 6, 10);
        assertThat(RangeMatcher.scopesOverlap(sameTimeSameVehiclesDifferentMap, otherMap)).isFalse();

        CalibrationVersion disjointTime =
                version(t2, t3, new VehicleScope.Vehicles(List.of("a")), 1, 5);
        assertThat(RangeMatcher.scopesOverlap(sameTimeSameVehiclesDifferentMap, disjointTime)).isTrue();

        CalibrationVersion otherVehicles =
                version(t1, t3, new VehicleScope.Vehicles(List.of("z")), 1, 5);
        assertThat(RangeMatcher.scopesOverlap(sameTimeSameVehiclesDifferentMap, otherVehicles)).isFalse();
    }
}
