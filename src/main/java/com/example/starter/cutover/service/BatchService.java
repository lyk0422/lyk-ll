package com.example.starter.cutover.service;

import com.example.starter.cutover.BusinessRuleException;
import com.example.starter.cutover.ConflictException;
import com.example.starter.cutover.EventType;
import com.example.starter.cutover.NotFoundException;
import com.example.starter.cutover.RequestStatus;
import com.example.starter.cutover.dto.BatchActionResult;
import com.example.starter.cutover.dto.BatchEdgeRef;
import com.example.starter.cutover.dto.BatchEdgeView;
import com.example.starter.cutover.dto.BatchMemberRef;
import com.example.starter.cutover.dto.BatchMemberView;
import com.example.starter.cutover.dto.BatchView;
import com.example.starter.cutover.dto.BlockDetailView;
import com.example.starter.cutover.dto.CloseBatchRequest;
import com.example.starter.cutover.dto.PublishBatchRequest;
import com.example.starter.cutover.model.CutoverBatchEdgePO;
import com.example.starter.cutover.model.CutoverBatchMemberPO;
import com.example.starter.cutover.model.CutoverBatchPO;
import com.example.starter.cutover.model.CutoverEventPO;
import com.example.starter.cutover.model.CutoverRequestPO;
import com.example.starter.cutover.repo.BatchRepository;
import com.example.starter.cutover.repo.CutoverRepository;
import com.example.starter.cutover.support.FingerprintUtil;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 割接批次领域服务：发布（原子冻结成员/依赖/窗口快照）、拓扑查询、
 * 执行门禁（前置未封板等待/依赖阻断拒绝）、阻断传播与批次结束。
 *
 * <p>关键口径：</p>
 * <ul>
 *   <li>成员必须唯一，依赖边两端都在批内，自环/环路/已开始/版本不符整批拒绝（事务回滚不占键）；</li>
 *   <li>一项申请最多属于一个未结束（PUBLISHED）批次；</li>
 *   <li>批内成员开始执行前，所有直接前置必须已封板（SEALED），否则返回等待且不写步骤；</li>
 *   <li>任一前置回退/超期，同事务内将尚未开始的全部可达后继置为依赖阻断，
 *       首个阻断来源集合一旦写入不再覆盖；</li>
 *   <li>发布与启动、前置失败与后继启动通过申请行 FOR UPDATE 串行化，只形成一致顺序。</li>
 * </ul>
 */
@Service
public class BatchService {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** 申请终态：批次结束判定口径。 */
    private static final Set<String> TERMINAL_STATUS = Set.of(
            RequestStatus.SEALED.name(), RequestStatus.ROLLED_BACK.name(),
            RequestStatus.WINDOW_EXPIRED.name(), RequestStatus.REJECTED.name());

    private final BatchRepository batchRepo;
    private final CutoverRepository cutoverRepo;

    public BatchService(BatchRepository batchRepo, CutoverRepository cutoverRepo) {
        this.batchRepo = batchRepo;
        this.cutoverRepo = cutoverRepo;
    }

    // ---------------- 批次发布 ----------------

