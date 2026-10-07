package com.example.starter.web.dto;

/**
 * 续行许可响应；成功仅结束本次阻断，保留始发与途中两条证据。
 *
 * @param dispositionId 处置单ID
 * @param jobId         所属作业ID
 * @param resumed       是否已准许续行
 * @param version       处置版本（本次修改后）
 * @param message       结论说明
 */
public record EnRouteResumeResponse(
        long dispositionId,
        long jobId,
        boolean resumed,
        int version,
        String message) {
}
