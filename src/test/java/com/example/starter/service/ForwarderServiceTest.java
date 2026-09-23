package com.example.starter.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.starter.adapter.SyntheticUpstreamAdapter;
import com.example.starter.config.ForwarderProperties;
import com.example.starter.dto.IngestResult;
import com.example.starter.dto.MessageRequest;
import com.example.starter.dto.TopicPolicyRequest;
import com.example.starter.enums.AuditEventType;
import com.example.starter.enums.MessageStatus;
import com.example.starter.enums.RetentionStrategy;
import com.example.starter.exception.RejectedException;
import com.example.starter.model.AuditRecord;
import com.example.starter.model.DeviceMessage;
import com.example.starter.repository.AuditRepository;
import com.example.starter.repository.MessageRepository;
import com.example.starter.repository.TopicPolicyRepository;
import com.example.starter.testsupport.MutableClock;
import com.example.starter.testsupport.TestClockConfiguration;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest
@Import(TestClockConfiguration.class)
class ForwarderServiceTest {

    @Autowired
    private ForwarderService forwarderService;
    @Autowired
    private MessageRepository messageRepository;
    @Autowired
    private TopicPolicyRepository topicPolicyRepository;
    @Autowired
    private AuditRepository auditRepository;
    @Autowired
    private SyntheticUpstreamAdapter upstreamAdapter;
    @Autowired
    private ForwarderProperties properties;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private MutableClock clock;

    @BeforeEach
    void clean() {
        jdbcTemplate.execute("DELETE FROM audit_record");
        jdbcTemplate.execute("DELETE FROM device_message");
        jdbcTemplate.execute("DELETE FROM topic_policy");
        upstreamAdapter.setOnline(true);
        upstreamAdapter.setAutoAck(false);
        upstreamAdapter.clearDeliveryLog();
        properties.setQueueCapacity(100);
    }

    @Test
    void dropStrategyOfflineDiscardsAndAuditsWithoutForwarding() {
        upstreamAdapter.setOnline(false);
        configure("t-drop", "DROP", null);

        IngestResult result = forwarderService.ingest(message("dev1", "t-drop", 1, 5, "k-drop"));

        assertThat(result.outcome()).isEqualTo(IngestResult.DROPPED);
        assertThat(messageRepository.findByMessageId("k-drop")).isNull();
        assertThat(upstreamAdapter.getDeliveredMessageIds()).isEmpty();
        List<AuditRecord> audits = auditRepository.findByEventType(AuditEventType.DROP_POLICY);
        assertThat(audits).hasSize(1);
        assertThat(audits.get(0).getMessageId()).isEqualTo("k-drop");
        assertThat(audits.get(0).getTopic()).isEqualTo("t-drop");
    }

    @Test
    void latestStrategyKeepsOnlyNewestAndAuditsOverwrites() {
        upstreamAdapter.setOnline(false);
        configure("t-latest", "LATEST", null);

        forwarderService.ingest(message("dev1", "t-latest", 1, 0, "k-l1"));
        forwarderService.ingest(message("dev1", "t-latest", 2, 0, "k-l2"));
        forwarderService.ingest(message("dev1", "t-latest", 3, 0, "k-l3"));

        List<DeviceMessage> pending = forwarderService.snapshotPending();
        assertThat(pending).extracting(DeviceMessage::getMessageId).containsExactly("k-l3");
        assertThat(auditRepository.findByEventType(AuditEventType.OVERWRITE_LATEST))
                .extracting(AuditRecord::getMessageId)
                .containsExactly("k-l1", "k-l2");
    }

    @Test
    void fifoStrategyKeepsAllMessagesInOrder() {
        upstreamAdapter.setOnline(false);

        forwarderService.ingest(message("dev1", "t-fifo", 1, 0, "k-f1"));
        forwarderService.ingest(message("dev1", "t-fifo", 2, 0, "k-f2"));
        forwarderService.ingest(message("dev1", "t-fifo", 3, 0, "k-f3"));

        assertThat(forwarderService.snapshotPending())
                .extracting(DeviceMessage::getMessageId)
                .containsExactly("k-f1", "k-f2", "k-f3");
        assertThat(auditRepository.findAll()).isEmpty();
    }

