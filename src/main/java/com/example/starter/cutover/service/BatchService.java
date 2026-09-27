package com.example.starter.cutover.service;

import com.example.starter.cutover.BatchStatus;
import com.example.starter.cutover.BusinessRuleException;
import com.example.starter.cutover.ConflictException;
import com.example.starter.cutover.EventType;
import com.example.starter.cutover.NotFoundException;
import com.example.starter.cutover.dto.BatchActionResult;
import com.example.starter.cutover.dto.BatchEdgeView;
import com.example.starter.cutover.dto.BatchMemberStateView;
import com.example.starter.cutover.dto.BatchMemberView;
import com.example.starter.cutover.dto.BatchView;
import com.example.starter.cutover.dto.BlockView;
import com.example.starter.cutover.dto.DependencyEdgeInput;
import com.example.starter.cutover.dto.PublishBatchRequest;
import com.example.starter.cutover.dto.TopologyNodeView;
import com.example.starter.cutover.dto.TopologyView;
import com.example.starter.cutover.model.CutoverBatchEdgePO;
import com.example.starter.cutover.model.CutoverBatchMemberPO;
import com.example.starter.cutover.model.CutoverBatchPO;
import com.example.starter.cutover.model.CutoverBlockPO;
import com.example.starter.cutover.model.CutoverEventPO;
import com.example.starter.cutover.model.CutoverRequestPO;
import com.example.starter.cutover.repo.CutoverRepository;
import com.example.starter.cutover.support.FingerprintUtil;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.time.LocalDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 割接批次领域服务：批次发布冻结、执行门禁依赖判定、前置失败的同事务阻断传播、
 * 拓扑与阻断明细查询。
 *
 * <p>串行化口径：同一批次的“启动/失败传播/封板收口”均先锁批次行
 * （SELECT ... FOR UPDATE），再锁成员申请行（按申请编号排序），避免跨事务死锁；
 * 并发发布重叠成员时在成员行锁上串行，靠 open_batch_id 判定“一项申请最多属于一个未结束批次”。</p>
 */
@Service
public class BatchService {

    /** 成员终态：到达这些状态后该成员不再变化，全部终态时批次可关闭。 */
    private static final Set<String> MEMBER_TERMINAL = Set.of(
            "SEALED", "REJECTED", "ROLLED_BACK", "WINDOW_EXPIRED", "DEPENDENCY_BLOCKED");
    /** 前置失败（进入回退或超期）状态。 */
    private static final Set<String> FAILED_STATUS = Set.of("ROLLED_BACK", "WINDOW_EXPIRED");

    private static final ObjectMapper JSON = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private final CutoverRepository repo;

    public BatchService(CutoverRepository repo) {
        this.repo = repo;
    }

    // ---------------- 批次发布 ----------------

