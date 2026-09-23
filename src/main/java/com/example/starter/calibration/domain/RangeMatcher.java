package com.example.starter.calibration.domain;

import java.time.Instant;

/**
 * 标定版本适用范围匹配与区间重叠判定。
 * 时间区间语义为左闭右开 [effectiveFrom, effectiveTo)；effectiveTo 为 null 表示长期有效。
 */
public final class RangeMatcher {

    private RangeMatcher() {
    }

    /** 判断版本在给定业务时间是否处于生效窗口内。 */
    public static boolean timeMatches(CalibrationVersion v, Instant time) {
        if (v.getEffectiveFrom().isAfter(time)) {
            return false;
        }
        return v.getEffectiveTo() == null || v.getEffectiveTo().isAfter(time);
    }

    /**
     * 判断版本是否覆盖指定地图版本。请求不区分地图版本（mapVersion 为 null）时，
     * 仅未设置地图边界的版本视为匹配，避免跨地图误用。
     */
    public static boolean mapMatches(CalibrationVersion v, Integer mapVersion) {
        if (mapVersion == null) {
            return v.getMapVersionFrom() == null && v.getMapVersionTo() == null;
        }
        if (v.getMapVersionFrom() != null && mapVersion < v.getMapVersionFrom()) {
            return false;
        }
        return v.getMapVersionTo() == null || mapVersion <= v.getMapVersionTo();
    }

    /** 判断版本是否覆盖指定车辆、地图版本与时间。 */
    public static boolean covers(CalibrationVersion v, String vehicleId, Integer mapVersion, Instant time) {
        return v.getVehicleScope().contains(vehicleId)
                && mapMatches(v, mapVersion)
                && timeMatches(v, time);
    }

    /** 两个车辆范围是否存在交集车辆。 */
    public static boolean vehicleScopesOverlap(VehicleScope a, VehicleScope b) {
        if (a instanceof VehicleScope.All || b instanceof VehicleScope.All) {
            return true;
        }
        return ((VehicleScope.Vehicles) a).vehicleIds().stream()
                .anyMatch(((VehicleScope.Vehicles) b).vehicleIds()::contains);
    }

    /** 两个整数闭区间是否重叠；null 端表示无界。 */
    public static boolean mapRangesOverlap(Integer fromA, Integer toA, Integer fromB, Integer toB) {
        int leftA = fromA == null ? Integer.MIN_VALUE : fromA;
        int rightA = toA == null ? Integer.MAX_VALUE : toA;
        int leftB = fromB == null ? Integer.MIN_VALUE : fromB;
        int rightB = toB == null ? Integer.MAX_VALUE : toB;
        return leftA <= rightB && leftB <= rightA;
    }

    /** 两个左闭右开时间区间是否重叠；null 结束时间表示长期有效。 */
    public static boolean timeRangesOverlap(Instant fromA, Instant toA, Instant fromB, Instant toB) {
        return fromA.isBefore(toB == null ? Instant.MAX : toB)
                && fromB.isBefore(toA == null ? Instant.MAX : toA);
    }

    /** 两个版本在车辆、地图、时间三个维度上是否都存在重叠。 */
    public static boolean scopesOverlap(CalibrationVersion a, CalibrationVersion b) {
        return vehicleScopesOverlap(a.getVehicleScope(), b.getVehicleScope())
                && mapRangesOverlap(a.getMapVersionFrom(), a.getMapVersionTo(),
                        b.getMapVersionFrom(), b.getMapVersionTo())
                && timeRangesOverlap(a.getEffectiveFrom(), a.getEffectiveTo(),
                        b.getEffectiveFrom(), b.getEffectiveTo());
    }
}
