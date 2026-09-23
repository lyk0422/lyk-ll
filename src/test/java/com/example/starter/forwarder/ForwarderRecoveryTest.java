package com.example.starter.forwarder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;

import com.example.starter.forwarder.TestSupport.MutableClock;
import com.example.starter.forwarder.TestSupport.RecordingUpstream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 跨进程恢复测试：模拟服务崩溃后重启，待发消息、in-flight 状态、
 * ACK 与审计记录均从 H2 文件库恢复。
 */
class ForwarderRecoveryTest {

    @TempDir
    Path tempDir;

    @Test
    void restartRecoversPendingInFlightAcksAndAudit() {
        MutableClock clock = new MutableClock(
                Instant.parse("2026-09-23T08:00:00Z"), ZoneId.of("Asia/Shanghai"));

        // —— 第一个“进程”：离线写入，恢复联网补发，确认一条，随后崩溃 ——
        JdbcTemplate jdbc1 = TestSupport.jdbcForFileDb(tempDir, "recovery-db");
        ForwarderProperties properties = TestSupport.defaultProperties();
        RecordingUpstream upstream1 = new RecordingUpstream();
        NetworkState network1 = new NetworkState(properties);
        ForwarderService service1 = TestSupport.newService(
                jdbc1, clock, upstream1, network1, properties);

        service1.ingest(TestSupport.msg("dev-1", "scan", 1, 1, "k1"));
        service1.ingest(TestSupport.msg("dev-1", "scan", 2, 1, "k2"));
        service1.ingest(TestSupport.msg("dev-1", "scan", 3, 1, "k3"));
        network1.setOnline(true);
        assertEquals(3, service1.drain());
        assertEquals(AckOutcome.ACKED, service1.ack("k1"));
        // 崩溃：不调用任何清理，直接丢弃本“进程”的对象

        // —— 第二个“进程”：同一文件库重新启动 ——
        JdbcTemplate jdbc2 = TestSupport.jdbcForFileDb(tempDir, "recovery-db");
        MessageRepository messages2 = new MessageRepository(jdbc2);
        AuditRepository audit2 = new AuditRepository(jdbc2);
        RecordingUpstream upstream2 = new RecordingUpstream();
        NetworkState network2 = new NetworkState(properties);
        network2.setOnline(true); // 重启后网络已恢复
        ForwarderService service2 = TestSupport.newService(
                jdbc2, clock, upstream2, network2, properties);

        // 崩溃前状态完整恢复：k1 已确认，k2/k3 仍在途
        assertEquals(MessageStatus.ACKED, messages2.findByDedupKey("k1").orElseThrow().status());
        assertEquals(MessageStatus.IN_FLIGHT, messages2.findByDedupKey("k2").orElseThrow().status());
        assertEquals(MessageStatus.IN_FLIGHT, messages2.findByDedupKey("k3").orElseThrow().status());
        // 审计记录跨进程保留
        assertEquals(3, audit2.countByType(AuditEventType.SENT));
        assertEquals(1, audit2.countByType(AuditEventType.ACKED));

        // 重启恢复：未确认的 in-flight 回到待补发
        assertEquals(2, service2.recoverAfterRestart());
        assertEquals(2, messages2.countByStatus(MessageStatus.PENDING));
        assertEquals(2, audit2.countByType(AuditEventType.RECOVERED));

        // 已确认的 k1 不再转发，只补发 k2/k3
        assertEquals(2, service2.drain());
        assertEquals(List.of("k2", "k3"), upstream2.sent());

        // 重启后重复 ACK 幂等
        assertEquals(AckOutcome.DUPLICATE, service2.ack("k1"));
        assertEquals(1, messages2.countByStatus(MessageStatus.ACKED));

        // 去重键跨进程仍然唯一
        assertEquals(IngestOutcome.DUPLICATE,
                service2.ingest(TestSupport.msg("dev-1", "scan", 9, 1, "k2")));
        assertTrue(messages2.findByDedupKey("k2").isPresent());
        assertEquals(3, messages2.findAll().size());
    }
}
