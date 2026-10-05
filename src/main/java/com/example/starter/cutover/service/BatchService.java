package com.example.starter.cutover.service;

import com.example.starter.cutover.BatchStatus;
import com.example.starter.cutover.BusinessRuleException;
import com.example.starter.cutover.ConflictException;
import com.example.starter.cutover.NotFoundException;
import com.example.starter.cutover.RequestStatus;
import com.example.starter.cutover.dto.BatchActionResult;
import com.example.starter.cutover.dto.BatchEdgeRef;
import com.example.starter.cutover.dto.BatchEdgeView;
import com.example.starter.cutover.dto.BatchMemberRef;
import com.example.starter.cutover.dto.BatchMemberView;
import com.example.starter.cutover.dto.BatchView;
import com.example.starter.cutover.dto.BlockDetailView;
import com.example.starter.cutover.dto.PublishBatchRequest;
import com.example.starter.cutover.model.CutoverBatchEdgePO;
import com.example.starter.cutover.model.CutoverBatchMemberPO;
import com.example.starter.cutover.model.CutoverBatchPO;
import com.example.starter.cutover.model.CutoverBlockRecordPO;
import com.example.starter.cutover.model.CutoverRequestPO;
import com.example.starter.cutover.repo.BatchRepository;
import com.example.starter.cutover.repo.CutoverRepository;
import com.example.starter.cutover.support.FingerprintUtil;
import java.time.LocalDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 割接批次领域服务。
 *
 * <p>关键口径：</p>
 * <ul>
 *   <li>发布原子冻结成员、依赖边与窗口快照，并将成员绑定批次；成员须为已批准(APPROVED)
 *       但未开始的申请，版本号与 expectedVersion 一致，且不属于其他未结束批次；
 *       成员重复、边端点出批、自环、环路、已开始、版本不符均整批拒绝(422)，不产生任何记录；</li>
 *   <li>batchId 幂等：指纹覆盖 batchId、发布精确时刻、成员集合(含版本)与依赖边集合，
 *       同键同参重放首次冻结结果，同键异参 409，校验/业务失败不占键；</li>
 *   <li>批内成员启动门禁：全部直接前置须已验证通过并封板(SEALED)，否则返回等待、
 *       不写步骤也不占幂等键；</li>
 *   <li>前置回退/超期与状态提交同事务传播：尚未开始(APPROVED)的全部可达后继转
 *       DEPENDENCY_BLOCKED 并记录首个阻断来源集合；已开始成员按原回退流程处理，
 *       已封板结果不改；无关支路可继续；</li>
 *   <li>全部成员到达终态后批次关闭(CLOSED)，释放“一项申请最多属于一个未结束批次”约束；</li>
 *   <li>多行加锁一律按申请编号升序，拓扑/阻断查询按申请编号稳定排序。</li>
 * </ul>
 */
@Service
public class BatchService {

    /** 阻断来源状态 -> 阻断原因。 */
    private static final Map<String, String> BLOCK_CAUSE = Map.of(
            RequestStatus.ROLLED_BACK.name(), "ROLLBACK",
            RequestStatus.WINDOW_EXPIRED.name(), "WINDOW_EXPIRED",
            RequestStatus.DEPENDENCY_BLOCKED.name(), "DEPENDENCY_BLOCKED");

    private final BatchRepository batchRepo;
    private final CutoverRepository repo;

    public BatchService(BatchRepository batchRepo, CutoverRepository repo) {
        this.batchRepo = batchRepo;
        this.repo = repo;
    }

    // ---------------- 批次发布 ----------------

