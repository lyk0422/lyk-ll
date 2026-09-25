package com.example.starter.epidemic.repo;

import java.time.LocalDateTime;

/**
 * idempotent_request 表实体：requestId 幂等记录。
 */
public class IdempotentRequestPo {
    public String requestId;
    public String requestType;
    public String fingerprint;
    /** CREATED=首次新建版本；DUPLICATE=命中重复去重。 */
    public String resultKind;
    public String cardNumber;
    public int versionNo;
    public LocalDateTime createdAt;
}