    /**
     * 发布批次：校验成员唯一、边在批内、无自环/环路、成员已批准未开始、版本相符、
     * 未绑定其他未结束批次；通过后原子冻结成员/依赖/窗口快照并绑定成员（版本 +1）。
     */
    @Transactional
    public BatchActionResult publish(PublishBatchRequest req) {
        // 全局互斥：发布与成员生命周期写操作共用同一串行化点，避免跨事务死锁。
        repo.lockGlobal();
        String fp = fingerprint(req);

        CutoverBatchPO existing = repo.findBatch(req.requestId());
        if (existing != null) {
            return replayResult(existing, fp);
        }

        List<String> memberIds = req.members().stream()
                .map(m -> m.requestId()).distinct().sorted().toList();
        // 先按申请编号排序锁定全部成员行：并发发布重叠成员在此串行，且统一加锁顺序避免死锁。
        List<CutoverRequestPO> locked = repo.lockRequestsByIds(memberIds);
        Map<String, CutoverRequestPO> byId = new TreeMap<>();
        for (CutoverRequestPO po : locked) {
            byId.put(po.getRequestId(), po);
        }

        // 持锁后复查同键，覆盖并发首个发布。
        CutoverBatchPO afterLock = repo.findBatch(req.requestId());
        if (afterLock != null) {
            return replayResult(afterLock, fp);
        }

        validateStructure(req, memberIds);
        for (String id : memberIds) {
            CutoverRequestPO po = byId.get(id);
            if (po == null) {
                throw new NotFoundException("批次成员申请不存在: " + id);
            }
        }
        // 成员入参（含版本）以编号排序后逐一核对。
        Map<String, Long> expectedVersion = new TreeMap<>();
        for (var m : req.members()) {
            expectedVersion.put(m.requestId(), m.expectedVersion());
        }
        for (String id : memberIds) {
            CutoverRequestPO po = byId.get(id);
            long expected = expectedVersion.get(id);
            if (po.getVersionNo() != expected) {
                throw new BusinessRuleException("成员 " + id + " 版本不符：期望 " + expected
                        + "，当前 " + po.getVersionNo() + "，整批拒绝");
            }
            if (!"APPROVED".equals(po.getStatus())) {
                throw new BusinessRuleException("成员 " + id + " 当前状态 " + po.getStatus()
                        + "，必须为已批准且未开始（APPROVED），整批拒绝");
            }
            if (po.getOpenBatchId() != null) {
                throw new BusinessRuleException("成员 " + id + " 已属于未结束批次 "
                        + po.getOpenBatchId() + "，一项申请最多属于一个未结束批次，整批拒绝");
            }
        }

        // 校验全部通过后才落批次行：业务拒绝不占用 requestId 键。
        LocalDateTime now = LocalDateTime.now();
        List<DependencyEdgeInput> uniqueEdges = uniqueSortedEdges(req.edges());

        CutoverBatchPO batch = new CutoverBatchPO();
        batch.setBatchId(req.requestId());
        batch.setFrozenAt(req.frozenAt());
        batch.setStatus(BatchStatus.OPEN.name());
        batch.setFingerprint(fp);
        batch.setMembersJson(writeMembersSnapshot(byId, expectedVersion));
        batch.setEdgesJson(writeEdgesSnapshot(uniqueEdges));
        batch.setResultSnapshot(writeSnapshot("批次 " + req.requestId() + " 发布冻结完成，成员 "
                + memberIds.size() + " 个，依赖边 " + uniqueEdges.size() + " 条"));
        batch.setCreatedAt(now);
        batch.setUpdatedAt(now);
        try {
            repo.insertBatch(batch);
        } catch (DuplicateKeyException dup) {
            CutoverBatchPO winner = repo.findBatch(req.requestId());
            if (winner != null) {
                return replayResult(winner, fp);
            }
            throw dup;
        }

        int ord = 0;
        for (String id : memberIds) {
            CutoverRequestPO po = byId.get(id);
            CutoverBatchMemberPO m = new CutoverBatchMemberPO();
            m.setBatchId(req.requestId());
            m.setRequestId(id);
            m.setExpectedVersion(expectedVersion.get(id));
            m.setWindowStart(po.getWindowStart());
            m.setWindowEnd(po.getWindowEnd());
            m.setOrd(ord++);
            m.setCreatedAt(now);
            repo.insertBatchMember(m);

            // 绑定批次是一次成功变更：成员版本 +1，open_batch_id 占用未结束批次名额。
            po.setOpenBatchId(req.requestId());
            po.setVersionNo(po.getVersionNo() + 1);
            po.setUpdatedAt(now);
            repo.updateMutable(po);
        }

        int edgeOrd = 0;
        for (DependencyEdgeInput e : uniqueEdges) {
            CutoverBatchEdgePO ep = new CutoverBatchEdgePO();
            ep.setBatchId(req.requestId());
            ep.setFromRequest(e.fromRequestId());
            ep.setToRequest(e.toRequestId());
            ep.setOrd(edgeOrd++);
            ep.setCreatedAt(now);
            repo.insertBatchEdge(ep);
        }

        insertBatchEvent(req.requestId(), EventType.BATCH_PUBLISH, fp,
                "批次发布冻结完成，成员 " + memberIds.size() + " 个、依赖边 " + uniqueEdges.size()
                        + " 条，发布时刻 " + req.frozenAt());

        return new BatchActionResult(false,
                "批次发布冻结完成，成员 " + memberIds.size() + " 个、依赖边 " + uniqueEdges.size()
                        + " 条，发布时刻 " + req.frozenAt(),
                toView(batch, memberIds, uniqueEdges));
    }

