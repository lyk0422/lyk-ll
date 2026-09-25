package com.example.starter.torque.dto;

/**
 * 检验签署结果。
 */
public record InspectionResult(
        String cardNo,
        String signType,
        String inspector,
        String cardStatus
) {
}
