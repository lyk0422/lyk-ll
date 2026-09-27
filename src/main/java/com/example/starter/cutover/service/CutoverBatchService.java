package com.example.starter.cutover.service;

import com.example.starter.cutover.BusinessRuleException;
import com.example.starter.cutover.ConflictException;
import com.example.starter.cutover.EventType;
import com.example.starter.cutover.NotFoundException;
import com.example.starter.cutover.RequestStatus;
import com.example.starter.cutover.dto.BatchEdgeRef;
import com.example.starter.cutover.dto.BatchEdgeView;
import com.example.starter.cutover.dto.BatchMemberRef;
import com.example.starter.cutover.dto.BatchMemberView;
import com.example.starter.cutover.dto.BatchResult;
import com.example.starter.cutover.dto.BatchView;
import com.example.starter.cutover.dto.BlockedMemberView;
import com.example.starter.cutover.dto.PublishBatchRequest;
import com.example.starter.cutover.model.CutoverBatchEdgePO;
import com.example.starter.cutover.model.CutoverBatchMemberPO;
import com.example.starter.cutover.model.CutoverBatchPO;
import com.example.starter.cutover.model.CutoverEventPO;
import com.example.starter.cutover.model.CutoverRequestPO;
import com.example.starter.cutover.repo.CutoverRepository;
import com.example.starter.cutover.support.FingerprintUtil;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 跨设备割接依赖批次服务。
 *
 * <p>关键口径：</p>
 * <ul>
 *   <li>发布原子冻结成员、依赖边与窗口快照；成员必须唯一且全部 APPROVED（已批准未开始），
 *       expectedVersion 与当前版本一致；自环、环路、边端点越界、已开始、版本不符整批拒绝，
 *       失败不产生任何记录（失败不占键）；</li>
 *   <li>requestId 幂等：指纹覆盖成员版本集合、依赖边集合与精确发布时刻（集合先规范化排序，
 *       结构化 JSON 序列化保证含分隔符文本亦无歧义），同键同参重放首次冻结结果，异参 409；</li>
 *   <li>一项申请仅在 APPROVED 时可入批，入批后不可再回到 APPROVED，故一项申请至多属于一个
 *       批次（天然满足“最多属于一个未结束批次”），由 cutover_batch_member.request_id 唯一约束兜底；</li>
 *   <li>成员校验与绑定在申请行锁（按编号升序 FOR UPDATE）内完成，与单申请启动只形成一致顺序。</li>
 * </ul>
 */
@Service
public class CutoverBatchService {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final CutoverRepository repo;

    public CutoverBatchService(CutoverRepository repo) {
        this.repo = repo;
    }

    // ---------------- 批次发布 ----------------