    @Transactional
    public BatchActionResult publish(PublishBatchRequest req) {
        List<BatchEdgeRef> edges = req.edges() == null ? List.of() : req.edges();
        List<BatchMemberRef> members = req.members();
        String fp = fingerprint(req.batchId(), req.publishedAt(), members, edges);

        CutoverBatchPO existed = batchRepo.findBatch(req.batchId());
        if (existed != null) {
            return resolveReplay(existed, fp);
        }

        // 结构校验（业务拒绝 422，整批不产生任何记录）。
        Set<String> memberIds = new LinkedHashSet<>();
        for (BatchMemberRef m : members) {
            if (!memberIds.add(m.requestId())) {
                throw new BusinessRuleException("批次成员必须唯一，重复成员: " + m.requestId());
            }
        }
        Set<String> edgeKeys = new LinkedHashSet<>();
        for (BatchEdgeRef e : edges) {
            if (e.predecessor().equals(e.successor())) {
                throw new BusinessRuleException("不允许自环依赖: " + e.predecessor());
            }
            if (!memberIds.contains(e.predecessor()) || !memberIds.contains(e.successor())) {
                throw new BusinessRuleException("依赖边两端都必须在批内: "
                        + e.predecessor() + " -> " + e.successor());
            }
            edgeKeys.add(e.predecessor() + "->" + e.successor());
        }
        if (edgeKeys.size() != edges.size()) {
            throw new BusinessRuleException("依赖边重复");
        }
        assertAcyclic(memberIds, edges);

        // 预检（无锁）：成员已属其他未结束批次时快速失败，减少与在途失败传播的锁竞争；
        // 拿锁后仍会复查，竞态不会漏判。
        for (String id : memberIds) {
            String active = batchRepo.findActiveBatchId(id);
            if (active != null) {
                throw new BusinessRuleException("成员 " + id
                        + " 已属于未结束批次 " + active + "，一项申请最多属于一个未结束批次");
            }
        }

        // 按申请编号升序锁定全部成员行，串行化并发发布/启动/失败传播。
        Map<String, CutoverRequestPO> locked = new HashMap<>();
        for (CutoverRequestPO po : repo.lockRequestsOrdered(List.copyOf(memberIds))) {
            locked.put(po.getRequestId(), po);
        }
        // 拿锁后复查：并发同 batchId 的首个事务可能已提交。
        CutoverBatchPO afterLock = batchRepo.findBatch(req.batchId());
        if (afterLock != null) {
            return resolveReplay(afterLock, fp);
        }

        LocalDateTime now = LocalDateTime.now();
        for (BatchMemberRef m : members) {
            CutoverRequestPO po = locked.get(m.requestId());
            if (po == null) {
                throw new NotFoundException("割接申请不存在: " + m.requestId());
            }
            if (!RequestStatus.APPROVED.name().equals(po.getStatus())) {
                throw new BusinessRuleException("成员 " + m.requestId()
                        + " 须为已批准但未开始(APPROVED)状态，当前状态 " + po.getStatus());
            }
            if (!po.getVersion().equals(m.expectedVersion())) {
                throw new BusinessRuleException("成员 " + m.requestId() + " 版本不符：期望 "
                        + m.expectedVersion() + "，当前 " + po.getVersion());
            }
            String activeBatch = batchRepo.findActiveBatchId(m.requestId());
            if (activeBatch != null) {
                throw new BusinessRuleException("成员 " + m.requestId()
                        + " 已属于未结束批次 " + activeBatch + "，一项申请最多属于一个未结束批次");
            }
        }

        // 原子冻结：批次 + 成员(版本/窗口快照) + 依赖边 + 成员绑定，同事务提交。
        CutoverBatchPO batch = new CutoverBatchPO();
        batch.setBatchId(req.batchId());
        batch.setPublishedAt(req.publishedAt());
        batch.setStatus(BatchStatus.PUBLISHED.name());
        batch.setFingerprint(fp);
        batch.setVersion(1);
        batch.setCreatedAt(now);
        batch.setUpdatedAt(now);
        try {
            batchRepo.insertBatch(batch);
        } catch (DuplicateKeyException dup) {
            CutoverBatchPO winner = batchRepo.findBatch(req.batchId());
            if (winner != null) {
                return resolveReplay(winner, fp);
            }
            throw dup;
        }
        List<String> sortedIds = memberIds.stream().sorted().toList();
        Map<String, BatchMemberRef> refById = new HashMap<>();
        for (BatchMemberRef m : members) {
            refById.put(m.requestId(), m);
        }
        for (String id : sortedIds) {
            CutoverRequestPO po = locked.get(id);
            CutoverBatchMemberPO member = new CutoverBatchMemberPO();
            member.setBatchId(req.batchId());
            member.setRequestId(id);
            member.setRequestVersion(po.getVersion());
            member.setWindowStart(po.getWindowStart());
            member.setWindowEnd(po.getWindowEnd());
            member.setCreatedAt(now);
            batchRepo.insertMember(member);
            // 绑定批次（成功变更，版本号由数据库原子加 1）。
            po.setBatchId(req.batchId());
            po.setUpdatedAt(now);
            repo.updateMutable(po);
        }
        List<BatchEdgeRef> sortedEdges = sortedEdges(edges);
        for (BatchEdgeRef e : sortedEdges) {
            CutoverBatchEdgePO edge = new CutoverBatchEdgePO();
            edge.setBatchId(req.batchId());
            edge.setPredecessor(e.predecessor());
            edge.setSuccessor(e.successor());
            edge.setCreatedAt(now);
            batchRepo.insertEdge(edge);
        }
        String message = "批次已发布，冻结 " + sortedIds.size() + " 个成员、"
                + sortedEdges.size() + " 条依赖边";
        return new BatchActionResult(false, message, toView(batch));
    }

