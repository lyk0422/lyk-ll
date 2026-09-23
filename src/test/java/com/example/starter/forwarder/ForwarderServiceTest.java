package com.example.starter.forwarder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;

import com.example.starter.forwarder.TestSupport.MutableClock;
import com.example.starter.forwarder.TestSupport.RecordingUpstream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 转发器核心流程测试：断网策略、去重、补发顺序、ACK 语义、容量驱逐与拒绝。
 * 使用独立临时目录下的 H2 文件库（MySQL 兼容模式）。
 */
class ForwarderServiceTest {

    @TempDir
    Path tempDir;

    private JdbcTemplate jdbc;
    private MessageRepository messages;
    private AuditRepository audit;
    private MutableClock clock;
    private RecordingUpstream upstream;
    private NetworkState network;
    private ForwarderProperties properties;
    private ForwarderService service;

    @BeforeEach
    void setUp() {
        jdbc = TestSupport.jdbcForFileDb(tempDir, "service-test");
        messages = new MessageRepository(jdbc);
        audit = new AuditRepository(jdbc);
        clock = new MutableClock(Instant.parse("2026-09-23T08:00:00Z"), ZoneId.of("Asia/Shanghai"));
        upstream = new RecordingUpstream();
        properties = TestSupport.defaultProperties();
        network = new NetworkState(properties); // 默认离线
        service = TestSupport.newService(jdbc, clock, upstream, network, properties);
    }

    @Test
    void dropPolicyDiscardsOfflineMessageAndAudits() {
        properties.getTopicPolicies().put("rosout", "DROP");

        IngestOutcome outcome = service.ingest(TestSupport.msg("dev-1", "rosout", 1, 1, "k1"));

        assertEquals(IngestOutcome.DROPPED_BY_POLICY, outcome);
        assertEquals(0, messages.countUnfinished());
        List<AuditRepository.AuditRow> drops = audit.findByType(AuditEventType.DROPPED_BY_POLICY);
        assertEquals(1, drops.size());
        assertEquals("k1", drops.get(0).dedupKey());
        // 被丢弃的消息不得伪装成已转发
        assertEquals(0, audit.countByType(AuditEventType.SENT));
    }

    @Test
    void latestPolicyKeepsOnlyNewestAndAuditsOverwrite() {
        properties.getTopicPolicies().put("tf", "LATEST");

        service.ingest(TestSupport.msg("dev-1", "tf", 1, 1, "k1"));
        service.ingest(TestSupport.msg("dev-1", "tf", 2, 1, "k2"));
        service.ingest(TestSupport.msg("dev-1", "tf", 3, 1, "k3"));

        List<MessageView> remaining = messages.findAll();
        assertEquals(1, remaining.size());
        assertEquals("k3", remaining.get(0).dedupKey());
        List<AuditRepository.AuditRow> overwritten = audit.findByType(AuditEventType.OVERWRITTEN);
        assertEquals(2, overwritten.size());
        assertEquals("k1", overwritten.get(0).dedupKey());
        assertEquals("k2", overwritten.get(1).dedupKey());
    }

    @Test
    void fifoPolicyKeepsAllMessagesInOrder() {
        properties.getTopicPolicies().put("scan", "FIFO");

        service.ingest(TestSupport.msg("dev-1", "scan", 1, 1, "k1"));
        service.ingest(TestSupport.msg("dev-1", "scan", 2, 1, "k2"));
        service.ingest(TestSupport.msg("dev-1", "scan", 3, 1, "k3"));

        assertEquals(3, messages.countUnfinished());
        assertEquals(0, audit.countByType(AuditEventType.DROPPED_BY_POLICY));
    }

    @Test
    void boundedPolicyEvictsOldestAndAudits() {
        properties.getTopicPolicies().put("cmd_vel", "BOUNDED(2)");

        service.ingest(TestSupport.msg("dev-1", "cmd_vel", 1, 1, "k1"));
        service.ingest(TestSupport.msg("dev-1", "cmd_vel", 2, 1, "k2"));
        service.ingest(TestSupport.msg("dev-1", "cmd_vel", 3, 1, "k3"));

        List<MessageView> remaining = messages.findAll();
        assertEquals(2, remaining.size());
        assertEquals(List.of("k2", "k3"), remaining.stream().map(MessageView::dedupKey).toList());
        List<AuditRepository.AuditRow> drops = audit.findByType(AuditEventType.DROPPED_BY_POLICY);
        assertEquals(1, drops.size());
        assertEquals("k1", drops.get(0).dedupKey());
    }

    @Test
    void sameDedupKeyKeepsOnlyOnePendingRecord() {
        assertEquals(IngestOutcome.QUEUED,
                service.ingest(TestSupport.msg("dev-1", "scan", 1, 1, "dup-key")));
        assertEquals(IngestOutcome.DUPLICATE,
                service.ingest(TestSupport.msg("dev-1", "scan", 2, 9, "dup-key")));

        assertEquals(1, messages.countUnfinished());
    }

    @Test
    void drainSendsByPriorityDescThenDeviceSeqAsc() {
        service.ingest(TestSupport.msg("dev-1", "scan", 1, 1, "low-1"));
        service.ingest(TestSupport.msg("dev-1", "scan", 2, 1, "low-2"));
        service.ingest(TestSupport.msg("dev-1", "scan", 5, 9, "high-5"));
        service.ingest(TestSupport.msg("dev-1", "scan", 3, 9, "high-3"));
        service.ingest(TestSupport.msg("dev-1", "scan", 4, 5, "mid-4"));

        network.setOnline(true);
        int sent = service.drain();

        assertEquals(5, sent);
        assertEquals(List.of("high-3", "high-5", "mid-4", "low-1", "low-2"), upstream.sent());
        assertEquals(5, messages.countByStatus(MessageStatus.IN_FLIGHT));
    }

