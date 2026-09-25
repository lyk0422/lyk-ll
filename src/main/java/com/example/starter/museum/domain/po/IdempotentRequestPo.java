package com.example.starter.museum.domain.po;

import java.time.Instant;

/**
 * requestId 幂等记录。fingerprint 覆盖 requestId 与全部业务字段：
 * 同键同参回放首次响应；同键异参返回 409。
 */
public class IdempotentRequestPo {
    public String requestId;
    public String fingerprint;
    public Integer httpStatus;
    public String responseJson;
    public Instant createdAt;
}
