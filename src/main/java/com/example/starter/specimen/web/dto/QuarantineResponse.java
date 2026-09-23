package com.example.starter.specimen.web.dto;

import com.example.starter.specimen.model.AliquotStatus;

/**
 * 污染隔离响应：cancelledAssayCount 为被连带取消的未开始申请数量。
 */
public record QuarantineResponse(
        String aliquotId,
        AliquotStatus status,
        int cancelledAssayCount,
        long version) {
}
