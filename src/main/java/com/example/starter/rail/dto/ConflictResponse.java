package com.example.starter.rail.dto;

import com.example.starter.rail.model.ConflictDetail;

import java.util.List;

/**
 * 审批失败时返回的冲突明细响应，明细按区段与时间排序。
 */
public record ConflictResponse(
        String requestId,
        boolean approved,
        List<ConflictDetail> conflicts
) {
}