    @Transactional
    public BatchResult publish(PublishBatchRequest req) {
        String fp = fingerprintBatch(req);
        CutoverBatchPO existed = repo.findBatch(req.requestId());
        if (existed != null) {
            return resolveReplay(existed, fp);
        }

        // 成员唯一性（在加锁前快速失败，不产生任何记录）。
        List<BatchMemberRef> members = req.members().stream()
                .sorted(Comparator.comparing(BatchMemberRef::requestId)).toList();
        Set<String> memberIds = new LinkedHashSet<>();
        for (BatchMemberRef m : members) {
            if (!memberIds.add(m.requestId())) {
                throw new BusinessRuleException("批次成员必须唯一，重复成员: " + m.requestId());
            }
        }

        // 依赖边结构校验：两端在批内、禁止自环、禁止环路。
        List<BatchEdgeRef> edges = req.edges().stream()
                .sorted(Comparator.comparing(BatchEdgeRef::fromRequestId)
                        .thenComparing(BatchEdgeRef::toRequestId)).toList();
        for (BatchEdgeRef e : edges) {
            if (e.fromRequestId().equals(e.toRequestId())) {
                throw new BusinessRuleException("依赖边不允许自环: " + e.fromRequestId());
            }
            if (!memberIds.contains(e.fromRequestId())
                    || !memberIds.contains(e.toRequestId())) {
                throw new BusinessRuleException("依赖边两端都必须在批内: "
                        + e.fromRequestId() + " -> " + e.toRequestId());
            }
        }
        assertAcyclic(memberIds, edges);

        // 按申请编号升序锁定全部成员行，与单申请启动/前置失败传播串行化。
        List<String> sortedIds = List.copyOf(memberIds);
        Map<String, CutoverRequestPO> locked = new HashMap<>();
        for (CutoverRequestPO po : repo.lockRequestsSorted(sortedIds)) {
            locked.put(po.getRequestId(), po);
        }

        // 拿锁后复查：并发同 requestId 的首个发布事务可能已提交。
        CutoverBatchPO afterLock = repo.findBatch(req.requestId());
        if (afterLock != null) {
            return resolveReplay(afterLock, fp);
        }

        // 成员状态与版本校验：任一不符整批拒绝，事务回滚不占键。
        for (BatchMemberRef m : members) {
            CutoverRequestPO po = locked.get(m.requestId());
            if (po == null) {
                throw new NotFoundException("批次成员割接申请不存在: " + m.requestId());
            }
            if (!RequestStatus.APPROVED.name().equals(po.getStatus())) {
                throw new BusinessRuleException("成员 " + m.requestId() + " 当前状态 "
                        + po.getStatus() + "，只有已批准未开始（APPROVED）的申请可入批，整批拒绝");
            }
            if (po.getVersion() != m.expectedVersion()) {
                throw new BusinessRuleException("成员 " + m.requestId() + " 期望版本 "
                        + m.expectedVersion() + " 与当前版本 " + po.getVersion()
                        + " 不符，整批拒绝");
            }
            if (po.getBatchId() != null) {
                throw new BusinessRuleException("成员 " + m.requestId() + " 已绑定批次 "
                        + po.getBatchId() + "，一项申请最多属于一个未结束批次，整批拒绝");
            }
        }

        // 原子冻结：批次、成员（窗口快照）、依赖边、成员绑定（版本加 1）同事务提交。
        LocalDateTime now = LocalDateTime.now();
        String message = "批次已发布并冻结：成员 " + members.size() + " 个，依赖边 "
                + edges.size() + " 条，冻结时刻 " + req.publishedAt();
        CutoverBatchPO batch = new CutoverBatchPO();
        batch.setBatchId(req.requestId());
        batch.setFingerprint(fp);
        batch.setPublishedAt(req.publishedAt());
        batch.setResultSnapshot(writeSnapshot(message));
        batch.setCreatedAt(now);
        try {
            repo.insertBatch(batch);
        } catch (DuplicateKeyException dup) {
            CutoverBatchPO winner = repo.findBatch(req.requestId());
            if (winner != null) {
                return resolveReplay(winner, fp);
            }
            throw dup;
        }
        for (BatchMemberRef m : members) {
            CutoverRequestPO po = locked.get(m.requestId());
            CutoverBatchMemberPO member = new CutoverBatchMemberPO();
            member.setBatchId(req.requestId());
            member.setRequestId(m.requestId());
            member.setExpectedVersion(m.expectedVersion());
            member.setWindowStart(po.getWindowStart());
            member.setWindowEnd(po.getWindowEnd());
            member.setCreatedAt(now);
            repo.insertBatchMember(member);
            repo.bindBatch(m.requestId(), req.requestId(), now);
            insertEvent("BATCH-BIND-" + req.requestId() + "-" + m.requestId(),
                    m.requestId(), EventType.BATCH_BIND,
                    FingerprintUtil.sha256("BATCH_BIND", req.requestId(), m.requestId()),
                    "申请绑定批次 " + req.requestId() + "，窗口快照 ["
                            + po.getWindowStart() + "," + po.getWindowEnd() + ")");
        }
        for (BatchEdgeRef e : edges) {
            CutoverBatchEdgePO edge = new CutoverBatchEdgePO();
            edge.setBatchId(req.requestId());
            edge.setFromRequestId(e.fromRequestId());
            edge.setToRequestId(e.toRequestId());
            edge.setCreatedAt(now);
            repo.insertBatchEdge(edge);
        }
        return new BatchResult(false, message, toView(req.requestId()));
    }

    // ---------------- 拓扑与阻断查询 ----------------

    /** 批次拓扑查询：成员按申请编号稳定升序，依赖边按 (from,to) 升序。 */
    @Transactional(readOnly = true)
    public BatchView getBatch(String batchId) {
        if (repo.findBatch(batchId) == null) {
            throw new NotFoundException("割接依赖批次不存在: " + batchId);
        }
        return toView(batchId);
    }

    /**
     * 阻断明细：列出全部依赖阻断成员，保留首个阻断来源（blocked_by），
     * 并给出当前处于回退/超期/阻断状态的直接前置集合（按申请编号升序）。
     */
    @Transactional(readOnly = true)
    public List<BlockedMemberView> getBlocks(String batchId) {
        if (repo.findBatch(batchId) == null) {
            throw new NotFoundException("割接依赖批次不存在: " + batchId);
        }
        List<CutoverBatchEdgePO> edges = repo.listBatchEdges(batchId);
        List<BlockedMemberView> result = new ArrayList<>();
        for (CutoverBatchMemberPO member : repo.listBatchMembers(batchId)) {
            CutoverRequestPO po = repo.findRequest(member.getRequestId());
            if (po == null || !RequestStatus.DEPENDENCY_BLOCKED.name().equals(po.getStatus())) {
                continue;
            }
            List<String> sources = new ArrayList<>();
            for (CutoverBatchEdgePO edge : edges) {
                if (!edge.getToRequestId().equals(member.getRequestId())) {
                    continue;
                }
                CutoverRequestPO pre = repo.findRequest(edge.getFromRequestId());
                if (pre != null && (RequestStatus.ROLLED_BACK.name().equals(pre.getStatus())
                        || RequestStatus.WINDOW_EXPIRED.name().equals(pre.getStatus())
                        || RequestStatus.DEPENDENCY_BLOCKED.name().equals(pre.getStatus()))) {
                    sources.add(pre.getRequestId());
                }
            }
            result.add(new BlockedMemberView(member.getRequestId(),
                    member.getBlockedBy(), sources));
        }
        return result;
    }