    // ---------------- 拓扑与阻断查询 ----------------

    @Transactional(readOnly = true)
    public BatchView getBatch(String batchId) {
        CutoverBatchPO batch = batchRepo.findBatch(batchId);
        if (batch == null) {
            throw new NotFoundException("批次不存在: " + batchId);
        }
        return toView(batch);
    }

    @Transactional(readOnly = true)
    public List<BlockDetailView> listBlocks(String batchId) {
        if (batchRepo.findBatch(batchId) == null) {
            throw new NotFoundException("批次不存在: " + batchId);
        }
        return batchRepo.listBlockRecords(batchId).stream()
                .map(b -> new BlockDetailView(b.getBatchId(), b.getRequestId(),
                        b.getSourceRequestId(), b.getCause(), b.getCreatedAt()))
                .toList();
    }

    // ---------------- 启动门禁（供 CutoverService 同事务调用） ----------------

    /**
     * 批内成员启动门禁。调用方须已持有该申请行锁。
     *
     * @return null 表示可启动；否则返回等待原因（前置尚未全部封板）
     */
    public String gateMessage(CutoverRequestPO po) {
        if (po.getBatchId() == null) {
            return null;
        }
        CutoverBatchPO batch = batchRepo.findBatch(po.getBatchId());
        if (batch == null || !BatchStatus.PUBLISHED.name().equals(batch.getStatus())) {
            return null;
        }
        List<String> predecessors = new ArrayList<>();
        for (CutoverBatchEdgePO e : batchRepo.listEdges(po.getBatchId())) {
            if (e.getSuccessor().equals(po.getRequestId())) {
                predecessors.add(e.getPredecessor());
            }
        }
        if (predecessors.isEmpty()) {
            return null;
        }
        // 前置失败传播与前置状态提交同事务：此处读到非 SEALED 即为仍在推进，
        // 返回等待且不写步骤、不占幂等键；若前置已失败，本申请必然已被传播阻断。
        predecessors.sort(String::compareTo);
        List<String> unsealed = new ArrayList<>();
        for (String predId : predecessors) {
            CutoverRequestPO pred = repo.findRequest(predId);
            if (pred == null || !RequestStatus.SEALED.name().equals(pred.getStatus())) {
                unsealed.add(predId);
            }
        }
        if (unsealed.isEmpty()) {
            return null;
        }
        return "直接前置 " + String.join(",", unsealed)
                + " 尚未验证通过并封板，等待前置完成，本次不启动、不写步骤";
    }

    // ---------------- 失败传播与批次关闭（供 CutoverService 同事务调用） ----------------