    @Test
    void boundedStrategyKeepsOnlyNAndAuditsEvictions() {
        upstreamAdapter.setOnline(false);
        configure("t-bounded", "BOUNDED", 2);

        forwarderService.ingest(message("dev1", "t-bounded", 1, 0, "k-b1"));
        forwarderService.ingest(message("dev1", "t-bounded", 2, 0, "k-b2"));
        forwarderService.ingest(message("dev1", "t-bounded", 3, 0, "k-b3"));

        assertThat(forwarderService.snapshotPending())
                .extracting(DeviceMessage::getMessageId)
                .containsExactly("k-b2", "k-b3");
        assertThat(auditRepository.findByEventType(AuditEventType.EVICT_BOUNDED))
                .extracting(AuditRecord::getMessageId)
                .containsExactly("k-b1");
    }

    @Test
    void duplicateDedupKeyKeepsSingleRecord() {
        upstreamAdapter.setOnline(false);

        IngestResult first = forwarderService.ingest(message("dev1", "t-x", 1, 0, "k-dup"));
        IngestResult second = forwarderService.ingest(message("dev1", "t-x", 9, 9, "k-dup"));

        assertThat(first.outcome()).isEqualTo(IngestResult.ACCEPTED);
        assertThat(second.outcome()).isEqualTo(IngestResult.DUPLICATE);
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM device_message WHERE message_id = 'k-dup'", Integer.class);
        assertThat(count).isEqualTo(1);
        // 重复上报不覆盖原记录内容。
        DeviceMessage stored = messageRepository.findByMessageId("k-dup");
        assertThat(stored.getSeq()).isEqualTo(1L);
        assertThat(stored.getPriority()).isZero();
    }

    @Test
    void dispatchAfterRecoveryFollowsPriorityThenSeqOrder() {
        upstreamAdapter.setOnline(false);
        forwarderService.ingest(message("dev1", "t", 1, 1, "low1"));
        forwarderService.ingest(message("dev2", "t", 1, 5, "high1"));
        forwarderService.ingest(message("dev1", "t", 2, 1, "low2"));
        forwarderService.ingest(message("dev2", "t", 2, 5, "high2"));
        forwarderService.ingest(message("dev1", "t", 1, 5, "high1-dev1"));

        upstreamAdapter.setOnline(true);
        int delivered = forwarderService.dispatchOnce();

        assertThat(delivered).isEqualTo(5);
        // 优先级高到低；同优先级内按设备本地序号升序（序号相同时以设备标识次序兜底）。
        assertThat(upstreamAdapter.getDeliveredMessageIds())
                .containsExactly("high1-dev1", "high1", "high2", "low1", "low2");
        assertThat(forwarderService.snapshotPending()).isEmpty();
        assertThat(messageRepository.findByMessageId("low1").getStatus())
                .isEqualTo(MessageStatus.INFLIGHT);
    }

    @Test
    void nothingDispatchedWhileOffline() {
        upstreamAdapter.setOnline(false);
        forwarderService.ingest(message("dev1", "t", 1, 0, "k-off"));

        assertThat(forwarderService.dispatchOnce()).isZero();
        assertThat(upstreamAdapter.getDeliveredMessageIds()).isEmpty();
        assertThat(messageRepository.findByMessageId("k-off").getStatus())
                .isEqualTo(MessageStatus.PENDING);
    }

