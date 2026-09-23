package com.example.starter.calibration.api;

import java.time.Instant;
import java.util.List;

/**
 * 状态流转审计视图。
 */
public record TransitionResponse(
        long id,
        long versionId,
        String fromStatus,
        String toStatus,
        String operator,
        Instant operatedAt,
        String remark) {

    /** 转换为审计视图列表由服务层完成。 */
    public static List<TransitionResponse> none() {
        return List.of();
    }
}