    /**
     * 前置进入回退/超期后的同事务传播：批内尚未开始(APPROVED)的全部可达后继
     * 转 DEPENDENCY_BLOCKED 并记录首个阻断来源集合；已开始/终态成员不动。
     * 传播完成后刷新批次关闭状态。调用方须已持有失败申请行锁。
     */
    public void propagateFailure(CutoverRequestPO failed) {
        String batchId = failed.getBatchId();
        if (batchId == null) {
            return;
        }
        CutoverBatchPO batch = batchRepo.findBatch(batchId);
        if (batch == null || !BatchStatus.PUBLISHED.name().equals(batch.getStatus())) {
            return;
        }
        List<CutoverBatchEdgePO> edges = batchRepo.listEdges(batchId);
        Map<String, List<String>> successorsOf = new HashMap<>();
        Map<String, List<String>> predecessorsOf = new HashMap<>();
        for (CutoverBatchEdgePO e : edges) {
            successorsOf.computeIfAbsent(e.getPredecessor(), k -> new ArrayList<>())
                    .add(e.getSuccessor());
            predecessorsOf.computeIfAbsent(e.getSuccessor(), k -> new ArrayList<>())
                    .add(e.getPredecessor());
        }
        // 按层遍历可达后继，保证阻断来源计算时上游阻断已落库。
        Set<String> reachable = new LinkedHashSet<>();
        Deque<String> queue = new ArrayDeque<>();
        queue.add(failed.getRequestId());
        while (!queue.isEmpty()) {
            String current = queue.poll();
            for (String next : successorsOf.getOrDefault(current, List.of())) {
                if (reachable.add(next)) {
                    queue.add(next);
                }
            }
        }
        if (reachable.isEmpty()) {
            refreshClosure(batchId);
            return;
        }
        // 按申请编号升序一次性锁定全部可达成员行，与发布/启动保持同一加锁顺序。
        Map<String, CutoverRequestPO> locked = new HashMap<>();
        for (CutoverRequestPO po : repo.lockRequestsOrdered(List.copyOf(reachable))) {
            locked.put(po.getRequestId(), po);
        }
        Map<String, String> statusById = new HashMap<>();
        statusById.put(failed.getRequestId(), failed.getStatus());
        LocalDateTime now = LocalDateTime.now();
        // 按 BFS 层次处理：上层阻断先落库，下层来源集合可见。
        Deque<String> levels = new ArrayDeque<>();
        levels.add(failed.getRequestId());
        Set<String> processed = new LinkedHashSet<>();
        while (!levels.isEmpty()) {
            String current = levels.poll();
            for (String next : successorsOf.getOrDefault(current, List.of())) {
                if (!processed.add(next)) {
                    continue;
                }
                CutoverRequestPO po = locked.get(next);
                if (po == null) {
                    continue;
                }
                if (RequestStatus.APPROVED.name().equals(po.getStatus())) {
                    // 尚未开始：转依赖阻断并记录首个阻断来源集合。
                    po.setStatus(RequestStatus.DEPENDENCY_BLOCKED.name());
                    po.setUpdatedAt(now);
                    repo.updateMutable(po);
                    statusById.put(next, po.getStatus());
                    for (String pred : predecessorsOf.getOrDefault(next, List.of())) {
                        String predStatus = statusById.computeIfAbsent(pred, id -> {
                            CutoverRequestPO p = locked.get(id);
                            if (p != null) {
                                return p.getStatus();
                            }
                            CutoverRequestPO fresh = repo.findRequest(id);
                            return fresh == null ? null : fresh.getStatus();
                        });
                        String cause = BLOCK_CAUSE.get(predStatus);
                        if (cause != null) {
                            CutoverBlockRecordPO record = new CutoverBlockRecordPO();
                            record.setBatchId(batchId);
                            record.setRequestId(next);
                            record.setSourceRequestId(pred);
                            record.setCause(cause);
                            record.setCreatedAt(now);
                            batchRepo.insertBlockRecord(record);
                        }
                    }
                } else {
                    // 已开始成员按原回退流程处理，已封板结果不改，无关状态不动。
                    statusById.put(next, po.getStatus());
                }
                levels.add(next);
            }
        }
        refreshClosure(batchId);
    }

    /**
     * 全部成员到达终态后关闭批次（PUBLISHED -> CLOSED，版本加 1）。
     * 在成员进入终态的同一事务内调用。
     */
    public void refreshClosure(String batchId) {
        if (batchId == null) {
            return;
        }
        CutoverBatchPO batch = batchRepo.lockBatch(batchId);
        if (batch == null || !BatchStatus.PUBLISHED.name().equals(batch.getStatus())) {
            return;
        }
        if (batchRepo.countNonTerminalMembers(batchId) == 0) {
            batchRepo.closeBatch(batchId, LocalDateTime.now());
        }
    }

