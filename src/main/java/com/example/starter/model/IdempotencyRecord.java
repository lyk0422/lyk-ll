package com.example.starter.model;

/**
 * 幂等记录行。
 *
 * @param requestId      幂等请求标识
 * @param endpoint       接口标识
 * @param fingerprint    请求指纹（requestId 与全部业务字段的 SHA-256）
 * @param responseStatus 首次响应 HTTP 状态码，NULL 表示处理中
 * @param responseBody   首次响应报文
 */
public record IdempotencyRecord(String requestId, String endpoint, String fingerprint,
                                Integer responseStatus, String responseBody) {

    /**
     * 是否已完成（已有首次响应）。
     */
    public boolean completed() {
        return responseStatus != null;
    }
}
