package com.example.starter.forwarder;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 离线消息转发核心服务：缓存策略、去重、补发调度、ACK 确认与崩溃恢复。
 */
@Service
public class ForwarderService {

    private static final int DRAIN_BATCH_SIZE = 100;

    private final MessageRepository messages;
    private final AuditRepository audit;
    private final UpstreamAdapter upstream;
    private final NetworkState network;
    private final ForwarderProperties properties;
    private final Clock clock;

    public ForwarderService(MessageRepository messages, AuditRepository audit,
                            UpstreamAdapter upstream, NetworkState network,
                            ForwarderProperties properties, Clock clock) {
        this.messages = messages;
        this.audit = audit;
        this.upstream = upstream;
        this.network = network;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * 接收一条设备消息。在线时入队并立即尝试转发；离线时按 Topic 策略处理。
     */
    @Transactional
    public IngestOutcome ingest(IncomingMessage message) {
        LocalDateTime now = LocalDateTime.now(clock);

        // 同一 dedupKey 只保留一条待发记录（幂等）
        if (messages.findByDedupKey(message.dedupKey()).isPresent()) {
            return IngestOutcome.DUPLICATE;
        }

        if (!network.isOnline()) {
            TopicPolicy policy = properties.policyFor(message.topic());
            switch (policy.type()) {
                case DROP -> {
                    audit.record(AuditEventType.DROPPED_BY_POLICY, message.deviceId(), message.topic(),
                            message.deviceSeq(), message.dedupKey(),
                            "离线且 Topic 策略为 DROP，直接丢弃", now);
                    return IngestOutcome.DROPPED_BY_POLICY;
                }
                case LATEST -> {
                    // 只保留最新一条：覆盖同 Topic 下尚未发送的旧消息
                    for (MessageView old : messages.findPendingByTopicOldestFirst(message.topic())) {
                        messages.deleteById(old.id());
                        audit.recordFor(AuditEventType.OVERWRITTEN, old,
                                "LATEST 策略下被 dedupKey=" + message.dedupKey() + " 覆盖", now);
                    }
                }
                case BOUNDED -> {
                    // 最多保留 n 条：挤出最旧的 PENDING 记录
                    while (messages.countPendingByTopic(message.topic()) >= policy.bound()) {
                        List<MessageView> oldest = messages.findPendingByTopicOldestFirst(message.topic());
                        if (oldest.isEmpty()) {
                            break;
                        }
                        MessageView evicted = oldest.get(0);
                        messages.deleteById(evicted.id());
                        audit.recordFor(AuditEventType.DROPPED_BY_POLICY, evicted,
                                "BOUNDED(" + policy.bound() + ") 策略挤出最旧记录，为新消息 dedupKey="
                                        + message.dedupKey() + " 腾出位置", now);
                    }
                }
                case FIFO -> {
                    // 完整顺序保留，无需额外处理
                }
            }
        }

        // 全局容量控制：不足时先驱逐最低优先级中最旧且非 in-flight 的记录
        while (messages.countUnfinished() >= properties.getCapacity()) {
            Optional<MessageView> evictable = messages.findEvictable();
            if (evictable.isEmpty()) {
                audit.record(AuditEventType.REJECTED_CAPACITY, message.deviceId(), message.topic(),
                        message.deviceSeq(), message.dedupKey(),
                        "容量 " + properties.getCapacity() + " 已满且无可驱逐记录，拒绝新消息", now);
                return IngestOutcome.REJECTED_CAPACITY;
            }
            MessageView evicted = evictable.get();
            messages.deleteById(evicted.id());
            audit.recordFor(AuditEventType.EVICTED, evicted,
                    "容量不足，驱逐最低优先级中最旧的非 in-flight 记录", now);
        }

        try {
            messages.insert(message, now);
        } catch (DuplicateKeyException e) {
            // 并发写入相同 dedupKey：唯一约束兜底，幂等返回
            return IngestOutcome.DUPLICATE;
        }
        return IngestOutcome.QUEUED;
    }

    /**
     * 网络恢复或定时触发：按优先级从高到低、同优先级按设备序号升序补发。
     * 发送失败（网络抖动）时回到 PENDING 并停止本轮，等待下次重试。
     *
     * @return 本轮成功发送（进入 in-flight）的条数
     */
    public int drain() {
        if (!network.isOnline()) {
            return 0;
        }
        int sent = 0;
        while (true) {
            List<MessageView> batch = messages.findPendingForDrain(DRAIN_BATCH_SIZE);
            if (batch.isEmpty()) {
                return sent;
            }
            for (MessageView message : batch) {
                LocalDateTime now = LocalDateTime.now(clock);
                if (!messages.markInFlight(message.id(), now)) {
                    continue; // 并发下已被其他线程抢占
                }
                MessageView inFlight = messages.findByDedupKey(message.dedupKey()).orElse(message);
                if (upstream.send(inFlight)) {
                    audit.recordFor(AuditEventType.SENT, inFlight,
                            "已发送到上游，等待 ACK（第 " + inFlight.attemptCount() + " 次尝试）", now);
                    sent++;
                } else {
                    messages.backToPending(message.id(), LocalDateTime.now(clock));
                    return sent; // 网络抖动：本轮停止，未确认的允许重试
                }
            }
        }
    }

    /**
     * 处理 ACK。ACK 可重复或乱序：重复 ACK 不改变状态与计数；
     * 只确认明确指定的消息，较大序号的 ACK 不会误删尚未确认的较小序号。
     */
    @Transactional
    public AckOutcome ack(String dedupKey) {
        Optional<MessageView> found = messages.findByDedupKey(dedupKey);
        if (found.isEmpty()) {
            return AckOutcome.NOT_FOUND;
        }
        return doAck(found.get());
    }

    /**
     * 按设备 ID + 设备本地序号处理 ACK（与 dedupKey 通道等价，二选一）。
     */
    @Transactional
    public AckOutcome ackByDeviceSeq(String deviceId, long deviceSeq) {
        Optional<MessageView> found = messages.findByDeviceAndSeq(deviceId, deviceSeq);
        if (found.isEmpty()) {
            return AckOutcome.NOT_FOUND;
        }
        return doAck(found.get());
    }

    private AckOutcome doAck(MessageView message) {
        if (message.status() == MessageStatus.ACKED) {
            return AckOutcome.DUPLICATE; // 重复 ACK：状态与计数不变
        }
        LocalDateTime now = LocalDateTime.now(clock);
        if (messages.markAcked(message.id(), now)) {
            audit.recordFor(AuditEventType.ACKED, message, "收到明确 ACK，不再转发", now);
            return AckOutcome.ACKED;
        }
        return AckOutcome.DUPLICATE; // 并发下已被确认
    }

    /**
     * 服务重启恢复：所有未确认的 in-flight 消息回到 PENDING，允许重试。
     *
     * @return 恢复的消息数
     */
    @Transactional
    public int recoverAfterRestart() {
        LocalDateTime now = LocalDateTime.now(clock);
        List<MessageView> recovered = messages.requeueInFlight(null, now);
        for (MessageView message : recovered) {
            audit.recordFor(AuditEventType.RECOVERED, message,
                    "服务重启，未确认的 in-flight 消息恢复为待补发", now);
        }
        return recovered.size();
    }

    /**
     * in-flight 超时未确认的消息重新排队，允许重试。
     *
     * @return 重新排队的消息数
     */
    @Transactional
    public int requeueTimedOutInFlight() {
        LocalDateTime now = LocalDateTime.now(clock);
        LocalDateTime deadline = now.minusSeconds(properties.getInFlightTimeoutSeconds());
        List<MessageView> stale = messages.requeueInFlight(deadline, now);
        for (MessageView message : stale) {
            audit.recordFor(AuditEventType.REQUEUED, message,
                    "in-flight 超过 " + properties.getInFlightTimeoutSeconds() + " 秒未确认，重新排队", now);
        }
        return stale.size();
    }
}
