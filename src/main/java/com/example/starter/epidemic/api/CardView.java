package com.example.starter.epidemic.api;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 报告卡查询视图，含完整版本链与审核轨迹，结果可稳定复算。
 */
public record CardView(
        String cardNumber,
        String patientId,
        String patientName,
        String diseaseCode,
        String onsetDate,
        String status,
        int currentVersion,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt,
        VersionView current,
        List<VersionView> versions,
        List<ReviewView> reviews
) {
}
