package com.example.starter.repo;

import com.example.starter.domain.AuditType;
import com.example.starter.po.AuditRecord;

import java.time.Instant;
import java.util.List;

/** 审计记录数据访问。 */
public interface AuditRepository {

    AuditRecord insert(AuditType type, String deviceId, String topic, Long seq,
                       String dedupKey, Long messageId, String reason, Instant now);

    List<AuditRecord> findAll();

    List<AuditRecord> findByType(AuditType type);

    /** 某类事件总数（重启后仍可从审计表恢复计数）。 */
    long countByType(AuditType type);

    /** 指定去重键是否已有 DELIVERED 审计（用于跨进程/重启后的幂等判定）。 */
    boolean existsDelivered(String dedupKey);
}
