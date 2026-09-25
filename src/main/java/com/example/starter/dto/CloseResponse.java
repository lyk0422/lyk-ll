package com.example.starter.dto;

/**
 * 销记响应: alreadyClosed=true 表示重复销记的幂等成功。
 */
public record CloseResponse(long operationId, boolean closed, boolean alreadyClosed) {
}
