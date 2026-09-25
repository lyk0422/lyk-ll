package com.example.starter.dto;

/**
 * 接入响应。
 */
public record ReceiveResponse(long operationId, String status, int consistVersion, int carCount) {
}
