package com.example.starter.api.dto;

import java.util.List;

/**
 * 冲突查询/审批拒绝响应体。
 */
public record ConflictsResponse(
        String requestId,
        boolean conflict,
        List<ConflictView> conflicts
) {
}