    @Test
    void repeatedAndOutOfOrderAcksAreIdempotentAndDoNotTouchUnacked() {
        forwarderService.ingest(message("dev1", "t", 1, 0, "ack-a"));
        forwarderService.ingest(message("dev1", "t", 2, 0, "ack-b"));
        forwarderService.dispatchOnce();

        // 较大序号先确认，不能误删较小序号的 in-flight 消息。
        assertThat(forwarderService.ack("ack-b")).isTrue();
        assertThat(messageRepository.findByMessageId("ack-b").getStatus())
                .isEqualTo(MessageStatus.FORWARDED);
        assertThat(messageRepository.findByMessageId("ack-a").getStatus())
                .isEqualTo(MessageStatus.INFLIGHT);

        // 重复 ACK 不改变任何计数。
        assertThat(forwarderService.ack("ack-b")).isFalse();
        // 游离 ACK 无效果。
        assertThat(forwarderService.ack("ack-unknown")).isFalse();
        // 较小序号随后可正常确认。
        assertThat(forwarderService.ack("ack-a")).isTrue();
        assertThat(messageRepository.findByMessageId("ack-a").getStatus())
                .isEqualTo(MessageStatus.FORWARDED);
        assertThat(forwarderService.ack("ack-a")).isFalse();

        Integer forwarded = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM device_message WHERE status = 'FORWARDED'", Integer.class);
        assertThat(forwarded).isEqualTo(2);
    }

    @Test
    void timedOutInflightMessageIsRetriedAfterTimeout() {
        forwarderService.ingest(message("dev1", "t", 1, 0, "k-timeout"));
        forwarderService.dispatchOnce();
        assertThat(upstreamAdapter.getDeliveredMessageIds()).containsExactly("k-timeout");

        // 超时前不会回收。
        clock.advance(Duration.ofSeconds(29));
        forwarderService.dispatchOnce();
        assertThat(messageRepository.findByMessageId("k-timeout").getStatus())
                .isEqualTo(MessageStatus.INFLIGHT);
        assertThat(upstreamAdapter.getDeliveredMessageIds()).hasSize(1);

        // 超过 in-flight 超时后回退 PENDING 并重新投递。
        clock.advance(Duration.ofSeconds(2));
        int redelivered = forwarderService.dispatchOnce();
        assertThat(redelivered).isEqualTo(1);
        assertThat(upstreamAdapter.getDeliveredMessageIds())
                .containsExactly("k-timeout", "k-timeout");
        DeviceMessage stored = messageRepository.findByMessageId("k-timeout");
        assertThat(stored.getStatus()).isEqualTo(MessageStatus.INFLIGHT);
        assertThat(stored.getAttempts()).isEqualTo(2);
    }

    @Test
    void lateAckAfterTimeoutRequeueStillConfirmsMessage() {
        forwarderService.ingest(message("dev1", "t", 1, 0, "k-late"));
        forwarderService.dispatchOnce();

        // 超时回退为待发后，上游迟到的 ACK 仍应明确确认该消息。
        clock.advance(Duration.ofSeconds(31));
        forwarderService.dispatchOnce();
        assertThat(messageRepository.findByMessageId("k-late").getStatus())
                .isEqualTo(MessageStatus.INFLIGHT);
        assertThat(forwarderService.ack("k-late")).isTrue();
        assertThat(messageRepository.findByMessageId("k-late").getStatus())
                .isEqualTo(MessageStatus.FORWARDED);
        assertThat(forwarderService.snapshotPending()).isEmpty();
    }

    @Test
    void capacityShortageEvictsLowestPriorityOldestNonInflightWithAudit() {
        properties.setQueueCapacity(3);
        upstreamAdapter.setOnline(false);
        forwarderService.ingest(message("dev1", "t", 1, 5, "high-old"));
        forwarderService.ingest(message("dev1", "t", 2, 1, "low-old"));
        forwarderService.ingest(message("dev1", "t", 3, 1, "low-new"));

        // 第 4 条：先驱逐最低优先级中最旧的 low-old，再写入。
        forwarderService.ingest(message("dev1", "t", 4, 9, "newest"));

        assertThat(forwarderService.snapshotPending())
                .extracting(DeviceMessage::getMessageId)
                .containsExactlyInAnyOrder("high-old", "low-new", "newest");
        assertThat(messageRepository.findByMessageId("low-old")).isNull();
        AuditRecord eviction = auditRepository.findByEventType(AuditEventType.EVICT_CAPACITY)
                .stream().findFirst().orElseThrow();
        assertThat(eviction.getMessageId()).isEqualTo("low-old");
        assertThat(eviction.getPriority()).isEqualTo(1);
    }

