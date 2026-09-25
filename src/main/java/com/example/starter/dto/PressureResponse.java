package com.example.starter.dto;

/**
 * 风压上报响应: late=true 表示销记后迟到数据, 仅记迟到事件。
 */
public record PressureResponse(long operationId, boolean recorded, boolean late) {
}