    private BatchActionResult replayResult(CutoverBatchPO batch, String fp) {
        BatchView view = resolveIdempotent(batch, fp);
        return new BatchActionResult(true, "同键同参重放，返回首次冻结结果", view);
    }

    private void validateStructure(PublishBatchRequest req, List<String> memberIds) {
        // 成员唯一
        Set<String> distinct = new TreeSet<>();
        for (var m : req.members()) {
            if (!distinct.add(m.requestId())) {
                throw new BusinessRuleException("批次成员重复: " + m.requestId() + "，整批拒绝");
            }
        }
        // 边两端在批内、无自环
        Set<String> memberSet = new TreeSet<>(memberIds);
        Set<String> edgeKeys = new TreeSet<>();
        List<DependencyEdgeInput> edges = new ArrayList<>();
        for (DependencyEdgeInput e : req.edges()) {
            if (!memberSet.contains(e.fromRequestId()) || !memberSet.contains(e.toRequestId())) {
                throw new BusinessRuleException("依赖边 [" + e.fromRequestId() + " -> "
                        + e.toRequestId() + "] 端点不在批内，整批拒绝");
            }
            if (e.fromRequestId().equals(e.toRequestId())) {
                throw new BusinessRuleException("依赖边不允许自环: " + e.fromRequestId()
                        + "，整批拒绝");
            }
            if (!edgeKeys.add(e.fromRequestId() + "  -> " + e.toRequestId())) {
                // 重复边按集合语义去重，不单独拒绝；保留首个。
            }
            edges.add(e);
        }
        // 环路检测：Kahn 拓扑排序，处理节点数 < 成员数即存在环。
        Map<String, List<String>> adjacency = new HashMap<>();
        Map<String, Integer> indegree = new HashMap<>();
        for (String id : memberIds) {
            adjacency.put(id, new ArrayList<>());
            indegree.put(id, 0);
        }
        for (DependencyEdgeInput e : uniqueSortedEdges(req.edges())) {
            adjacency.get(e.fromRequestId()).add(e.toRequestId());
            indegree.merge(e.toRequestId(), 1, Integer::sum);
        }
        Deque<String> queue = new ArrayDeque<>();
        for (String id : memberIds) {
            if (indegree.get(id) == 0) {
                queue.add(id);
            }
        }
        int processed = 0;
        while (!queue.isEmpty()) {
            String node = queue.poll();
            processed++;
            for (String next : adjacency.get(node)) {
                int d = indegree.merge(next, -1, Integer::sum);
                if (d == 0) {
                    queue.add(next);
                }
            }
        }
        if (processed < memberIds.size()) {
            throw new BusinessRuleException("批次依赖存在环路（拓扑排序无法覆盖全部成员），整批拒绝");
        }
    }

    // ---------------- 执行门禁支持（供 CutoverService 调用） ----------------

    /** 非锁定读取申请当前绑定的未结束批次 ID。 */
    public String peekOpenBatchId(String requestId) {
        CutoverRequestPO po = repo.findRequest(requestId);
        return po == null ? null : po.getOpenBatchId();
    }

    /** 锁定批次行作为同批次并发操作的串行化点；批次不存在时抛 404。 */
    public void lockBatchOrThrow(String batchId) {
        if (repo.lockBatch(batchId) == null) {
            throw new NotFoundException("割接批次不存在: " + batchId);
        }
    }

