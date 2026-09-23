package com.example.starter.calibration.web.dto;

import java.time.Instant;
import java.util.List;

import com.example.starter.calibration.domain.CanaryRelease;
import com.example.starter.calibration.domain.ReleaseStatus;

/**
 * 灰度发布单响应。
 *
 * @param previousVersions 每辆车发布前版本 ID（null 表示此前无有效版本），与 vehicles 一一对应
 */
public record CanaryReleaseResponse(
        Long releaseId,
        String key,
        Long newVersionId,
        String newVersion,
        ReleaseStatus status,
        String operator,
        Instant createdAt,
        List<String> vehicles,
        List<Long> previousVersions,
        String rollbackOperator,
        Instant rolledBackAt,
        String reason) {

    /** 组装响应。 */
    public static CanaryReleaseResponse of(CanaryRelease release, String newVersion,
            List<String> vehicles, List<Long> previousVersions) {
        return new CanaryReleaseResponse(
                release.getId(), release.getCalibrationKey(), release.getNewVersionId(),
                newVersion, release.getStatus(), release.getOperator(), release.getCreatedAt(),
                List.copyOf(vehicles), List.copyOf(previousVersions),
                release.getRollbackOperator(), release.getRolledBackAt(), release.getReason());
    }
}
