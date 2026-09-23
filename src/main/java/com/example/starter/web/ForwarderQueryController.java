package com.example.starter.web;

import com.example.starter.po.AuditRecord;
import com.example.starter.po.DeviceMessage;
import com.example.starter.repo.AuditRepository;
import com.example.starter.repo.DeviceMessageRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 只读查询入口：审计记录与当前存活消息，供核验补发、丢弃、驱逐等行为。 */
@RestController
@RequestMapping("/api/forwarder")
public class ForwarderQueryController {

    private final AuditRepository auditRepository;
    private final DeviceMessageRepository messageRepository;

    public ForwarderQueryController(AuditRepository auditRepository,
                                    DeviceMessageRepository messageRepository) {
        this.auditRepository = auditRepository;
        this.messageRepository = messageRepository;
    }

    /** 查询审计记录，可按事件类型过滤（DROP_POLICY/OVERWRITE/EVICT/REJECT/...）。 */
    @GetMapping("/audits")
    public List<AuditRecord> audits(@RequestParam(required = false) String type) {
        if (type == null || type.isBlank()) {
            return auditRepository.findAll();
        }
        return auditRepository.findByType(com.example.starter.domain.AuditType.valueOf(
                type.trim().toUpperCase()));
    }

    /** 查询当前存活消息（PENDING + IN_FLIGHT）。 */
    @GetMapping("/messages")
    public List<DeviceMessage> messages() {
        return messageRepository.findAll();
    }
}