    /**
     * 判定成员启动门禁。
     *
     * @param lockedMembers 调用方已按申请编号排序锁定的同批全部成员行（持锁），
     *                      前置状态直接取自行内，避免乱序加锁造成跨事务死锁
     * @return 尚未验证封板的直接前置编号（按编号排序）；空集合表示门禁满足，可以启动
     */
    public List<String> waitingPrerequisites(String batchId, String memberId,
                                             Map<String, CutoverRequestPO> lockedMembers) {
        List<String> prereqIds = repo.listBatchEdges(batchId).stream()
                .filter(e -> e.getToRequest().equals(memberId))
                .map(CutoverBatchEdgePO::getFromRequest)
                .collect(Collectors.toCollection(TreeSet::new))
                .stream().toList();
        List<String> waiting = new ArrayList<>();
        for (String id : prereqIds) {
            CutoverRequestPO pred = lockedMembers.get(id);
            if (pred == null || !"SEALED".equals(pred.getStatus())) {
                waiting.add(id);
            }
        }
        return waiting;
    }

    // ---------------- 前置失败的同事务阻断传播 ----------------

    /**
     * 成员进入回退/超期后调用：在同一事务内把“尚未开始（APPROVED）的全部可达后继”
     * 标记为 DEPENDENCY_BLOCKED，记录首个阻断来源集合；已开始成员不动，封板结果不改。
     * 传播结束后尝试关闭批次。
     */
    public void propagateFailureAndMaybeClose(CutoverRequestPO failedMember) {
        String batchId = failedMember.getOpenBatchId();
        if (batchId == null) {
            return;
        }
        if (repo.lockBatch(batchId) == null) {
            return;
        }
        List<CutoverBatchEdgePO> edges = repo.listBatchEdges(batchId);
        Map<String, List<String>> predecessors = new HashMap<>();
        for (CutoverRequestPO m : repo.lockMemberRequests(batchId)) {
            predecessors.putIfAbsent(m.getRequestId(), new ArrayList<>());
        }
        for (CutoverBatchEdgePO e : edges) {
            predecessors.computeIfAbsent(e.getToRequest(), k -> new ArrayList<>())
                    .add(e.getFromRequest());
        }

        // 用最新行状态做不动点传播；每轮把新阻断节点落库，下一轮可经其继续向下传播。
        boolean changed = true;
        while (changed) {
            changed = false;
            Map<String, CutoverRequestPO> current = new TreeMap<>();
            for (CutoverRequestPO m : repo.lockMemberRequests(batchId)) {
                current.put(m.getRequestId(), m);
            }
            for (Map.Entry<String, CutoverRequestPO> entry : current.entrySet()) {
                String id = entry.getKey();
                CutoverRequestPO po = entry.getValue();
                if (!"APPROVED".equals(po.getStatus())) {
                    continue; // 仅阻断尚未开始者；已开始按原回退流程，已封板不改
                }
                List<String> preds = predecessors.getOrDefault(id, List.of());
                TreeSet<String> sources = new TreeSet<>();
                for (String pred : preds) {
                    CutoverRequestPO predPo = current.get(pred);
                    if (predPo == null) {
                        continue;
                    }
                    if (FAILED_STATUS.contains(predPo.getStatus())) {
                        sources.add(pred); // 直接失败前置即阻断来源
                    } else if ("DEPENDENCY_BLOCKED".equals(predPo.getStatus())
                            && predPo.getBlockedSources() != null) {
                        // 经由已阻断前置间接可达：继承其首个阻断来源（失败原点）
                        for (String origin : predPo.getBlockedSources().split(",")) {
                            if (!origin.isBlank()) {
                                sources.add(origin);
                            }
                        }
                    }
                }
                if (!sources.isEmpty()) {
                    blockMember(batchId, po, sources);
                    changed = true;
                }
            }
        }

        tryCloseBatch(batchId);
    }

