package com.example.starter.web.dto;

/**
 * 改派结果。
 */
public record ReassignmentResponse(
        String requestId,
        OrderResponse order,
        String oldGenCode,
        String newGenCode
) {
}
