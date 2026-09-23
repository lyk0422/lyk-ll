package com.example.starter.forwarder.repo;

import com.example.starter.forwarder.model.AuditEventType;
import com.example.starter.forwarder.model.DeviceMessage;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 审计记录仓储：策略丢弃、覆盖、驱逐、拒绝、超时重试、启动重置、补发、ACK 全部落库，
 * 任何被策略造成的丢弃/覆盖都不得伪装成已转发。
 */
@Repository
public class AuditRepository {

    private final JdbcTemplate jdbcTemplate;

    public AuditRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 记录一条与具体消息关联的审计事件。 */
    public void record(AuditEventType type, DeviceMessage message, String detail, long now) {
        record(type, message.getDedupKey(), message.getDeviceId(), message.getTopic(),
                message.getSeq(), message.getPriority(), detail, now);
    }

    /** 记录一条审计事件（被丢弃的新消息可能尚未入库，字段可为空）。 */
    public void record(AuditEventType type, String dedupKey, String deviceId, String topic,
                       Long seq, Integer priority, String detail, long now) {
        jdbcTemplate.update(
                "INSERT INTO audit_log (event_type, dedup_key, device_id, topic, seq, priority, detail, created_at) "
                        + "VALUES (?,?,?,?,?,?,?,?)",
                type.name(), dedupKey, deviceId, topic, seq, priority,
                detail == null ? null : detail.substring(0, Math.min(detail.length(), 1024)), now);
    }

    /** 按事件类型计数。 */
    public long countByType(AuditEventType type) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM audit_log WHERE event_type = ?", Long.class, type.name());
        return count == null ? 0 : count;
    }

    /** 按事件类型查询全部原因说明（测试断言用）。 */
    public List<String> findDetailsByType(AuditEventType type) {
        return jdbcTemplate.queryForList(
                "SELECT detail FROM audit_log WHERE event_type = ? ORDER BY id ASC",
                String.class, type.name());
    }

    /** 查询某事件类型关联的去重键（测试断言驱逐对象用）。 */
    public List<String> findDedupKeysByType(AuditEventType type) {
        return jdbcTemplate.queryForList(
                "SELECT dedup_key FROM audit_log WHERE event_type = ? ORDER BY id ASC",
                String.class, type.name());
    }
}