    private void blockMember(String batchId, CutoverRequestPO po, TreeSet<String> sources) {
        String joined = String.join(",", sources);
        LocalDateTime now = LocalDateTime.now();
        po.setStatus("DEPENDENCY_BLOCKED");
        po.setBlockedFlag(true);
        po.setBlockedSources(joined);
        po.setUpdatedAt(now);
        repo.updateMutable(po);

        for (String source : sources) {
            CutoverBlockPO b = new CutoverBlockPO();
            b.setBatchId(batchId);
            b.setRequestId(po.getRequestId());
            b.setSourceRequest(source);
            b.setCreatedAt(now);
            try {
                repo.insertBlock(b);
            } catch (DuplicateKeyException ignore) {
                // 同 (批次,被阻断,来源) 已存在则保留首个来源集合，不覆盖。
            }
        }
        insertBatchEvent(po.getRequestId(), EventType.DEPENDENCY_BLOCKED,
                FingerprintUtil.sha256("DEP_BLOCK", batchId, po.getRequestId(), joined),
                "前置失败传播，申请 " + po.getRequestId() + " 转依赖阻断，首个阻断来源集合 ["
                        + joined + "]");
    }

    /**
     * 成员封板等收口动作后尝试关闭批次：全部成员到达终态则批次 CLOSED 并释放成员
     * 的未结束批次占用（open_batch_id 置空）。
     */
    public void tryCloseBatch(String batchId) {
        if (batchId == null) {
            return;
        }
        CutoverBatchPO batch = repo.lockBatch(batchId);
        if (batch == null || BatchStatus.CLOSED.name().equals(batch.getStatus())) {
            return;
        }
        List<CutoverRequestPO> members = repo.lockMemberRequests(batchId);
        boolean allTerminal = members.stream()
                .allMatch(m -> MEMBER_TERMINAL.contains(m.getStatus()));
        if (!allTerminal) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        batch.setStatus(BatchStatus.CLOSED.name());
        if (batch.getResultSnapshot() == null || batch.getResultSnapshot().isBlank()) {
            batch.setResultSnapshot(writeSnapshot("批次全部成员到达终态，批次关闭"));
        }
        batch.setUpdatedAt(now);
        repo.updateBatchResultAndStatus(batch);
        repo.clearOpenBatchForMembers(batchId);
    }

    // ---------------- 查询 ----------------

    @Transactional(readOnly = true)
    public BatchView get(String batchId) {
        CutoverBatchPO batch = repo.findBatch(batchId);
        if (batch == null) {
            throw new NotFoundException("割接批次不存在: " + batchId);
        }
        List<CutoverBatchMemberPO> members = repo.listBatchMembers(batchId);
        List<CutoverBatchEdgePO> edges = repo.listBatchEdges(batchId);
        return toView(batch,
                members.stream().map(CutoverBatchMemberPO::getRequestId).toList(),
                edges.stream().map(e -> new DependencyEdgeInput(e.getFromRequest(), e.getToRequest()))
                        .toList());
    }

    @Transactional(readOnly = true)
    public TopologyView topology(String batchId) {
        BatchView view = get(batchId);
        Map<String, List<String>> prerequisites = new TreeMap<>();
        for (BatchMemberView m : view.members()) {
            prerequisites.put(m.requestId(), new ArrayList<>());
        }
        for (BatchEdgeView e : view.edges()) {
            prerequisites.computeIfAbsent(e.toRequestId(), k -> new ArrayList<>())
                    .add(e.fromRequestId());
        }
        Map<String, BatchMemberStateView> state = new HashMap<>();
        for (BatchMemberStateView s : view.memberStates()) {
            state.put(s.requestId(), s);
        }
        List<TopologyNodeView> nodes = new ArrayList<>();
        for (String id : new TreeSet<>(prerequisites.keySet())) {
            List<String> preds = prerequisites.get(id).stream().sorted().toList();
            BatchMemberStateView s = state.get(id);
            String status = s == null ? null : s.status();
            List<String> blockedSources = s == null ? List.of() : s.blockedSources();
            List<String> waitingOn = "APPROVED".equals(status)
                    ? preds.stream().filter(p -> {
                        BatchMemberStateView ps = state.get(p);
                        return ps == null || !"SEALED".equals(ps.status());
                    }).sorted().toList()
                    : List.of();
            boolean ready = "APPROVED".equals(status) && waitingOn.isEmpty();
            nodes.add(new TopologyNodeView(id, status, preds, ready, waitingOn, blockedSources));
        }
        return new TopologyView(batchId, nodes, view.edges());
    }

