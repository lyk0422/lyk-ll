package com.example.starter.service;

import com.example.starter.domain.AuditType;
import com.example.starter.domain.PolicyType;
import com.example.starter.domain.RetentionPolicy;
import com.example.starter.dto.AckResponse;
import com.example.starter.dto.IngestResponse;
import com.example.starter.dto.StatusResponse;
import com.example.starter.po.DeviceMessage;
import com.example.starter.po.TopicConfig;
import com.example.starter.repo.AuditRepository;
import com.example.starter.repo.DeviceMessageRepository;
import com.example.starter.repo.TopicConfigRepository;
import com.example.starter.upstream.UpstreamClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.example.starter.domain.MessageStatus;

/**
 * 边缘离线消息转发核心实现。
 *
 * <p>进程内用 synchronized 串行化入队/补发/确认，跨进程依赖数据库唯一约束与
 * 条件 UPDATE（{@code markInFlight} 仅 PENDING 可抢占）保证不重复发送。</p>
 */
@Service
public class ForwarderServiceImpl implements ForwarderService {

    private static final String OUTCOME_ACCEPTED = "ACCEPTED";
    private static final String OUTCOME_DROPPED = "DROPPED";
    private static final String OUTCOME_REJECTED = "REJECTED";
    private static final String OUTCOME_DEDUP = "DEDUP";
    private static final String OUTCOME_DELIVERED = "DELIVERED";
    private static final String OUTCOME_IGNORED = "IGNORED";

    /** 未显式配置策略的 Topic，离线时按 FIFO 完整保留。 */
    private static final RetentionPolicy DEFAULT_POLICY = RetentionPolicy.of(PolicyType.FIFO);

    private final ForwarderProperties properties;
    private final TopicConfigRepository topicConfigRepository;
    private final DeviceMessageRepository messageRepository;
    private final AuditRepository auditRepository;
    private final UpstreamClient upstreamClient;
    private final Clock clock;
    private final TransactionTemplate transactionTemplate;

    private volatile Instant lastFlushAt;

    public ForwarderServiceImpl(ForwarderProperties properties,
                                TopicConfigRepository topicConfigRepository,
                                DeviceMessageRepository messageRepository,
                                AuditRepository auditRepository,
                                UpstreamClient upstreamClient,
                                Clock clock,
                                org.springframework.transaction.PlatformTransactionManager txManager) {
        this.properties = properties;
        this.topicConfigRepository = topicConfigRepository;
        this.messageRepository = messageRepository;
        this.auditRepository = auditRepository;
        this.upstreamClient = upstreamClient;
        this.clock = clock;
        this.transactionTemplate = new TransactionTemplate(txManager);
    }

    @Override
    public synchronized void configureTopic(String topic, RetentionPolicy policy) {
        transactionTemplate.executeWithoutResult(status ->
                topicConfigRepository.upsert(topic, policy));
    }