    @Test
    void duplicateAckDoesNotChangeStateOrCount() {
        service.ingest(TestSupport.msg("dev-1", "scan", 1, 1, "k1"));
        network.setOnline(true);
        service.drain();

        assertEquals(AckOutcome.ACKED, service.ack("k1"));
        assertEquals(AckOutcome.DUPLICATE, service.ack("k1"));
        assertEquals(AckOutcome.DUPLICATE, service.ack("k1"));

        assertEquals(1, messages.countByStatus(MessageStatus.ACKED));
        assertEquals(1, audit.countByType(AuditEventType.ACKED));
    }

    @Test
    void outOfOrderAckOnlyConfirmsSpecifiedMessage() {
        service.ingest(TestSupport.msg("dev-1", "scan", 3, 1, "k3"));
        service.ingest(TestSupport.msg("dev-1", "scan", 5, 1, "k5"));
        network.setOnline(true);
        service.drain();

        // 较大序号的 ACK 先到达：不得误删尚未确认的较小序号
        assertEquals(AckOutcome.ACKED, service.ackByDeviceSeq("dev-1", 5));

        assertEquals(MessageStatus.ACKED, messages.findByDedupKey("k5").orElseThrow().status());
        assertEquals(MessageStatus.IN_FLIGHT, messages.findByDedupKey("k3").orElseThrow().status());
        assertEquals(AckOutcome.NOT_FOUND, service.ack("no-such-key"));
    }

    @Test
    void unackedInFlightMessageIsRequeuedAfterTimeout() {
        service.ingest(TestSupport.msg("dev-1", "scan", 1, 1, "k1"));
        network.setOnline(true);
        service.drain();
        assertEquals(1, messages.countByStatus(MessageStatus.IN_FLIGHT));

        // 未超时：不重排
        clock.advanceSeconds(10);
        assertEquals(0, service.requeueTimedOutInFlight());
        assertEquals(1, messages.countByStatus(MessageStatus.IN_FLIGHT));

        // 超时：回到 PENDING 允许重试
        clock.advanceSeconds(25);
        assertEquals(1, service.requeueTimedOutInFlight());
        assertEquals(1, messages.countByStatus(MessageStatus.PENDING));
        assertEquals(1, audit.countByType(AuditEventType.REQUEUED));

        // 重试后仍可确认
        assertEquals(1, service.drain());
        assertEquals(AckOutcome.ACKED, service.ack("k1"));
    }

    @Test
    void upstreamFailureReturnsMessageToPendingForRetry() {
        service.ingest(TestSupport.msg("dev-1", "scan", 1, 1, "k1"));
        service.ingest(TestSupport.msg("dev-1", "scan", 2, 1, "k2"));
        network.setOnline(true);

        upstream.setFailing(true);
        assertEquals(0, service.drain());
        assertEquals(2, messages.countByStatus(MessageStatus.PENDING));

        upstream.setFailing(false);
        assertEquals(2, service.drain());
        assertEquals(2, messages.countByStatus(MessageStatus.IN_FLIGHT));
    }

    @Test
    void capacityShortageEvictsLowestPriorityOldestNonInFlight() {
        properties.setCapacity(3);
        service.ingest(TestSupport.msg("dev-1", "scan", 1, 1, "low-old"));
        clock.advanceSeconds(1);
        service.ingest(TestSupport.msg("dev-1", "scan", 2, 1, "low-new"));
        clock.advanceSeconds(1);
        service.ingest(TestSupport.msg("dev-1", "scan", 3, 9, "high"));

        // 容量已满，新消息触发驱逐：最低优先级中最旧的 low-old
        assertEquals(IngestOutcome.QUEUED,
                service.ingest(TestSupport.msg("dev-1", "scan", 4, 5, "newcomer")));

        assertTrue(messages.findByDedupKey("low-old").isEmpty());
        assertEquals(3, messages.countUnfinished());
        List<AuditRepository.AuditRow> evicted = audit.findByType(AuditEventType.EVICTED);
        assertEquals(1, evicted.size());
        assertEquals("low-old", evicted.get(0).dedupKey());
    }

    @Test
    void capacityFullOfInFlightRejectsNewMessageAndAudits() {
        properties.setCapacity(2);
        service.ingest(TestSupport.msg("dev-1", "scan", 1, 1, "k1"));
        service.ingest(TestSupport.msg("dev-1", "scan", 2, 1, "k2"));
        network.setOnline(true);
        service.drain(); // 两条都进入 in-flight，不可驱逐

        network.setOnline(false);
        IngestOutcome outcome = service.ingest(TestSupport.msg("dev-1", "scan", 3, 1, "k3"));

        assertEquals(IngestOutcome.REJECTED_CAPACITY, outcome);
        assertTrue(messages.findByDedupKey("k3").isEmpty());
        List<AuditRepository.AuditRow> rejects = audit.findByType(AuditEventType.REJECTED_CAPACITY);
        assertEquals(1, rejects.size());
        assertEquals("k3", rejects.get(0).dedupKey());
    }
}
