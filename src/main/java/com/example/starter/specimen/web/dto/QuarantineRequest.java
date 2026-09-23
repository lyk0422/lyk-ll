package com.example.starter.specimen.web.dto;

import jakarta.validation.constraints.PositiveOrZero;

/**
 * 污染隔离请求：把分装及其未开始申请原子转为 QUARANTINED/CANCELLED；reason 可空。
 */
public record QuarantineRequest(
        @PositiveOrZero long expectedVersion,
        String reason) {
}