    @Test
    void fullQueueWithOnlyInflightMessagesRejectsAndAuditsReason() {
        properties.setQueueCapacity(2);
        forwarderService.ingest(message("dev1", "t", 1, 0, "if-1"));
        forwarderService.ingest(message("dev1", "t", 2, 0, "if-2"));
        forwarderService.dispatchOnce();
        assertThat(messageRepository.countActive()).isEqualTo(2);

        // 容量被 in-flight 占满，不能驱逐 in-flight，只能拒绝并记录原因。
        assertThatThrownBy(() -> forwarderService.ingest(message("dev1", "t", 3, 0, "if-3")))
                .isInstanceOf(RejectedException.class)
                .hasMessageContaining("if-3");

        assertThat(messageRepository.findByMessageId("if-3")).isNull();
        List<AuditRecord> rejects = auditRepository.findByEventType(AuditEventType.REJECT_CAPACITY);
        assertThat(rejects).hasSize(1);
        assertThat(rejects.get(0).getMessageId()).isEqualTo("if-3");
        assertThat(rejects.get(0).getReason()).contains("2");
        // in-flight 消息保持不变。
        assertThat(messageRepository.findByMessageId("if-1").getStatus())
                .isEqualTo(MessageStatus.INFLIGHT);
    }

    @Test
    void startupRecoveryRequeuesLeftoverInflightMessages() {
        forwarderService.ingest(message("dev1", "t", 1, 0, "rc-1"));
        forwarderService.ingest(message("dev1", "t", 2, 0, "rc-2"));
        forwarderService.dispatchOnce();
        assertThat(messageRepository.countActive()).isEqualTo(2);

        // 模拟进程崩溃后重启：残留 INFLIGHT 全部回退 PENDING，并允许重新补发。
        int recovered = messageRepository.recoverInflightOnStartup();
        assertThat(recovered).isEqualTo(2);
        assertThat(forwarderService.snapshotPending())
                .extracting(DeviceMessage::getMessageId)
                .containsExactly("rc-1", "rc-2");

        upstreamAdapter.clearDeliveryLog();
        int redelivered = forwarderService.dispatchOnce();
        assertThat(redelivered).isEqualTo(2);
        assertThat(upstreamAdapter.getDeliveredMessageIds()).containsExactly("rc-1", "rc-2");
    }

    @Test
    void boundedPolicyWithoutBoundIsRejected() {
        TopicPolicyRequest request = new TopicPolicyRequest();
        request.setStrategy("BOUNDED");
        assertThatThrownBy(() -> forwarderService.configurePolicy("t-bad", request))
                .isInstanceOf(com.example.starter.exception.BadRequestException.class);
        request.setBound(0);
        assertThatThrownBy(() -> forwarderService.configurePolicy("t-bad", request))
                .isInstanceOf(com.example.starter.exception.BadRequestException.class);
        assertThat(topicPolicyRepository.findByTopic("t-bad")).isNull();
    }

    @Test
    void defaultPolicyForUnconfiguredTopicIsFifo() {
        upstreamAdapter.setOnline(false);
        forwarderService.ingest(message("dev1", "unconfigured", 1, 0, "k-default"));
        assertThat(forwarderService.snapshotPending()).hasSize(1);
        assertThat(auditRepository.findAll()).isEmpty();
    }

    private void configure(String topic, String strategy, Integer bound) {
        TopicPolicyRequest request = new TopicPolicyRequest();
        request.setStrategy(strategy);
        request.setBound(bound);
        assertThat(forwarderService.configurePolicy(topic, request).getStrategy())
                .isEqualTo(RetentionStrategy.valueOf(strategy));
    }

    private MessageRequest message(String deviceId, String topic, long seq, int priority, String key) {
        MessageRequest request = new MessageRequest();
        request.setDeviceId(deviceId);
        request.setTopic(topic);
        request.setSeq(seq);
        request.setPriority(priority);
        request.setDedupKey(key);
        request.setPayload("payload-" + key);
        return request;
    }
}
