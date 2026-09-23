package com.example.starter.rosconfig.service;

import com.example.starter.rosconfig.repo.ConfigRepository;
import com.example.starter.rosconfig.repo.EventRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

/**
 * 事务原子提交边界：全部目标节点的版本自增、变更值落库与唯一一条事务事件必须同成同败。
 */
@Service
public class ConfigCommitService {

    private final ConfigRepository configRepository;
    private final EventRepository eventRepository;

    public ConfigCommitService(ConfigRepository configRepository, EventRepository eventRepository) {
        this.configRepository = configRepository;
        this.eventRepository = eventRepository;
    }

    /**
     * 原子提交结果。
     *
     * @param eventCursor 写入的单调事件游标
     * @param newVersions 各节点提交后的新版本
     */
    public record CommitOutcome(long eventCursor, Map<String, Long> newVersions) {
    }

    /** 根据提交时才生成的事件游标与新版本组装最终响应 JSON。 */
    @FunctionalInterface
    public interface ResponseAssembler {
        String assemble(long eventCursor, Map<String, Long> newVersions);
    }

    /**
     * 抢占幂等键：插入 PROCESSING 记录。
     *
     * @return true 表示抢占成功；false 表示同事务 ID 已存在
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public boolean claim(String transactionId, String requestHash, long now) {
        return eventRepository.tryInsertProcessing(transactionId, requestHash, now);
    }

    /**
     * 在单个数据库事务内：条件自增每个节点版本、写入变更值、写入唯一事务事件，
     * 最后把幂等记录推进到 COMMITTED 并保存响应。任一节点版本条件不满足则整笔回滚。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public CommitOutcome commit(String transactionId,
                                Map<String, Long> expectedVersions,
                                Map<String, Map<String, String>> changedValueTexts,
                                String eventPayload,
                                String requestHash,
                                long now,
                                ResponseAssembler responseAssembler) {
        Map<String, Long> newVersions = new java.util.LinkedHashMap<>();
        for (Map.Entry<String, Long> entry : expectedVersions.entrySet()) {
            String node = entry.getKey();
            long expected = entry.getValue();
            configRepository.getOrInitVersion(node);
            int rows = configRepository.incrementVersionIfMatches(node, expected);
            if (rows == 0) {
                throw new VersionConflictAtCommitException(node, expected);
            }
            newVersions.put(node, expected + 1);
        }

        for (Map.Entry<String, Map<String, String>> nodeEntry : changedValueTexts.entrySet()) {
            String node = nodeEntry.getKey();
            for (Map.Entry<String, String> valueEntry : nodeEntry.getValue().entrySet()) {
                configRepository.upsertValue(node, valueEntry.getKey(),
                        ConfigRepository.SOURCE_COMMITTED, valueEntry.getValue());
            }
        }

        long cursor = eventRepository.insertEvent(transactionId,
                EventRepository.EVENT_COMMITTED, eventPayload, now);

        String responseJson = responseAssembler.assemble(cursor, newVersions);
        eventRepository.finishRecord(transactionId, EventRepository.STATUS_COMMITTED, responseJson, now);
        return new CommitOutcome(cursor, newVersions);
    }

    /**
     * 持久化一次失败处理的终态记录（节点拒绝、超时、提交期版本冲突等），独立事务。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public void recordFailure(String transactionId, String requestHash, String responseJson, long now) {
        eventRepository.finishRecord(transactionId, EventRepository.STATUS_REJECTED, responseJson, now);
    }

    /** 提交阶段发现版本已被其他事务推进。 */
    public static class VersionConflictAtCommitException extends RuntimeException {
        private final String node;
        private final long expectedVersion;

        public VersionConflictAtCommitException(String node, long expectedVersion) {
            super("version conflict at commit for node " + node + ", expected " + expectedVersion);
            this.node = node;
            this.expectedVersion = expectedVersion;
        }

        public String node() {
            return node;
        }

        public long expectedVersion() {
            return expectedVersion;
        }
    }
}
