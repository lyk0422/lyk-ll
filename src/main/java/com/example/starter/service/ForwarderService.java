package com.example.starter.service;

import com.example.starter.adapter.SyntheticUpstreamAdapter;
import com.example.starter.adapter.UpstreamAdapter;
import com.example.starter.config.ForwarderProperties;
import com.example.starter.dto.IngestResult;
import com.example.starter.dto.MessageRequest;
import com.example.starter.dto.TopicPolicyRequest;
import com.example.starter.enums.AuditEventType;
import com.example.starter.enums.MessageStatus;
import com.example.starter.enums.RetentionStrategy;
import com.example.starter.exception.BadRequestException;
import com.example.starter.exception.RejectedException;
import com.example.starter.model.DeviceMessage;
import com.example.starter.model.TopicPolicy;
import com.example.starter.repository.AuditRepository;
import com.example.starter.repository.MessageRepository;
import com.example.starter.repository.TopicPolicyRepository;
import jakarta.annotation.PostConstruct;
import java.time.Clock;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 转发核心服务：离线缓存、策略处置、补发调度、ACK 与恢复。
 */
@Service
public class ForwarderService implements AckSink {

    /** 单轮补发最多领取条数。 */
    private static final int DISPATCH_BATCH_LIMIT = 100;

    private static final Logger log = LoggerFactory.getLogger(ForwarderService.class);

    private final ForwarderProperties properties;
    private final UpstreamAdapter upstreamAdapter;
    private final SyntheticUpstreamAdapter syntheticAdapter;
    private final MessageRepository messageRepository;
    private final TopicPolicyRepository topicPolicyRepository;
    private final AuditRepository auditRepository;
    private final AuditService auditService;
    private final Clock clock;
    private final TransactionTemplate transactionTemplate;

    /** 同一 dedupKey 的进程内条带锁，配合数据库唯一约束兜底并发重复写入。 */
    private final ConcurrentHashMap<String, Object> keyLocks = new ConcurrentHashMap<>();

    public ForwarderService(ForwarderProperties properties,
                            UpstreamAdapter upstreamAdapter,
                            MessageRepository messageRepository,
                            TopicPolicyRepository topicPolicyRepository,
                            AuditRepository auditRepository,
                            AuditService auditService,
                            Clock clock,
                            TransactionTemplate transactionTemplate) {
        this.properties = properties;
        this.upstreamAdapter = upstreamAdapter;
        this.syntheticAdapter = (upstreamAdapter instanceof SyntheticUpstreamAdapter synthetic)
                ? synthetic : null;
        this.messageRepository = messageRepository;
        this.topicPolicyRepository = topicPolicyRepository;
        this.auditRepository = auditRepository;
        this.auditService = auditService;
        this.clock = clock;
        this.transactionTemplate = transactionTemplate;
    }

    @PostConstruct
    void registerAckSink() {
        if (syntheticAdapter != null) {
            syntheticAdapter.setAckSink(this);
        }
    }

    /**
     * 接收一条设备消息并完成去重、离线策略与容量处置。
     * 对同一 dedupKey 加条带锁，并在锁内执行完整事务（含提交），保证并发上报幂等；
     * 数据库唯一约束作为跨线程/跨进程兜底。
     */
    public IngestResult ingest(MessageRequest request) {
        Object lock = keyLocks.computeIfAbsent(request.getDedupKey(), key -> new Object());
        try {
            synchronized (lock) {
                return transactionTemplate.execute(status -> doIngest(request));
            }
        } finally {
            keyLocks.remove(request.getDedupKey());
        }
    }

    private IngestResult doIngest(MessageRequest request) {
        long now = clock.millis();

        DeviceMessage existing = messageRepository.findByMessageId(request.getDedupKey());
        if (existing != null) {
            // 同一 dedupKey 只保留一条记录，重复上报幂等忽略。
            return new IngestResult(IngestResult.DUPLICATE, existing);
        }

        TopicPolicy policy = topicPolicyRepository.findByTopic(request.getTopic());
        RetentionStrategy strategy = policy == null ? RetentionStrategy.FIFO : policy.getStrategy();
        boolean online = upstreamAdapter.isOnline();

        DeviceMessage message = newMessage(request, now);

        if (!online && strategy == RetentionStrategy.DROP) {
            // 策略丢弃必须审计，且不能伪装成已转发：不落库、不计入已转发。
            auditService.recordInNewTransaction(AuditEventType.DROP_POLICY, message,
                    "离线期间 DROP 策略直接丢弃，topic=" + request.getTopic(), now);
            return new IngestResult(IngestResult.DROPPED, message);
        }

        if (!online) {
            applyTopicRetention(strategy, policy, message, now);
        }

        ensureCapacity(message, now);
        try {
            messageRepository.insert(message);
        } catch (DuplicateKeyException ex) {
            // 唯一约束兜底：并发/跨进程重复 dedupKey 只保留一条。
            DeviceMessage raced = messageRepository.findByMessageId(request.getDedupKey());
            return new IngestResult(IngestResult.DUPLICATE, raced == null ? message : raced);
        }
        return new IngestResult(IngestResult.ACCEPTED, message);
    }