    @Transactional(readOnly = true)
    public List<BlockView> blocks(String batchId) {
        if (repo.findBatch(batchId) == null) {
            throw new NotFoundException("割接批次不存在: " + batchId);
        }
        Map<String, TreeSet<String>> grouped = new TreeMap<>();
        for (CutoverBlockPO b : repo.listBlocks(batchId)) {
            grouped.computeIfAbsent(b.getRequestId(), k -> new TreeSet<>())
                    .add(b.getSourceRequest());
        }
        return grouped.entrySet().stream()
                .map(e -> new BlockView(e.getKey(), List.copyOf(e.getValue())))
                .toList();
    }

    // ---------------- 内部方法 ----------------

    private BatchView resolveIdempotent(CutoverBatchPO batch, String fp) {
        if (!batch.getFingerprint().equals(fp)) {
            throw new ConflictException("批次 requestId=" + batch.getBatchId()
                    + " 已存在但业务参数（版本/成员集合/依赖集合/发布时刻）不一致（同键异参）");
        }
        List<CutoverBatchMemberPO> members = repo.listBatchMembers(batch.getBatchId());
        List<DependencyEdgeInput> edges = repo.listBatchEdges(batch.getBatchId()).stream()
                .map(e -> new DependencyEdgeInput(e.getFromRequest(), e.getToRequest()))
                .toList();
        return toView(batch,
                members.stream().map(CutoverBatchMemberPO::getRequestId).toList(), edges);
    }

    private BatchView toView(CutoverBatchPO batch, List<String> orderedMemberIds,
                             List<DependencyEdgeInput> edges) {
        String batchId = batch.getBatchId();
        List<CutoverBatchMemberPO> memberRows = repo.listBatchMembers(batchId);
        Map<String, CutoverBatchMemberPO> memberRow = new HashMap<>();
        for (CutoverBatchMemberPO m : memberRows) {
            memberRow.put(m.getRequestId(), m);
        }
        List<BatchMemberView> memberViews = orderedMemberIds.stream().sorted().map(id -> {
            CutoverBatchMemberPO m = memberRow.get(id);
            return new BatchMemberView(id, m.getExpectedVersion(), m.getWindowStart(),
                    m.getWindowEnd(), m.getOrd());
        }).toList();

        List<BatchEdgeView> edgeViews = repo.listBatchEdges(batchId).stream()
                .map(e -> new BatchEdgeView(e.getFromRequest(), e.getToRequest(), e.getOrd()))
                .toList();

        List<CutoverRequestPO> live = repo.listMemberRequests(batchId);
        Map<String, CutoverRequestPO> liveById = new HashMap<>();
        for (CutoverRequestPO po : live) {
            liveById.put(po.getRequestId(), po);
        }
        Map<String, TreeSet<String>> sourceGroups = new HashMap<>();
        for (CutoverBlockPO b : repo.listBlocks(batchId)) {
            sourceGroups.computeIfAbsent(b.getRequestId(), k -> new TreeSet<>())
                    .add(b.getSourceRequest());
        }
        List<BatchMemberStateView> states = orderedMemberIds.stream().sorted().map(id -> {
            CutoverRequestPO po = liveById.get(id);
            TreeSet<String> sources = sourceGroups.getOrDefault(id, new TreeSet<>());
            return new BatchMemberStateView(id, po == null ? 0 : po.getVersionNo(),
                    po == null ? null : po.getStatus(), List.copyOf(sources));
        }).toList();
        List<BlockView> blockViews = sourceGroups.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(e -> new BlockView(e.getKey(), List.copyOf(e.getValue())))
                .toList();

        return new BatchView(batchId, batch.getFrozenAt(), batch.getStatus(),
                memberViews, edgeViews, states, blockViews);
    }

