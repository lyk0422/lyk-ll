package com.example.starter.service;

import com.example.starter.enums.AuditEventType;
import com.example.starter.model.DeviceMessage;
import com.example.starter.repository.AuditRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 审计服务：审计记录在独立事务中提交，
 * 保证“拒绝/丢弃”等导致主业务事务回滚或不落库的场景下审计仍然留存。
 */
@Service
public class AuditService {

    private final AuditRepository auditRepository;

    public AuditService(AuditRepository auditRepository) {
        this.auditRepository = auditRepository;
    }

    /** 在独立新事务中写入一条消息处置审计。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordInNewTransaction(AuditEventType type, DeviceMessage message,
                                       String reason, long now) {
        auditRepository.record(type, message, reason, now);
    }
}