    @Transactional
    public BatchActionResult publish(PublishBatchRequest req) {
        String fp = fingerprintPublish(req);
        CutoverBatchPO existing = batchRepo.findBatch(req.requestId());
        if (existing != null) {
            return resolvePublishIdempotent(existing, fp);
        }

        // 结构校验：成员唯一、边两端在批内、无自环、无重复边、无环。
        List<BatchMemberRef> members = req.members();
        Set<String> memberIds = new HashSet<>();
        Map<String, Integer> expectedVersions = new HashMap<>();
        for (BatchMemberRef m : members) {
            if (!memberIds.add(m.requestId())) {
                throw new BusinessRuleException("批次成员必须唯一，重复成员: " + m.requestId());
            }
            expectedVersions.put(m.requestId(), m.expectedVersion());
        }
        List<BatchEdgeRef> edges = req.edges() == null ? List.of() : req.edges();
        Set<String> edgeKeys = new HashSet<>();
        for (BatchEdgeRef e : edges) {
            if (e.predecessor().equals(e.successor())) {
                throw new BusinessRuleException("依赖边不允许自环: " + e.predecessor());
            }
            if (!memberIds.contains(e.predecessor()) || !memberIds.contains(e.successor())) {
                throw new BusinessRuleException("依赖边两端必须都在批内: "
                        + e.predecessor() + "->" + e.successor());
            }
            if (!edgeKeys.add(e.predecessor() + "->" + e.successor())) {
                throw new BusinessRuleException("依赖边重复: "
                        + e.predecessor() + "->" + e.successor());
            }
        }
        detectCycle(memberIds, edges);

        // 按申请编号升序逐个加行锁，与单申请启动/其他发布串行化且避免死锁。
        List<String> sortedIds = memberIds.stream().sorted().toList();
        Map<String, CutoverRequestPO> locked = new LinkedHashMap<>();
        for (String id : sortedIds) {
            CutoverRequestPO po = cutoverRepo.lockRequest(id);
            if (po == null) {
                throw new NotFoundException("割接申请不存在: " + id);
            }
            locked.put(id, po);
        }
        // 拿锁后复查：并发同批次号的首个事务可能已提交。
        CutoverBatchPO afterLock = batchRepo.findBatch(req.requestId());
        if (afterLock != null) {
            return resolvePublishIdempotent(afterLock, fp);
        }

        // 状态与版本校验：必须已批准但未开始（APPROVED），版本须与 expectedVersion 一致，
        // 且未绑定其他未结束批次；任一不符整批拒绝（事务回滚，不占键）。
        for (String id : sortedIds) {
            CutoverRequestPO po = locked.get(id);
            if (!RequestStatus.APPROVED.name().equals(po.getStatus())) {
                throw new BusinessRuleException("成员 " + id + " 状态 " + po.getStatus()
                        + " 不是已批准未开始（APPROVED），整批拒绝");
            }
            if (!po.getVersion().equals(expectedVersions.get(id))) {
                throw new BusinessRuleException("成员 " + id + " 版本不符：期望 "
                        + expectedVersions.get(id) + "，实际 " + po.getVersion() + "，整批拒绝");
            }
            if (batchRepo.findOpenMembership(id) != null) {
                throw new BusinessRuleException("成员 " + id
                        + " 已属于其他未结束批次，一项申请最多属于一个未结束批次，整批拒绝");
            }
        }

        LocalDateTime now = LocalDateTime.now();
        CutoverBatchPO batch = new CutoverBatchPO();
        batch.setBatchId(req.requestId());
        batch.setBatchName(req.batchName());
        batch.setStatus("PUBLISHED");
        batch.setVersion(1);
        batch.setFingerprint(fp);
        batch.setCreatedAt(now);
        batch.setUpdatedAt(now);
        try {
            batchRepo.insertBatch(batch);
        } catch (DuplicateKeyException dup) {
            // 并发下同批次号已提交：按幂等/冲突处理。
            CutoverBatchPO winner = batchRepo.findBatch(req.requestId());
            if (winner != null) {
                return resolvePublishIdempotent(winner, fp);
            }
            throw dup;
        }

        for (String id : sortedIds) {
            CutoverRequestPO po = locked.get(id);
            CutoverBatchMemberPO member = new CutoverBatchMemberPO();
            member.setBatchId(req.requestId());
            member.setRequestId(id);
            member.setRequestVersion(po.getVersion());
            member.setWindowStart(po.getWindowStart());
            member.setWindowEnd(po.getWindowEnd());
            member.setMemberStatus("READY");
            member.setCreatedAt(now);
            member.setUpdatedAt(now);
            batchRepo.insertMember(member);
        }
        for (BatchEdgeRef e : edges) {
            CutoverBatchEdgePO edge = new CutoverBatchEdgePO();
            edge.setBatchId(req.requestId());
            edge.setPredecessor(e.predecessor());
            edge.setSuccessor(e.successor());
            edge.setCreatedAt(now);
            batchRepo.insertEdge(edge);
        }
        String message = "批次已发布，原子冻结 " + sortedIds.size() + " 个成员、"
                + edges.size() + " 条依赖边及窗口快照";
        insertBatchEvent("BATCH-PUBLISH-" + req.requestId(), req.requestId(),
                EventType.BATCH_PUBLISH, fp, message);
        return new BatchActionResult(false, message, toView(batch));
    }

    // ---------------- 查询 ----------------

    @Transactional(readOnly = true)
    public BatchView get(String batchId) {
        CutoverBatchPO batch = batchRepo.findBatch(batchId);
        if (batch == null) {
            throw new NotFoundException("批次不存在: " + batchId);
        }
        return toView(batch);
    }

