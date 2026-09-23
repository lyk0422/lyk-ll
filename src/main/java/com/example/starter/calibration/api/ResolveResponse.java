package com.example.starter.calibration.api;

import com.example.starter.calibration.domain.CalibrationParam;

import java.time.Instant;

/**
 * 参数解析结果：返回选中版本内容与可追溯 resolutionId。
 */
public record ResolveResponse(
        String resolutionId,
        String paramKey,
        String version,
        String status,
        String contentDigest,
        Object content,
        Instant resolveTime
) {
    public static ResolveResponse of(CalibrationParam p, String resolutionId, Object content, Instant resolveTime) {
        return new ResolveResponse(resolutionId, p.getParamKey(), p.getVersion(),
                p.getStatus().name(), p.getContentDigest(), content, resolveTime);
    }
}
