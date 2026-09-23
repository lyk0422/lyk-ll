package com.example.starter.specimen.web.dto;

import com.example.starter.specimen.model.AssayStatus;

import java.math.BigDecimal;

/**
 * 检测申请响应：result 在未出结果或已取消时为 null。
 */
public record AssayResponse(
        String assayId,
        String aliquotId,
        String project,
        BigDecimal reservedVolumeUl,
        AssayStatus status,
        String result,
        String custodian,
        long version) {
}