    @Transactional(readOnly = true)
    public List<BlockDetailView> blocks(String batchId) {
        if (batchRepo.findBatch(batchId) == null) {
            throw new NotFoundException("批次不存在: " + batchId);
        }
        return batchRepo.listMembers(batchId).stream()
                .filter(m -> "BLOCKED".equals(m.getMemberStatus()))
                .map(m -> new BlockDetailView(batchId, m.getRequestId(),
                        splitSources(m.getBlockSources())))
                .toList();
    }

    // ---------------- 批次结束 ----------------

    @Transactional
    public BatchActionResult close(CloseBatchRequest req) {
        String fp = FingerprintUtil.sha256("BATCH_CLOSE", req.batchId(),
                req.expectedVersion(), req.closeTime());
        CutoverEventPO existed = cutoverRepo.findEvent(req.requestUid());
        if (existed != null) {
            return resolveCloseIdempotent(existed, fp, req.batchId());
        }

        CutoverBatchPO batch = batchRepo.lockBatch(req.batchId());
        if (batch == null) {
            throw new NotFoundException("批次不存在: " + req.batchId());
        }
        CutoverEventPO afterLock = cutoverRepo.findEvent(req.requestUid());
        if (afterLock != null) {
            return resolveCloseIdempotent(afterLock, fp, req.batchId());
        }
        if ("CLOSED".equals(batch.getStatus())) {
            throw new BusinessRuleException("批次已结束，不能重复结束");
        }
        if (!batch.getVersion().equals(req.expectedVersion())) {
            throw new BusinessRuleException("批次版本不符：期望 " + req.expectedVersion()
                    + "，实际 " + batch.getVersion());
        }
        // 全部成员申请到达终态、或成员已被依赖阻断，批次才可结束。
        for (CutoverBatchMemberPO member : batchRepo.listMembers(req.batchId())) {
            if ("BLOCKED".equals(member.getMemberStatus())) {
                continue;
            }
            CutoverRequestPO po = cutoverRepo.findRequest(member.getRequestId());
            String status = po == null ? null : po.getStatus();
            if (!TERMINAL_STATUS.contains(status)) {
                throw new BusinessRuleException("成员 " + member.getRequestId()
                        + " 状态 " + status + " 未完结，批次不能结束");
            }
        }
        LocalDateTime now = LocalDateTime.now();
        int updated = batchRepo.closeBatch(req.batchId(), req.expectedVersion(), now);
        if (updated == 0) {
            // 并发兜底：行锁串行化下理论上不可达，仍按业务拒绝处理。
            throw new BusinessRuleException("批次版本不符或已结束，结束失败");
        }
        String message = "批次已结束，版本 " + req.expectedVersion() + " -> "
                + (req.expectedVersion() + 1);
        insertBatchEvent(req.requestUid(), req.batchId(), EventType.BATCH_CLOSE, fp, message);
        return new BatchActionResult(false, message, toView(batchRepo.findBatch(req.batchId())));
    }

    // ---------------- 启动门禁与阻断传播（供 CutoverService 同事务调用） ----------------

    /**
     * 启动门禁：返回 null 表示放行（并将成员置为 STARTED）；
     * 返回非 null 表示前置未封板需等待（不写任何记录）；依赖阻断抛 422。
     * 未绑定批次的旧单申请直接放行，行为不变。
     */
    public String checkStartGateAndMark(String requestId) {
        // 阻断门禁：任意批次（含已结束）中被阻断的成员永久禁止启动。
        for (CutoverBatchMemberPO membership : batchRepo.listMemberships(requestId)) {
            if ("BLOCKED".equals(membership.getMemberStatus())) {
                throw new BusinessRuleException("申请 " + requestId + " 在批次 "
                        + membership.getBatchId() + " 中已被依赖阻断（来源: "
                        + membership.getBlockSources() + "），不能开始执行");
            }
        }
        CutoverBatchMemberPO open = batchRepo.findOpenMembership(requestId);
        if (open == null) {
            return null;
        }
        // 所有直接前置必须已验证通过并封板（SEALED），否则返回等待且不写步骤。
        List<String> unsealed = new ArrayList<>();
        for (CutoverBatchEdgePO edge : batchRepo.listInboundOpenEdges(requestId)) {
            CutoverRequestPO pred = cutoverRepo.findRequest(edge.getPredecessor());
            String predStatus = pred == null ? "MISSING" : pred.getStatus();
            if (!RequestStatus.SEALED.name().equals(predStatus)) {
                unsealed.add(edge.getPredecessor() + "(" + predStatus + ")");
            }
        }
        if (!unsealed.isEmpty()) {
            return "直接前置尚未验证通过并封板，等待: " + String.join(",", unsealed);
        }
        if ("READY".equals(open.getMemberStatus())) {
            batchRepo.updateMemberStatus(open.getBatchId(), requestId, "STARTED",
                    null, LocalDateTime.now());
        }
        return null;
    }

