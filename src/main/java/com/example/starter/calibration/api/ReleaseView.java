package com.example.starter.calibration.api;

import com.example.starter.calibration.domain.ReleaseStatus;

import java.time.Instant;
import java.util.List;

/**
 * 灰度发布单视图：vehicles 为显式车辆集合，previousVersions 为各车发布前版本。
 */
public record ReleaseView(
        String releaseId,
        String paramKey,
        String newVersion,
        Long paramId,
        List<String> vehicles,
        ReleaseStatus status,
        String createdBy,
        Instant createdAt,
        String finishedBy,
        Instant finishedAt,
        String remark,
        List<VehicleSnapshot> vehicleSnapshots
) {
    /**
     * 单车发布前版本快照。
     */
    public record VehicleSnapshot(String vehicleId, Long previousParamId, String previousVersion) {
    }
}
