package com.example.starter.rt.dto;

/**
 * 医师批准结果。
 *
 * @param planVersionId 计划版本ID
 * @param versionNo     版本号
 * @param status        批准后状态 APPROVED
 * @param requestId     幂等键
 * @param replayed      是否为同键同参重放
 */
public record ApprovalResponse(
        Long planVersionId,
        Integer versionNo,
        String status,
        String requestId,
        boolean replayed) {
}
