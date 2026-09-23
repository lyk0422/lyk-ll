package com.example.starter.calibration.web.dto;

import com.example.starter.calibration.domain.ResolutionRecord;

/**
 * 参数解析响应：返回命中版本内容与可追溯 resolutionId。
 */
public record ResolveResponse(
        String resolutionId,
        String key,
        String sensorId,
        String vehicleId,
        Integer mapVersion,
        Long selectedVersionId,
        String selectedVersion,
        String contentDigest,
        String payload,
        Long canaryReleaseId,
        boolean canaryHit) {

    public static ResolveResponse from(ResolutionRecord r) {
        return new ResolveResponse(
                r.getResolutionId(), r.getCalibrationKey(), r.getSensorId(), r.getVehicleId(),
                r.getMapVersion(), r.getSelectedVersionId(), r.getSelectedVersion(),
                r.getContentDigest(), r.getPayload(),
                r.getCanaryReleaseId(), r.getCanaryReleaseId() != null);
    }
}
