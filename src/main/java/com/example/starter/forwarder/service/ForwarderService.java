package com.example.starter.forwarder.service;

import com.example.starter.forwarder.config.ForwarderProperties;
import com.example.starter.forwarder.model.AuditEventType;
import com.example.starter.forwarder.model.DeviceMessage;
import com.example.starter.forwarder.model.MessageStatus;
import com.example.starter.forwarder.policy.RetentionPolicy;
import com.example.starter.forwarder.repo.AuditRepository;
import com.example.starter.forwarder.repo.MessageRepository;
import com.example.starter.forwarder.support.Clock;
import com.example.starter.forwarder.upstream.UpstreamAdapter;
import com.example.starter.forwarder.web.AckRequest;
import com.example.starter.forwarder.web.AckResponse;
import com.example.starter.forwarder.web.IngestRequest;
import com.example.starter.forwarder.web.IngestResponse;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 离线消息转发核心服务：接入缓存、策略丢弃/覆盖、容量驱逐、补发、ACK、超时与重启恢复。
 *
 * <p>所有状态迁移都通过仓储中的条件 UPDATE 完成，保证重复 ACK、乱序 ACK、
 * 并发抢占与超时重试下计数不错乱；丢弃/覆盖/驱逐/拒绝全部写审计。</p>
 */
@Service
public class ForwarderService {

    private static final Logger log = LoggerFactory.getLogger(ForwarderService.class);

    private final MessageRepository messageRepository;
    private final AuditRepository auditRepository;
    private final ForwarderProperties properties;
    private final Clock clock;
    private final UpstreamAdapter upstreamAdapter;

    /** topic -> 离线保留策略；未配置的 Topic 默认 FIFO。 */
    private final Map<String, RetentionPolicy> policyMap;

    /** 是否联网；离线时消息按策略进入缓存，联网后调度补发。 */
    private volatile boolean online = false;

    public ForwarderService(MessageRepository messageRepository, AuditRepository auditRepository,
                            ForwarderProperties properties, Clock clock, UpstreamAdapter upstreamAdapter) {
        this.messageRepository = messageRepository;
        this.auditRepository = auditRepository;
        this.properties = properties;
        this.clock = clock;
        this.upstreamAdapter = upstreamAdapter;
        this.policyMap = properties.policyMap();
    }

    @PostConstruct
    public void init() {
        this.online = properties.isOnlineOnStartup();
    }

    public boolean isOnline() {
        return online;
    }

    public void setOnline(boolean online) {
        this.online = online;
    }

    /**
     * 接入一条设备消息：重复 dedupKey 忽略；离线时按 Topic 策略缓存/丢弃；
     * 容量不足先驱逐最低优先级中最旧且非 in-flight 的记录，仍无法写入则拒绝并审计。
     */
    @Transactional
    public IngestResponse ingest(IngestRequest request) {
        long now = clock.nowMillis();

        DeviceMessage existing = messageRepository.findByDedupKey(request.getDedupKey());
        if (existing != null) {
            // 同一 dedupKey 全局只保留一条；重复消息不改任何计数与状态。
            return IngestResponse.duplicate(request.getDedupKey());
        }

        RetentionPolicy policy = policyMap.getOrDefault(request.getTopic(), RetentionPolicy.fifo());

        if (!online && policy.kind() == RetentionPolicy.Kind.DROP) {
            auditRepository.record(AuditEventType.DROP_POLICY, request.getDedupKey(),
                    request.getDeviceId(), request.getTopic(), request.getSeq().longValue(),
                    request.getPriority(), "Topic 策略 DROP：设备离线，消息直接丢弃", now);
            return IngestResponse.rejected(request.getDedupKey(),
                    "Topic 策略 DROP：设备离线，消息直接丢弃");
        }

        DeviceMessage message = new DeviceMessage();
        message.setMessageKey(UUID.randomUUID().toString());
        message.setDeviceId(request.getDeviceId());
        message.setTopic(request.getTopic());
        message.setSeq(request.getSeq());
        message.setPriority(request.getPriority());
        message.setDedupKey(request.getDedupKey());
        message.setPayload(request.getPayload());
        message.setStatus(MessageStatus.PENDING);
        message.setReceivedAt(now);
        message.setDispatchedCount(0);

        // 全局容量：先驱逐最低优先级、最旧、非 in-flight 的待发记录；无候选则拒绝。
        while (messageRepository.activeCount() >= properties.getCapacity()) {
            DeviceMessage victim = messageRepository.findEvictionCandidate();
            if (victim == null) {
                auditRepository.record(AuditEventType.REJECT_CAPACITY, request.getDedupKey(),
                        request.getDeviceId(), request.getTopic(), request.getSeq().longValue(),
                        request.getPriority(),
                        "容量 " + properties.getCapacity() + " 已满且全部在途，无法驱逐，拒绝新消息", now);
                return IngestResponse.rejected(request.getDedupKey(),
                        "缓存容量不足且无可用驱逐记录，消息被拒绝");
            }
            messageRepository.deleteById(victim.getId());
            auditRepository.record(AuditEventType.EVICT_CAPACITY, victim,
                    "容量不足，驱逐最低优先级中最旧的待发记录以为新消息 "
                            + request.getDedupKey() + " 腾出空间", now);
        }

        try {
            messageRepository.insert(message);
        } catch (DuplicateKeyException e) {
            // 并发写入相同 dedupKey：唯一约束兜底，视为重复。
            return IngestResponse.duplicate(request.getDedupKey());
        }

        enforceTopicRetention(policy, message, now);
        return IngestResponse.accepted(request.getDedupKey());
    }