    /**
     * 前置回退/超期后的阻断传播（调用方须处于同事务并已完成自身状态推进）。
     * 尚未开始的全部可达后继转依赖阻断；已开始成员跳过，按原回退流程处理。
     */
    public void propagateDependencyBlock(String failedRequestId) {
        List<CutoverBatchEdgePO> openEdges = batchRepo.listAllOpenEdges();
        Map<String, List<String>> outgoing = new HashMap<>();
        for (CutoverBatchEdgePO e : openEdges) {
            outgoing.computeIfAbsent(e.getPredecessor(), k -> new ArrayList<>())
                    .add(e.getSuccessor());
        }
        if (!outgoing.containsKey(failedRequestId)) {
            return;
        }
        // 先在无锁图上求可达集合，再按申请编号全局升序加锁，
        // 保证并发传播/启动的锁顺序一致，避免死锁。
        Set<String> reachable = new HashSet<>();
        Deque<String> queue = new ArrayDeque<>();
        queue.add(failedRequestId);
        reachable.add(failedRequestId);
        while (!queue.isEmpty()) {
            String node = queue.poll();
            for (String succ : outgoing.getOrDefault(node, List.of())) {
                if (reachable.add(succ)) {
                    queue.add(succ);
                }
            }
        }
        reachable.remove(failedRequestId);

        LocalDateTime now = LocalDateTime.now();
        for (String succ : reachable.stream().sorted().toList()) {
            CutoverBatchMemberPO member = batchRepo.findOpenMembership(succ);
            if (member == null || !"READY".equals(member.getMemberStatus())) {
                continue;
            }
            // 锁后继申请行，与后继启动串行化：只形成一致顺序。
            CutoverRequestPO succReq = cutoverRepo.lockRequest(succ);
            if (succReq == null) {
                continue;
            }
            boolean started = succReq.getExecStartedAt() != null
                    || !RequestStatus.APPROVED.name().equals(succReq.getStatus());
            if (started) {
                continue;
            }
            // 首个阻断来源集合：直接前置中已回退/超期或被阻断者，升序；一旦写入不再覆盖。
            List<String> sources = batchRepo.listInboundOpenEdges(succ).stream()
                    .map(CutoverBatchEdgePO::getPredecessor)
                    .filter(this::isFailedOrBlocked)
                    .sorted()
                    .toList();
            batchRepo.updateMemberStatus(member.getBatchId(), succ, "BLOCKED",
                    String.join(",", sources), now);
            insertBatchEvent("SYS-BLOCK-" + member.getBatchId() + "-" + succ, succ,
                    EventType.DEPENDENCY_BLOCK,
                    FingerprintUtil.sha256("DEPENDENCY_BLOCK", member.getBatchId(), succ,
                            new ArrayList<>(sources)),
                    "前置 " + failedRequestId + " 回退/超期，尚未开始的后继转依赖阻断，来源: "
                            + sources);
        }
    }

    // ---------------- 内部方法 ----------------

    /**
     * 发布指纹：requestId + 批次名 + 精确发布时刻 + 成员（申请,期望版本）集合 + 依赖边集合。
     * 集合先按申请编号排序再以嵌套列表参与 JSON 序列化，结构化无歧义：
     * 成员文本中即使含分隔符，也由 JSON 字符串边界区分，不会与分隔符混淆。
     */
    private String fingerprintPublish(PublishBatchRequest req) {
        List<List<Object>> members = req.members().stream()
                .sorted(Comparator.comparing(BatchMemberRef::requestId))
                .map(m -> List.<Object>of(m.requestId(), m.expectedVersion()))
                .toList();
        List<BatchEdgeRef> edges = req.edges() == null ? List.of() : req.edges();
        List<List<Object>> edgeParts = edges.stream()
                .sorted(Comparator.comparing(BatchEdgeRef::predecessor)
                        .thenComparing(BatchEdgeRef::successor))
                .map(e -> List.<Object>of(e.predecessor(), e.successor()))
                .toList();
        return FingerprintUtil.sha256("BATCH_PUBLISH", req.requestId(), req.batchName(),
                req.publishTime(), members, edgeParts);
    }

