package com.example.starter.web.dto;

/**
 * 发车确认响应。
 *
 * @param jobId   作业ID
 * @param approved 是否准许发车
 * @param message 说明
 */
public record DepartureResponse(
        long jobId,
        boolean approved,
        String message) {
}
