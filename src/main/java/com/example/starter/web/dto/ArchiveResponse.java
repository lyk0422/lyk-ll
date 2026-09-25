package com.example.starter.web.dto;

/**
 * 归档结果。
 *
 * @param archived        是否处于已归档终态
 * @param alreadyArchived 本次调用前是否已归档（重复归档返回幂等成功）
 */
public record ArchiveResponse(
        String requestId,
        String siloCode,
        String state,
        boolean archived,
        boolean alreadyArchived
) {
}