    /**
     * Topic 级保留策略：新消息写入后，LATEST 只保留它本身，
     * BOUNDED(n) 最多保留 n 条，挤出的最旧记录写覆盖/驱逐审计。
     */
    private void enforceTopicRetention(RetentionPolicy policy, DeviceMessage incoming, long now) {
        switch (policy.kind()) {
            case LATEST -> evictTopicOverflow(incoming, 1, AuditEventType.OVERWRITE_LATEST,
                    "LATEST 策略：被更新消息 " + incoming.getDedupKey() + " 覆盖", now);
            case BOUNDED -> evictTopicOverflow(incoming, policy.bound(), AuditEventType.EVICT_BOUNDED,
                    "BOUNDED(" + policy.bound() + ") 策略：超出上限，最旧待发记录被挤出", now);
            case DROP, FIFO -> {
                // DROP 在离线入口已处理；FIFO 完整保留。
            }
        }
    }

    private void evictTopicOverflow(DeviceMessage incoming, int bound, AuditEventType eventType,
                                    String reason, long now) {
        while (messageRepository.countPendingByTopic(incoming.getTopic()) > bound) {
            DeviceMessage oldest = messageRepository.findOldestPendingByTopic(incoming.getTopic());
            if (oldest == null || oldest.getId() == incoming.getId()) {
                return;
            }
            messageRepository.deleteById(oldest.getId());
            auditRepository.record(eventType, oldest, reason, now);
        }
    }

    /**
     * 处理一个上游 ACK：仅精确确认 in-flight 的目标记录。
     * 重复 ACK、乱序 ACK（目标仍是 PENDING）或未知 dedupKey 均不改计数。
     */
    @Transactional
    public AckResponse acknowledge(AckRequest request) {
        long now = clock.nowMillis();
        String dedupKey = request.getDedupKey();
        boolean matched = messageRepository.acknowledgeIfInFlight(dedupKey, now);
        if (matched) {
            DeviceMessage message = messageRepository.findByDedupKey(dedupKey);
            auditRepository.record(AuditEventType.ACK, message, "收到上游 ACK，消息确认完成", now);
            return AckResponse.matched(dedupKey);
        }
        auditRepository.record(AuditEventType.ACK_DUPLICATE, dedupKey, null, null, null, null,
                "重复、乱序或无法匹配的 ACK，忽略且不改变计数", now);
        return AckResponse.duplicate(dedupKey);
    }

    /**
     * 执行一轮补发：先重置超时 in-flight，再按优先级降序、设备本地序号升序
     * 原子抢占并发送。发送失败的记录立即回退为待发，等待后续重试。
     *
     * @return 本轮成功发送到上游适配器的条数
     */
    public int dispatchOnce() {
        if (!online) {
            return 0;
        }
        long now = clock.nowMillis();

        List<DeviceMessage> timedOut = messageRepository.resetTimedOutInFlight(now);
        for (DeviceMessage m : timedOut) {
            auditRepository.record(AuditEventType.TIMEOUT_RETRY, m,
                    "in-flight 超过 ACK 超时 " + properties.getAckTimeoutMs() + "ms，回到待发重试", now);
        }

        List<DeviceMessage> pending = messageRepository.findPendingOrdered(properties.getDispatchBatchSize());
        int sent = 0;
        for (DeviceMessage candidate : pending) {
            long deadline = now + properties.getAckTimeoutMs();
            if (!messageRepository.markInFlightIfPending(candidate.getId(), deadline, now)) {
                // 已被其他调度线程抢占或已确认，跳过。
                continue;
            }
            DeviceMessage locked = messageRepository.findByDedupKey(candidate.getDedupKey());
            try {
                upstreamAdapter.send(locked);
                auditRepository.record(AuditEventType.DISPATCH, locked,
                        "第 " + locked.getDispatchedCount() + " 次补发给上游", now);
                sent++;
            } catch (Exception e) {
                log.warn("补发失败，回退待发: dedupKey={}, reason={}", locked.getDedupKey(), e.getMessage());
                messageRepository.revertToPendingAfterSendFailure(locked.getId());
            }
        }
        return sent;
    }

    /**
     * 崩溃/重启恢复：上次进程遗留的 in-flight 记录没有明确 ACK，
     * 全部重置为待发允许重试，并逐条写审计。
     *
     * @return 被重置的记录数
     */
    @Transactional
    public int recoverInFlightOnStartup() {
        long now = clock.nowMillis();
        List<DeviceMessage> inFlight = messageRepository.findAllInFlight();
        int reset = messageRepository.resetStaleInFlightOnStartup();
        for (DeviceMessage m : inFlight) {
            auditRepository.record(AuditEventType.RESET_INFLIGHT, m,
                    "服务重启恢复：未确认的在途消息重置为待发", now);
        }
        log.info("启动恢复完成，{} 条遗留 in-flight 消息已重置为待发", reset);
        return reset;
    }
}