    // ---------------- 内部方法 ----------------

    /**
     * 批次指纹：成员（申请编号+期望版本）与依赖边先按字典序规范化排序，
     * 再连同 requestId 与精确发布时刻做结构化 JSON 序列化取 SHA-256。
     * JSON 数组编码天然无分隔符歧义，合法文本含分隔符也能区分。
     */
    private String fingerprintBatch(PublishBatchRequest req) {
        List<Object> parts = new ArrayList<>();
        parts.add("BATCH_PUBLISH");
        parts.add(req.requestId());
        parts.add(req.publishedAt());
        req.members().stream()
                .sorted(Comparator.comparing(BatchMemberRef::requestId))
                .forEach(m -> {
                    parts.add(m.requestId());
                    parts.add(m.expectedVersion());
                });
        parts.add("EDGES");
        req.edges().stream()
                .sorted(Comparator.comparing(BatchEdgeRef::fromRequestId)
                        .thenComparing(BatchEdgeRef::toRequestId))
                .forEach(e -> {
                    parts.add(e.fromRequestId());
                    parts.add(e.toRequestId());
                });
        return FingerprintUtil.sha256(parts.toArray());
    }

    /** Kahn 拓扑排序判环：无法排出全部节点即存在环路，整批拒绝。 */
    private void assertAcyclic(Set<String> memberIds, List<BatchEdgeRef> edges) {
        Map<String, Integer> indegree = new HashMap<>();
        Map<String, List<String>> adjacency = new HashMap<>();
        for (String id : memberIds) {
            indegree.put(id, 0);
            adjacency.put(id, new ArrayList<>());
        }
        Set<BatchEdgeRef> seenEdges = new HashSet<>();
        for (BatchEdgeRef e : edges) {
            if (seenEdges.add(e)) {
                indegree.merge(e.toRequestId(), 1, Integer::sum);
                adjacency.get(e.fromRequestId()).add(e.toRequestId());
            }
        }
        Deque<String> queue = new ArrayDeque<>();
        indegree.forEach((id, d) -> {
            if (d == 0) {
                queue.add(id);
            }
        });
        int visited = 0;
        while (!queue.isEmpty()) {
            String cur = queue.poll();
            visited++;
            for (String next : adjacency.get(cur)) {
                if (indegree.merge(next, -1, Integer::sum) == 0) {
                    queue.add(next);
                }
            }
        }
        if (visited != memberIds.size()) {
            throw new BusinessRuleException("批次依赖边存在环路，整批拒绝");
        }
    }

    private BatchResult resolveReplay(CutoverBatchPO existed, String fp) {
        if (!existed.getFingerprint().equals(fp)) {
            throw new ConflictException("requestId=" + existed.getBatchId()
                    + " 已存在但业务参数不一致（同键异参），拒绝产生新记录");
        }
        return new BatchResult(true, "同键同参重放，返回首次冻结结果："
                + readSnapshotMessage(existed.getResultSnapshot()),
                toView(existed.getBatchId()));
    }

    private BatchView toView(String batchId) {
        CutoverBatchPO batch = repo.findBatch(batchId);
        List<BatchMemberView> members = repo.listBatchMembers(batchId).stream()
                .map(m -> {
                    CutoverRequestPO po = repo.findRequest(m.getRequestId());
                    return new BatchMemberView(m.getRequestId(), m.getExpectedVersion(),
                            po == null ? null : po.getVersion(),
                            po == null ? null : po.getStatus(),
                            m.getWindowStart(), m.getWindowEnd(), m.getBlockedBy());
                })
                .toList();
        List<BatchEdgeView> edges = repo.listBatchEdges(batchId).stream()
                .map(e -> new BatchEdgeView(e.getFromRequestId(), e.getToRequestId()))
                .toList();
        return new BatchView(batch.getBatchId(), batch.getPublishedAt(), members, edges);
    }

    private void insertEvent(String uid, String requestId, EventType type,
                             String fingerprint, String message) {
        CutoverEventPO po = new CutoverEventPO();
        po.setRequestUid(uid);
        po.setRequestId(requestId);
        po.setEventType(type.name());
        po.setSeqNo(null);
        po.setPayloadFingerprint(fingerprint);
        po.setResultSnapshot(writeSnapshot(message));
        po.setLateFlag(false);
        po.setCreatedAt(LocalDateTime.now());
        repo.insertEvent(po);
    }

    private String writeSnapshot(String message) {
        try {
            return JSON.writeValueAsString(Map.of("message", message));
        } catch (Exception e) {
            return "{}";
        }
    }

    private String readSnapshotMessage(String snapshot) {
        if (snapshot == null || snapshot.isBlank()) {
            return "";
        }
        try {
            return JSON.readValue(snapshot, new TypeReference<Map<String, String>>() {
            }).getOrDefault("message", "");
        } catch (Exception e) {
            return "";
        }
    }
}