    // ---------------- 内部方法 ----------------

    private BatchActionResult resolveReplay(CutoverBatchPO existed, String fp) {
        if (!existed.getFingerprint().equals(fp)) {
            throw new ConflictException("batchId=" + existed.getBatchId()
                    + " 已存在但业务参数不一致（同键异参），拒绝产生新记录");
        }
        return new BatchActionResult(true, "同键同参重放，返回首次冻结结果", toView(existed));
    }

    /** 环检测：Kahn 拓扑排序，无法全部入列即存在环路。 */
    private void assertAcyclic(Set<String> memberIds, List<BatchEdgeRef> edges) {
        Map<String, Integer> indegree = new HashMap<>();
        Map<String, List<String>> successorsOf = new HashMap<>();
        for (String id : memberIds) {
            indegree.put(id, 0);
        }
        for (BatchEdgeRef e : edges) {
            indegree.merge(e.successor(), 1, Integer::sum);
            successorsOf.computeIfAbsent(e.predecessor(), k -> new ArrayList<>())
                    .add(e.successor());
        }
        Deque<String> queue = new ArrayDeque<>();
        for (Map.Entry<String, Integer> entry : indegree.entrySet()) {
            if (entry.getValue() == 0) {
                queue.add(entry.getKey());
            }
        }
        int visited = 0;
        while (!queue.isEmpty()) {
            String current = queue.poll();
            visited++;
            for (String next : successorsOf.getOrDefault(current, List.of())) {
                if (indegree.merge(next, -1, Integer::sum) == 0) {
                    queue.add(next);
                }
            }
        }
        if (visited != memberIds.size()) {
            throw new BusinessRuleException("依赖关系存在环路，整批拒绝");
        }
    }

    /** 指纹：batchId + 发布精确时刻 + 规范化排序后的成员集合(含版本)与依赖边集合。 */
    private String fingerprint(String batchId, LocalDateTime publishedAt,
                               List<BatchMemberRef> members, List<BatchEdgeRef> edges) {
        List<Object> parts = new ArrayList<>();
        parts.add("PUBLISH_BATCH");
        parts.add(batchId);
        parts.add(publishedAt);
        members.stream()
                .sorted((a, b) -> a.requestId().compareTo(b.requestId()))
                .forEach(m -> {
                    parts.add(m.requestId());
                    parts.add(m.expectedVersion());
                });
        for (BatchEdgeRef e : sortedEdges(edges)) {
            parts.add(e.predecessor());
            parts.add(e.successor());
        }
        return FingerprintUtil.sha256(parts.toArray());
    }

    private List<BatchEdgeRef> sortedEdges(List<BatchEdgeRef> edges) {
        return edges.stream()
                .sorted((a, b) -> {
                    int c = a.predecessor().compareTo(b.predecessor());
                    return c != 0 ? c : a.successor().compareTo(b.successor());
                })
                .toList();
    }

    private BatchView toView(CutoverBatchPO batch) {
        String batchId = batch.getBatchId();
        List<BatchMemberView> members = batchRepo.listMembers(batchId).stream()
                .map(m -> {
                    CutoverRequestPO po = repo.findRequest(m.getRequestId());
                    List<String> sources = batchRepo
                            .listBlockRecordsFor(batchId, m.getRequestId()).stream()
                            .map(CutoverBlockRecordPO::getSourceRequestId)
                            .toList();
                    return new BatchMemberView(m.getRequestId(), m.getRequestVersion(),
                            po == null ? null : po.getStatus(),
                            m.getWindowStart(), m.getWindowEnd(), sources);
                })
                .toList();
        List<BatchEdgeView> edges = batchRepo.listEdges(batchId).stream()
                .map(e -> new BatchEdgeView(e.getPredecessor(), e.getSuccessor()))
                .toList();
        return new BatchView(batchId, batch.getStatus(), batch.getVersion(),
                batch.getPublishedAt(), members, edges);
    }
}