    @Override
    public synchronized IngestResponse ingest(String deviceId, String topic, long seq,
                                              int priority, String dedupKey, String payload) {
        Instant now = Instant.now(clock);
        boolean online = upstreamClient.isOnline();

        IngestResult result = transactionTemplate.execute(status -> {
            // 同一全局唯一 dedupKey 只保留一条待发记录：
            // 存活记录（PENDING/IN_FLIGHT）已存在，或该键此前已确认完成（审计跨进程保留），
            // 均幂等跳过。
            if (messageRepository.findByDedupKey(dedupKey).isPresent()
                    || auditRepository.existsDelivered(dedupKey)) {
                auditRepository.insert(AuditType.DEDUP_SKIP, deviceId, topic, seq, dedupKey,
                        null, "dedupKey already queued or previously delivered", now);
                return new IngestResult(OUTCOME_DEDUP, "duplicate dedupKey skipped", null);
            }

            // 离线时按 Topic 策略处理；在线时消息一律进入补发队列，由 flush 按顺序投递。
            if (!online) {
                RetentionPolicy policy = loadPolicy(topic);
                switch (policy.type()) {
                    case DROP -> {
                        auditRepository.insert(AuditType.DROP_POLICY, deviceId, topic, seq,
                                dedupKey, null, "topic policy DROP while offline", now);
                        return new IngestResult(OUTCOME_DROPPED,
                                "dropped by DROP policy while offline", null);
                    }
                    case LATEST -> {
                        // 只保留最新一条：旧的待发消息被覆盖并审计。
                        messageRepository.findLatestPendingByTopic(topic).ifPresent(victim -> {
                            messageRepository.deleteById(victim.id());
                            auditRepository.insert(AuditType.OVERWRITE, victim.deviceId(),
                                    victim.topic(), victim.seq(), victim.dedupKey(),
                                    victim.id(),
                                    "overwritten by newer message " + dedupKey
                                            + " under LATEST policy", now);
                        });
                    }
                    case BOUNDED -> {
                        int capacity = policy.capacity();
                        if (messageRepository.countPendingByTopic(topic) >= capacity) {
                            messageRepository.findOldestPendingByTopic(topic).ifPresent(victim -> {
                                messageRepository.deleteById(victim.id());
                                auditRepository.insert(AuditType.BOUNDED_OVERFLOW,
                                        victim.deviceId(), victim.topic(), victim.seq(),
                                        victim.dedupKey(), victim.id(),
                                        "evicted by BOUNDED(" + capacity
                                                + ") overflow, newer key " + dedupKey, now);
                            });
                        }
                    }
                    case FIFO -> {
                        // 完整顺序保留，落入下方容量检查后直接插入。
                    }
                }
            }

            // 全局容量：驱逐最低优先级、最旧且非 in-flight 的记录；全是 in-flight 则拒绝。
            while (messageRepository.countAll() >= properties.capacity()) {
                Optional<DeviceMessage> victim = messageRepository.findEvictable();
                if (victim.isEmpty()) {
                    auditRepository.insert(AuditType.REJECT, deviceId, topic, seq, dedupKey,
                            null, "capacity " + properties.capacity()
                                    + " exhausted and no evictable (non-inflight) record", now);
                    return new IngestResult(OUTCOME_REJECTED,
                            "capacity exhausted, all records in-flight", null);
                }
                DeviceMessage evicted = victim.get();
                messageRepository.deleteById(evicted.id());
                auditRepository.insert(AuditType.EVICT, evicted.deviceId(), evicted.topic(),
                        evicted.seq(), evicted.dedupKey(), evicted.id(),
                        "evicted lowest-priority oldest pending message to free capacity", now);
            }
            DeviceMessage saved;
            try {
                saved = messageRepository.insert(deviceId, topic, seq, priority,
                        dedupKey, payload, now);
            } catch (org.springframework.dao.DuplicateKeyException duplicate) {
                // 跨进程并发写入相同 dedupKey 时由数据库唯一约束兜底：幂等跳过。
                auditRepository.insert(AuditType.DEDUP_SKIP, deviceId, topic, seq, dedupKey,
                        null, "same dedupKey inserted concurrently, skipped by unique constraint",
                        now);
                return new IngestResult(OUTCOME_DEDUP,
                        "duplicate dedupKey skipped by constraint", null);
            }
            return new IngestResult(OUTCOME_ACCEPTED, "queued", saved.id());
        });

        IngestResponse response = new IngestResponse(result.outcome(), result.reason(),
                result.messageId());
        // 在线写入在事务提交后立即按全局补发顺序投递；离线写入留待网络恢复。
        if (online && OUTCOME_ACCEPTED.equals(result.outcome())) {
            doFlush(Instant.now(clock));
        }
        return response;
    }

    /** ingest 事务内部返回值。 */
    private record IngestResult(String outcome, String reason, Long messageId) {
    }

    @Override
    public synchronized AckResponse acknowledge(String dedupKey) {
        Instant now = Instant.now(clock);
        // ACK 按全局唯一 dedupKey 精确确认，不按序号范围确认，
        // 因此较大序号的 ACK 不会误删尚未确认的较小序号消息。
        Boolean delivered = transactionTemplate.execute(status -> {
            Optional<DeviceMessage> deleted = messageRepository.deleteByDedupKey(dedupKey);
            if (deleted.isPresent()) {
                DeviceMessage m = deleted.get();
                auditRepository.insert(AuditType.DELIVERED, m.deviceId(), m.topic(), m.seq(),
                        m.dedupKey(), m.id(), "explicit ACK received", now);
                return Boolean.TRUE;
            }
            // 重复 ACK 或未知键：不改任何计数，仅留审计。
            auditRepository.insert(AuditType.ACK_IGNORED, null, null, null, dedupKey, null,
                    "duplicate or out-of-order ACK with no outstanding message", now);
            return Boolean.FALSE;
        });
        return Boolean.TRUE.equals(delivered)
                ? new AckResponse(OUTCOME_DELIVERED, dedupKey)
                : new AckResponse(OUTCOME_IGNORED, dedupKey);
    }

    @Override
    public synchronized int flush() {
        return doFlush(Instant.now(clock));
    }