    /** 配置 Topic 保留策略。 */
    public TopicPolicy configurePolicy(String topic, TopicPolicyRequest request) {
        long now = clock.millis();
        RetentionStrategy strategy;
        try {
            strategy = RetentionStrategy.valueOf(request.getStrategy().trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            throw new BadRequestException(
                    "未知策略: " + request.getStrategy() + "，支持 DROP/LATEST/FIFO/BOUNDED");
        }
        Integer bound = null;
        if (strategy == RetentionStrategy.BOUNDED) {
            if (request.getBound() == null || request.getBound() <= 0) {
                throw new BadRequestException("BOUNDED 策略必须指定正整数 bound");
            }
            bound = request.getBound();
        }
        TopicPolicy policy = topicPolicyRepository.findByTopic(topic);
        if (policy == null) {
            policy = new TopicPolicy();
            policy.setTopic(topic);
            policy.setCreatedAt(now);
        }
        policy.setStrategy(strategy);
        policy.setBound(bound);
        policy.setUpdatedAt(now);
        topicPolicyRepository.upsert(policy);
        return policy;
    }

    /** 查询 Topic 保留策略。 */
    public TopicPolicy getPolicy(String topic) {
        return topicPolicyRepository.findByTopic(topic);
    }

    @Override
    public boolean ack(String dedupKey) {
        long now = clock.millis();
        boolean confirmed = messageRepository.confirmAcked(dedupKey, now);
        if (!confirmed) {
            // 重复 ACK、游离 ACK：不改变任何计数与状态。
            log.debug("ACK 未产生新确认（重复或消息不存在）: {}", dedupKey);
        }
        return confirmed;
    }

    /**
     * 执行一轮补发：回收超时 in-flight，领取 PENDING 批次并投递。
     * 断网时不领取新消息；投递失败的消息立即回退为 PENDING。
     *
     * @return 本轮成功投递给上游的条数
     */
    public int dispatchOnce() {
        long now = clock.millis();
        int reverted = messageRepository.revertTimedOutInflight(now, properties.getInflightTimeoutMs());
        if (reverted > 0) {
            log.info("回收超时未 ACK 的 in-flight 消息 {} 条，允许重新投递", reverted);
        }
        if (!upstreamAdapter.isOnline()) {
            return 0;
        }
        List<DeviceMessage> batch = messageRepository.claimPendingBatch(DISPATCH_BATCH_LIMIT, now);
        int delivered = 0;
        for (DeviceMessage message : batch) {
            boolean ok;
            try {
                ok = upstreamAdapter.deliver(message);
            } catch (RuntimeException ex) {
                log.warn("投递异常，消息回退待发: {}", message.getMessageId(), ex);
                ok = false;
            }
            if (ok) {
                message.setStatus(MessageStatus.INFLIGHT);
                message.setForwardedAt(now);
                messageRepository.updateStatus(message);
                delivered++;
            } else {
                // 链路抖动导致发送失败：回退 PENDING，等待下一轮补发，不计为已转发。
                message.setStatus(MessageStatus.PENDING);
                message.setInflightAt(null);
                messageRepository.updateStatus(message);
            }
        }
        return delivered;
    }

    /** 快照当前待发消息（按补发顺序），供状态查询与测试使用。 */
    public List<DeviceMessage> snapshotPending() {
        return messageRepository.findPendingSnapshot();
    }

    private void applyTopicRetention(RetentionStrategy strategy, TopicPolicy policy,
                                     DeviceMessage incoming, long now) {
        switch (strategy) {
            case LATEST -> {
                // 只保留最新一条：删除该主题下全部待发旧消息，每条覆盖均写审计。
                DeviceMessage oldest;
                while ((oldest = messageRepository.findOldestByTopicAndStatus(
                        incoming.getTopic(), MessageStatus.PENDING)) != null) {
                    messageRepository.deleteById(oldest.getId());
                    auditRepository.record(AuditEventType.OVERWRITE_LATEST, oldest,
                            "离线期间 LATEST 策略保留最新消息，旧消息被覆盖", now);
                }
            }
            case BOUNDED -> {
                int bound = policy.getBound();
                while (messageRepository.countPendingByTopic(incoming.getTopic()) >= bound) {
                    DeviceMessage victim =
                            messageRepository.deleteOldestPendingByTopic(incoming.getTopic());
                    if (victim == null) {
                        break;
                    }
                    auditRepository.record(AuditEventType.EVICT_BOUNDED, victim,
                            "离线期间 BOUNDED(" + bound + ") 策略驱逐主题内最旧待发消息", now);
                }
            }
            case FIFO, DROP -> {
                // FIFO 完整保留；DROP 在进入此方法前已处理。
            }
        }
    }

    private void ensureCapacity(DeviceMessage incoming, long now) {
        int capacity = properties.getQueueCapacity();
        if (capacity <= 0) {
            return;
        }
        while (messageRepository.countActive() >= capacity) {
            // 先驱逐最低优先级中最旧且非 in-flight 的记录。
            DeviceMessage victim = messageRepository.evictLowestPriorityOldest();
            if (victim == null) {
                // 拒绝会导致当前事务回滚，审计必须在独立事务中留存。
                auditService.recordInNewTransaction(AuditEventType.REJECT_CAPACITY, incoming,
                        "队列容量 " + capacity
                                + " 已满且仅存 in-flight 消息，无可驱逐记录，拒绝新消息", now);
                throw new RejectedException("待发队列容量已满，暂时拒绝新消息: "
                        + incoming.getMessageId());
            }
            auditRepository.record(AuditEventType.EVICT_CAPACITY, victim,
                    "队列容量 " + capacity + " 已满，驱逐最低优先级中最旧的非 in-flight 消息", now);
        }
    }

    private DeviceMessage newMessage(MessageRequest request, long now) {
        DeviceMessage message = new DeviceMessage();
        message.setMessageId(request.getDedupKey());
        message.setDeviceId(request.getDeviceId());
        message.setTopic(request.getTopic());
        message.setSeq(request.getSeq());
        message.setPriority(request.getPriority());
        message.setPayload(request.getPayload());
        message.setStatus(MessageStatus.PENDING);
        message.setAttempts(0);
        message.setEnqueuedAt(now);
        return message;
    }
}
