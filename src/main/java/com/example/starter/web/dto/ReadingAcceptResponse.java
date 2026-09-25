package com.example.starter.web.dto;

/**
 * 日常温湿度上报接收结果。归档后上报只登记迟到事件（late=true）。
 */
public record ReadingAcceptResponse(
        String siloCode,
        int received,
        boolean late,
        String state
) {
}