    private int doFlush(Instant now) {
        // 超时 in-flight 先回退为待发，允许重试。
        Instant cutoff = now.minusMillis(properties.inFlightTimeoutMs());
        List<DeviceMessage> stale = transactionTemplate.execute(
                status -> messageRepository.resetStaleInFlight(cutoff));
        if (stale != null) {
            for (DeviceMessage m : stale) {
                Instant at = Instant.now(clock);
                transactionTemplate.executeWithoutResult(status ->
                        auditRepository.insert(AuditType.RETRY_RESET, m.deviceId(), m.topic(),
                                m.seq(), m.dedupKey(), m.id(),
                                "in-flight timeout, reset to PENDING", at));
            }
        }

        if (!upstreamClient.isOnline()) {
            lastFlushAt = now;
            return 0;
        }

        int sent = 0;
        List<DeviceMessage> batch = messageRepository.findPending(properties.flushBatchSize());
        for (DeviceMessage m : batch) {
            Instant at = Instant.now(clock);
            // 条件抢占：只有仍是 PENDING 的消息才会真正发出，杜绝并发双发。
            Boolean claimed = transactionTemplate.execute(
                    status -> messageRepository.markInFlight(m.id(), at));
            if (!Boolean.TRUE.equals(claimed)) {
                continue;
            }
            // dispatch 在数据库事务之外执行，模拟真实上游调用不持有数据库连接。
            if (upstreamClient.dispatch(m)) {
                transactionTemplate.executeWithoutResult(status ->
                        auditRepository.insert(AuditType.SENT, m.deviceId(), m.topic(), m.seq(),
                                m.dedupKey(), m.id(),
                                "dispatched to upstream, awaiting ACK", at));
                sent++;
            } else {
                // 链路抖动：抢占成功但投递失败，回退待发，等下轮补发。
                transactionTemplate.executeWithoutResult(status ->
                        messageRepository.revertToPending(m.id()));
            }
        }
        lastFlushAt = now;
        return sent;
    }

    /**
     * 崩溃/重启恢复：上次进程遗留的 in-flight 消息无法确认 ACK 是否到达，
     * 统一重置为 PENDING（at-least-once），并逐条写审计；若链路在线则立即补发。
     */
    public synchronized int recoverAfterRestart() {
        Instant now = Instant.now(clock);
        Integer count = transactionTemplate.execute(status -> {
            List<DeviceMessage> recovered = messageRepository.resetAllInFlight();
            for (DeviceMessage m : recovered) {
                auditRepository.insert(AuditType.RETRY_RESET, m.deviceId(), m.topic(), m.seq(),
                        m.dedupKey(), m.id(),
                        "reset IN_FLIGHT to PENDING after service restart", now);
            }
            return recovered.size();
        });
        if (upstreamClient.isOnline()) {
            return doFlush(now);
        }
        return count == null ? 0 : count;
    }

    @Override
    public void setOnline(boolean online) {
        upstreamClient.setOnline(online);
        if (online) {
            flush();
        }
    }

    @Override
    public synchronized StatusResponse status() {
        long pending = messageRepository.countByStatus(MessageStatus.PENDING);
        long inFlight = messageRepository.countByStatus(MessageStatus.IN_FLIGHT);
        long total = messageRepository.countAll();
        long delivered = auditRepository.countByType(AuditType.DELIVERED);
        return new StatusResponse(upstreamClient.isOnline(), pending, inFlight, total,
                properties.capacity(), delivered,
                lastFlushAt == null ? null : lastFlushAt.toString());
    }

    @Override
    public synchronized Map<String, String> topicPolicies() {
        Map<String, String> result = new LinkedHashMap<>();
        for (TopicConfig config : topicConfigRepository.findAll()) {
            Integer cap = "BOUNDED".equals(config.policyType()) ? config.capacity() : null;
            RetentionPolicy policy = cap == null
                    ? RetentionPolicy.of(PolicyType.valueOf(config.policyType()))
                    : new RetentionPolicy(PolicyType.BOUNDED, cap);
            result.put(config.topic(), policy.format());
        }
        return result;
    }

    private RetentionPolicy loadPolicy(String topic) {
        return topicConfigRepository.findByTopic(topic).map(config -> {
            if (config.policyType().equals(PolicyType.BOUNDED.name())) {
                return RetentionPolicy.bounded(config.capacity());
            }
            return RetentionPolicy.of(PolicyType.valueOf(config.policyType()));
        }).orElse(DEFAULT_POLICY);
    }
}
