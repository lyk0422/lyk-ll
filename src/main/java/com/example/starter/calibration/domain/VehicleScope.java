package com.example.starter.calibration.domain;

import java.util.List;

/**
 * 车辆范围：全部车辆或显式车辆集合。
 */
public sealed interface VehicleScope permits VehicleScope.All, VehicleScope.Vehicles {

    /** 全部车辆。 */
    record All() implements VehicleScope {
    }

    /** 显式车辆集合，非空、去重。 */
    record Vehicles(List<String> vehicleIds) implements VehicleScope {
        public Vehicles {
            if (vehicleIds == null || vehicleIds.isEmpty()) {
                throw new IllegalArgumentException("显式车辆集合不能为空");
            }
            vehicleIds = vehicleIds.stream().distinct().sorted().toList();
        }
    }

    /** 序列化为库中存储格式：ALL 或 VEHICLES:v1,v2。 */
    default String encode() {
        if (this instanceof All) {
            return "ALL";
        }
        return "VEHICLES:" + String.join(",", ((Vehicles) this).vehicleIds());
    }

    /** 反序列化库中存储的车辆范围。 */
    static VehicleScope decode(String stored) {
        if (stored == null || stored.isBlank() || "ALL".equals(stored)) {
            return new All();
        }
        if (!stored.startsWith("VEHICLES:")) {
            throw new IllegalArgumentException("无法解析车辆范围: " + stored);
        }
        List<String> ids = List.of(stored.substring("VEHICLES:".length()).split(","));
        return new Vehicles(ids);
    }

    /** 判断车辆是否落在该范围内。 */
    default boolean contains(String vehicleId) {
        if (this instanceof All) {
            return true;
        }
        return ((Vehicles) this).vehicleIds().contains(vehicleId);
    }
}
