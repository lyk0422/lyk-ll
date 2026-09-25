package com.example.starter.slaughter.service;

/**
 * 并发场景下 requestId 幂等占位被其他事务抢先提交时抛出。
 *
 * @param requestId   幂等键
 * @param fingerprint 本请求计算出的指纹
 */
public class ConcurrentClaimException extends RuntimeException {

    private final String requestId;
    private final String fingerprint;

    public ConcurrentClaimException(String requestId, String fingerprint) {
        super(null, null, false, false);
        this.requestId = requestId;
        this.fingerprint = fingerprint;
    }

    public String requestId() {
        return requestId;
    }

    public String fingerprint() {
        return fingerprint;
    }
}