    /** Kahn 拓扑判环：存在环路则整批拒绝。 */
    private void detectCycle(Set<String> memberIds, List<BatchEdgeRef> edges) {
        Map<String, Integer> indegree = new HashMap<>();
        Map<String, List<String>> outgoing = new HashMap<>();
        for (String id : memberIds) {
            indegree.put(id, 0);
            outgoing.put(id, new ArrayList<>());
        }
        for (BatchEdgeRef e : edges) {
            indegree.merge(e.successor(), 1, Integer::sum);
            outgoing.get(e.predecessor()).add(e.successor());
        }
        Deque<String> queue = new ArrayDeque<>();
        indegree.forEach((id, deg) -> {
            if (deg == 0) {
                queue.add(id);
            }
        });
        int visited = 0;
        while (!queue.isEmpty()) {
            String node = queue.poll();
            visited++;
            for (String next : outgoing.get(node)) {
                if (indegree.merge(next, -1, Integer::sum) == 0) {
                    queue.add(next);
                }
            }
        }
        if (visited < memberIds.size()) {
            throw new BusinessRuleException("依赖边存在环路，整批拒绝");
        }
    }

    private BatchActionResult resolvePublishIdempotent(CutoverBatchPO existing, String fp) {
        if (!existing.getFingerprint().equals(fp)) {
            throw new ConflictException("requestId=" + existing.getBatchId()
                    + " 已发布但业务参数不一致（同键异参），拒绝产生新记录");
        }
        return new BatchActionResult(true, "同键同参重放，返回首次冻结结果", toView(existing));
    }

    private BatchActionResult resolveCloseIdempotent(CutoverEventPO existed, String fp,
                                                     String batchId) {
        if (!existed.getPayloadFingerprint().equals(fp)) {
            throw new ConflictException("requestUid=" + existed.getRequestUid()
                    + " 已存在但业务参数不一致（同键异参），拒绝产生新记录");
        }
        CutoverBatchPO batch = batchRepo.findBatch(batchId);
        return new BatchActionResult(true, "同键同参重放，返回首次结果",
                batch == null ? null : toView(batch));
    }

    /** 阻断来源判定：申请已回退/超期，或其在未结束批次中的成员已被阻断。 */
    private boolean isFailedOrBlocked(String requestId) {
        CutoverRequestPO po = cutoverRepo.findRequest(requestId);
        if (po != null && (RequestStatus.ROLLED_BACK.name().equals(po.getStatus())
                || RequestStatus.WINDOW_EXPIRED.name().equals(po.getStatus()))) {
            return true;
        }
        CutoverBatchMemberPO member = batchRepo.findOpenMembership(requestId);
        return member != null && "BLOCKED".equals(member.getMemberStatus());
    }

    private void insertBatchEvent(String uid, String requestId, EventType type,
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
        try {
            cutoverRepo.insertEvent(po);
        } catch (DuplicateKeyException dup) {
            // 并发下同 uid 事件已提交：指纹一致属重放，不一致按 409 处理。
            CutoverEventPO winner = cutoverRepo.findEvent(uid);
            if (winner != null && !winner.getPayloadFingerprint().equals(fingerprint)) {
                throw new ConflictException("requestUid=" + uid
                        + " 已存在但业务参数不一致（同键异参）");
            }
        }
    }

    private BatchView toView(CutoverBatchPO batch) {
        List<BatchMemberView> members = batchRepo.listMembers(batch.getBatchId()).stream()
                .map(m -> new BatchMemberView(m.getRequestId(), m.getRequestVersion(),
                        m.getWindowStart(), m.getWindowEnd(), m.getMemberStatus(),
                        splitSources(m.getBlockSources())))
                .toList();
        List<BatchEdgeView> edges = batchRepo.listEdges(batch.getBatchId()).stream()
                .map(e -> new BatchEdgeView(e.getPredecessor(), e.getSuccessor()))
                .toList();
        return new BatchView(batch.getBatchId(), batch.getBatchName(), batch.getStatus(),
                batch.getVersion(), members, edges);
    }

    private List<String> splitSources(String blockSources) {
        if (blockSources == null || blockSources.isBlank()) {
            return List.of();
        }
        return Arrays.stream(blockSources.split(","))
                .filter(s -> !s.isEmpty())
                .toList();
    }

    private String writeSnapshot(String message) {
        try {
            return JSON.writeValueAsString(Map.of("message", message));
        } catch (Exception e) {
            return "{}";
        }
    }

    @SuppressWarnings("unused")
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