    private List<DependencyEdgeInput> uniqueSortedEdges(List<DependencyEdgeInput> edges) {
        Map<String, DependencyEdgeInput> unique = new LinkedHashMap<>();
        for (DependencyEdgeInput e : edges) {
            unique.putIfAbsent(e.fromRequestId() + " -> " + e.toRequestId(), e);
        }
        return unique.values().stream()
                .sorted((a, b) -> {
                    int c = a.fromRequestId().compareTo(b.fromRequestId());
                    return c != 0 ? c : a.toRequestId().compareTo(b.toRequestId());
                })
                .toList();
    }

    /**
     * 结构化指纹：嵌套 JSON 数组保留字段边界，申请编号等文本即使包含分隔符也无法逃逸结构。
     * 顺序固定，成员/边集合先排序，覆盖 requestId、发布时刻、成员（编号+版本）与依赖边集合。
     */
    private String fingerprint(PublishBatchRequest req) {
        List<Object> memberFp = req.members().stream()
                .map(m -> {
                    List<Object> pair = new ArrayList<>();
                    pair.add(m.requestId());
                    pair.add(m.expectedVersion());
                    return pair;
                })
                .sorted((a, b) -> ((String) a.get(0)).compareTo((String) b.get(0)))
                .map(p -> (Object) p)
                .collect(Collectors.toList());
        List<Object> edgeFp = uniqueSortedEdges(req.edges()).stream()
                .map(e -> {
                    List<Object> pair = new ArrayList<>();
                    pair.add(e.fromRequestId());
                    pair.add(e.toRequestId());
                    return (Object) pair;
                })
                .toList();
        return FingerprintUtil.sha256("BATCH_PUBLISH", req.requestId(), req.frozenAt(),
                memberFp, edgeFp);
    }

    private String writeMembersSnapshot(Map<String, CutoverRequestPO> byId,
                                        Map<String, Long> expectedVersion) {
        List<Map<String, Object>> snapshot = new ArrayList<>();
        for (String id : new TreeSet<>(byId.keySet())) {
            CutoverRequestPO po = byId.get(id);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("requestId", id);
            m.put("expectedVersion", expectedVersion.get(id));
            m.put("windowStart", po.getWindowStart());
            m.put("windowEnd", po.getWindowEnd());
            snapshot.add(m);
        }
        return writeJson(snapshot);
    }

    private String writeEdgesSnapshot(List<DependencyEdgeInput> edges) {
        List<Map<String, String>> snapshot = new ArrayList<>();
        for (DependencyEdgeInput e : edges) {
            Map<String, String> m = new LinkedHashMap<>();
            m.put("from", e.fromRequestId());
            m.put("to", e.toRequestId());
            snapshot.add(m);
        }
        return writeJson(snapshot);
    }

    private void insertBatchEvent(String requestId, EventType type, String fingerprint,
                                  String message) {
        // 批次级/系统级证据事件：requestUid 使用稳定前缀，避免与申请回报键冲突。
        String uid = "SYS-" + type.name() + "-" + requestId;
        if (repo.findEvent(uid) != null) {
            return;
        }
        CutoverEventPO po = new CutoverEventPO();
        po.setRequestUid(uid);
        po.setRequestId(requestId);
        po.setEventType(type.name());
        po.setPayloadFingerprint(fingerprint);
        po.setResultSnapshot(writeSnapshot(message));
        po.setLateFlag(false);
        po.setCreatedAt(LocalDateTime.now());
        try {
            repo.insertEvent(po);
        } catch (DuplicateKeyException ignore) {
            // 并发下首事件已提交，保留首个。
        }
    }

    private String writeJson(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("快照序列化失败", e);
        }
    }

    private String writeSnapshot(String message) {
        return writeJson(Map.of("message", message));
    }
}
