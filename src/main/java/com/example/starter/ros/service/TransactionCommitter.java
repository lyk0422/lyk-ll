package com.example.starter.ros.service;

import com.example.starter.ros.dto.HotUpdateRequest;
import com.example.starter.ros.dto.HotUpdateResponse;
import com.example.starter.ros.dto.ParamEvent;
import com.example.starter.ros.repo.EventJdbcRepository;
import com.example.starter.ros.repo.ParameterJdbcRepository;
import com.example.starter.ros.repo.TransactionJdbcRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 提交边界：在单一数据库事务内完成
 * 行锁版本核对 -> 写入各节点参数值 -> 推进各节点单调版本 ->
 * 追加一条事务事件 -> 登记幂等事务记录。任何异常整体回滚。
 */
@Component
public class TransactionCommitter {

    private final ParameterJdbcRepository parameterRepository;
    private final EventJdbcRepository eventRepository;
    private final TransactionJdbcRepository transactionRepository;
    private final ObjectMapper objectMapper;

    public TransactionCommitter(ParameterJdbcRepository parameterRepository,
                                EventJdbcRepository eventRepository,
                                TransactionJdbcRepository transactionRepository,
                                ObjectMapper objectMapper) {
        this.parameterRepository = parameterRepository;
        this.eventRepository = eventRepository;
        this.transactionRepository = transactionRepository;
        this.objectMapper = objectMapper;
    }

    /**
     * 原子提交；行锁内版本冲突抛 {@link HotUpdateServiceImpl.CommitVersionConflictException} 由上层回滚处理。
     */
    @Transactional(rollbackFor = Exception.class)
    public HotUpdateServiceImpl.CommitOutcome commit(
            HotUpdateRequest request,
            String requestFingerprint,
            Map<String, Map<String, Object>> candidatesByNode,
            long nowMs) {

        // 1. 固定加锁顺序（节点名排序），避免跨节点事务互锁
        List<String> orderedNodes = request.changes().stream()
                .map(HotUpdateRequest.NodeChangeSet::node)
                .sorted()
                .toList();

        Map<String, Long> expectedByNode = new LinkedHashMap<>();
        for (HotUpdateRequest.NodeChangeSet change : request.changes()) {
            expectedByNode.put(change.node(), change.expectedVersion());
        }

        // 2. 行锁内核对版本
        for (String node : orderedNodes) {
            long lockedVersion = parameterRepository.currentVersionForUpdate(node);
            long expected = expectedByNode.get(node);
            if (lockedVersion != expected) {
                throw new HotUpdateServiceImpl.CommitVersionConflictException(
                        "节点 " + node + " 行锁版本 " + lockedVersion + " 与期望版本 " + expected + " 不一致");
            }
        }

        // 3. 写入各节点变更值并推进版本
        Map<String, Long> newVersions = new LinkedHashMap<>();
        for (HotUpdateRequest.NodeChangeSet change : request.changes()) {
            String node = change.node();
            if (change.parameters() != null) {
                change.parameters().forEach((group, params) -> {
                    if (params == null) {
                        return;
                    }
                    Map<String, String> stored = new LinkedHashMap<>();
                    params.forEach((name, raw) -> stored.put(name, raw == null ? null : String.valueOf(raw)));
                    if (!stored.isEmpty()) {
                        parameterRepository.upsertValuesWithVersion(node, group, stored, expectedByNode.get(node) + 1);
                    }
                });
            }
            parameterRepository.incrementVersion(node, nowMs);
            newVersions.put(node, parameterRepository.currentVersion(node));
        }

        // 4. 追加一条事务级事件（节点列为 null 表示整笔事务汇总）
        Map<String, Object> eventPayload = new LinkedHashMap<>();
        eventPayload.put("transactionId", request.transactionId());
        List<Map<String, Object>> nodeSummaries = new ArrayList<>();
        for (HotUpdateRequest.NodeChangeSet change : request.changes()) {
            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("node", change.node());
            summary.put("version", newVersions.get(change.node()));
            // 事件内容只包含本次真正写入的变更键，避免泄露全量默认值
            summary.put("changes", change.parameters() == null ? Map.of() : change.parameters());
            nodeSummaries.add(summary);
        }
        eventPayload.put("nodes", nodeSummaries);

        String payloadJson = writeJson(eventPayload);
        ParamEvent event = eventRepository.append(
                request.transactionId(), ParamEvent.TYPE_COMMITTED, null, payloadJson, nowMs);

        // 5. 组装响应并登记幂等终态（与值、版本、事件同事务，失败一并回滚）
        List<HotUpdateResponse.NodeResult> results = new ArrayList<>();
        for (HotUpdateRequest.NodeChangeSet change : request.changes()) {
            results.add(new HotUpdateResponse.NodeResult(
                    change.node(), HotUpdateResponse.NodeResult.ACCEPTED, null,
                    newVersions.get(change.node())));
        }
        HotUpdateResponse response = new HotUpdateResponse(
                request.transactionId(), true, false, results);
        transactionRepository.insertIfAbsent(
                request.transactionId(), true, requestFingerprint, writeJson(response), nowMs);

        return new HotUpdateServiceImpl.CommitOutcome(response, candidatesByNode);
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("序列化提交内容失败", e);
        }
    }
}
