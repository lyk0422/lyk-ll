package com.example.starter.lot.service;

import com.example.starter.lot.api.BalanceView;
import com.example.starter.lot.api.ChildOutput;
import com.example.starter.lot.api.CreateLotRequest;
import com.example.starter.lot.api.GenealogyView;
import com.example.starter.lot.api.ImpactView;
import com.example.starter.lot.api.LotView;
import com.example.starter.lot.api.ParentInput;
import com.example.starter.lot.api.QuarantineRequest;
import com.example.starter.lot.api.QuarantineResponse;
import com.example.starter.lot.api.TransformRequest;
import com.example.starter.lot.api.TransformResponse;
import com.example.starter.lot.model.Lot;
import com.example.starter.lot.model.LotStatus;
import com.example.starter.lot.repository.LotRepository;
import com.example.starter.lot.repository.LotRepository.TransformRecordRow;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 制造批次谱系与隔离传播应用服务。
 *
 * <p>转换与隔离均通过 {@code tx_mutex} 单行表锁串行化，保证两类操作之间存在确定的
 * 提交顺序：先提交的隔离必然被后提交转换看到并传播给新子批次；先提交的转换产生的
 * 子批次必然落入后提交隔离的传播范围。并发转换则在锁内重新校验 expectedVersion，
 * 并以条件 UPDATE 兜底，杜绝父批次超用。</p>
 */
@Service
public class LotService {

    private static final String LEDGER_INITIAL = "INITIAL";
    private static final String LEDGER_CONSUME = "CONSUME";
    private static final String LEDGER_PRODUCE = "PRODUCE";
    private static final String ACTION_SET = "SET";
    private static final String ACTION_RELEASE = "RELEASE";

    private final LotRepository repo;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public LotService(LotRepository repo, ObjectMapper objectMapper, Clock clock) {
        this.repo = repo;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /** 建批次：初始数量三位小数为正，ID 冲突返回 409。 */
    @Transactional
    public LotView createLot(CreateLotRequest request) {
        requireThreeDecimals(request.quantity(), "初始数量");
        if (request.quantity().signum() <= 0) {
            throw new BadRequestException("初始数量必须为正");
        }
        if (repo.lotExists(request.lotId())) {
            throw new ConflictException("批次已存在: " + request.lotId());
        }
        LocalDateTime now = LocalDateTime.now(clock);
        BigDecimal qty = scale3(request.quantity());
        Lot lot = new Lot(request.lotId(), request.unit(), qty, qty, BigDecimal.ZERO.setScale(3),
                LotStatus.AVAILABLE, 0L, now);
        repo.insertLot(lot);
        repo.insertLedger(null, lot.lotId(), LEDGER_INITIAL, qty, qty, now);
        return toView(lot, List.of());
    }

    @Transactional(readOnly = true)
    public LotView getLot(String lotId) {
        Lot lot = loadLot(lotId);
        return toView(lot, repo.findActiveSources(lotId));
    }

    /**
     * 原子转换。规则：
     * 总产出 &le; 总消费，且 总消费 - 总产出 &le; 允许损耗；
     * 父批次部分消费允许，但条件更新保证并发不超用；
     * 子批次 ID 必须全新；单位必须一致；新边不得成环；
     * requestId 幂等：同键同参重放首次结果，同键异参 409。
     */
    @Transactional
    public TransformResponse transform(TransformRequest request) {
        repo.lockMutex();

        String fingerprint = fingerprint(request);
        var existing = repo.findTransformRecord(request.requestId());
        if (existing.isPresent()) {
            TransformRecordRow row = existing.get();
            if (!row.fingerprint().equals(fingerprint)) {
                throw new ConflictException("requestId 已用于不同参数的转换: " + request.requestId());
            }
            return replay(row);
        }

        validateShape(request);
        requireThreeDecimals(request.allowedLoss(), "允许损耗");
        if (request.allowedLoss().signum() < 0) {
            throw new BadRequestException("允许损耗不得为负");
        }
        for (ParentInput parent : request.parents()) {
            requireThreeDecimals(parent.quantity(), "父批次消费量");
            if (parent.quantity().signum() <= 0) {
                throw new BadRequestException("父批次消费量必须为正: " + parent.lotId());
            }
        }
        for (ChildOutput child : request.children()) {
            requireThreeDecimals(child.quantity(), "子批次产出量");
            if (child.quantity().signum() <= 0) {
                throw new BadRequestException("子批次产出量必须为正: " + child.lotId());
            }
        }

        List<ParentInput> parents = request.parents();
        List<ChildOutput> children = request.children();

        // 子批次 ID 必须全局全新（含请求内重复、与父批次重合）。
        Set<String> childIds = new LinkedHashSet<>();
        for (ChildOutput child : children) {
            if (!childIds.add(child.lotId())) {
                throw new ConflictException("子批次 ID 在本次请求中重复: " + child.lotId());
            }
            if (repo.lotExists(child.lotId())) {
                throw new ConflictException("子批次 ID 已存在: " + child.lotId());
            }
        }

        Map<String, Lot> parentLots = new LinkedHashMap<>();
        for (ParentInput parent : parents) {
            Lot lot = loadLot(parent.lotId());
            if (!lot.unit().equals(request.unit())) {
                throw new BadRequestException(
                        "批次 " + lot.lotId() + " 单位 " + lot.unit() + " 与转换单位 " + request.unit() + " 不一致");
            }
            if (lot.version() != parent.expectedVersion()) {
                throw new ConflictException(
                        "批次 " + lot.lotId() + " expectedVersion=" + parent.expectedVersion()
                                + " 与当前版本 " + lot.version() + " 不一致");
            }
            parentLots.put(lot.lotId(), lot);
        }

        BigDecimal totalConsumed = BigDecimal.ZERO.setScale(3);
        for (ParentInput parent : parents) {
            totalConsumed = totalConsumed.add(scale3(parent.quantity()));
        }
        BigDecimal totalProduced = BigDecimal.ZERO.setScale(3);
        for (ChildOutput child : children) {
            totalProduced = totalProduced.add(scale3(child.quantity()));
        }
        BigDecimal loss = totalConsumed.subtract(totalProduced);
        if (loss.signum() < 0) {
            throw new BadRequestException("总产出 " + totalProduced + " 超过总消费 " + totalConsumed);
        }
        if (loss.compareTo(scale3(request.allowedLoss())) > 0) {
            throw new BadRequestException(
                    "损耗 " + loss + " 超过本次允许损耗 " + scale3(request.allowedLoss()));
        }

        // 成环防御：新边 parent -> child，若 child 已是 parent 的祖先则成环。
        // 正常路径下 child 均为全新批次不可能成环，此处显式保证契约。
        for (ParentInput parent : parents) {
            List<String> ancestorIds = repo.findAllAncestors(parent.lotId());
            for (String childId : childIds) {
                if (ancestorIds.contains(childId)) {
                    throw new BadRequestException(
                            "转换将形成谱系环: " + childId + " 已是 " + parent.lotId() + " 的祖先");
                }
            }
        }

        LocalDateTime now = LocalDateTime.now(clock);

        // 新子批次继承全部父批次当前仍生效的隔离来源集合（并集）。
        Set<String> inheritedSources = new LinkedHashSet<>();
        for (String parentId : parentLots.keySet()) {
            inheritedSources.addAll(repo.findActiveSources(parentId));
        }
        LotStatus childStatus = inheritedSources.isEmpty() ? LotStatus.AVAILABLE : LotStatus.QUARANTINED;

        // 条件消费：版本不符或余额不足均整笔回滚（409）。
        for (ParentInput parent : parents) {
            BigDecimal qty = scale3(parent.quantity());
            int updated = repo.tryConsume(parent.lotId(), qty, parent.expectedVersion());
            if (updated == 0) {
                Lot current = loadLot(parent.lotId());
                if (current.availableQty().compareTo(qty) < 0) {
                    throw new ConflictException(
                            "批次 " + parent.lotId() + " 可用余额 " + current.availableQty()
                                    + " 不足消费 " + qty);
                }
                throw new ConflictException("批次 " + parent.lotId() + " 版本已变化，请重试");
            }
        }

        // 产出子批次并登记全部 parent x child 谱系边。
        for (ChildOutput child : children) {
            BigDecimal qty = scale3(child.quantity());
            Lot childLot = new Lot(child.lotId(), request.unit(), BigDecimal.ZERO.setScale(3),
                    qty, BigDecimal.ZERO.setScale(3), childStatus, 0L, now);
            repo.insertChildLot(childLot);
            for (String source : inheritedSources) {
                repo.addQuarantineSource(child.lotId(), source, now);
            }
        }
        for (String parentId : parentLots.keySet()) {
            for (String childId : childIds) {
                repo.insertLink(request.requestId(), parentId, childId);
            }
        }

        // 不可变台账。
        for (ParentInput parent : parents) {
            BigDecimal qty = scale3(parent.quantity());
            Lot after = loadLot(parent.lotId());
            repo.insertLedger(request.requestId(), parent.lotId(), LEDGER_CONSUME, qty,
                    after.availableQty(), now);
        }
        for (ChildOutput child : children) {
            repo.insertLedger(request.requestId(), child.lotId(), LEDGER_PRODUCE,
                    scale3(child.quantity()), scale3(child.quantity()), now);
        }

        // 请求台账先落库（response 回填），历史不可变。
        String requestJson = writeJson(request);
        repo.insertTransformRecord(request, fingerprint, requestJson, totalConsumed,
                totalProduced, now);

        TransformResponse response = new TransformResponse(
                request.requestId(), false, request.unit(), totalConsumed, totalProduced, loss,
                parents.stream().map(ParentInput::lotId).toList(),
                children.stream().map(ChildOutput::lotId).toList());
        repo.updateTransformResponse(request.requestId(), writeJson(response));
        return response;
    }

    /**
     * 设置/解除隔离并沿谱系传播。
     * SET：来源 = 被操作批次自身，加入自身与全部后代；
     * RELEASE：移除该来源，任一批次仅在来源集合清空后才恢复 AVAILABLE。
     */
    @Transactional
    public QuarantineResponse quarantine(QuarantineRequest request) {
        String action = request.action() == null ? "" : request.action().trim().toUpperCase();
        if (!ACTION_SET.equals(action) && !ACTION_RELEASE.equals(action)) {
            throw new BadRequestException("action 必须为 SET 或 RELEASE");
        }
        repo.lockMutex();
        Lot lot = loadLot(request.lotId());
        LocalDateTime now = LocalDateTime.now(clock);
        repo.insertQuarantineEvent(lot.lotId(), action, request.reason(), now);

        List<String> affected;
        if (ACTION_SET.equals(action)) {
            affected = applySet(lot.lotId(), now);
        } else {
            affected = applyRelease(lot.lotId());
        }
        Lot refreshed = loadLot(lot.lotId());
        return new QuarantineResponse(lot.lotId(), action, refreshed.status(), affected, now);
    }

    @Transactional(readOnly = true)
    public GenealogyView genealogy(String lotId) {
        loadLot(lotId);
        return new GenealogyView(lotId, repo.findAllAncestors(lotId), repo.findAllDescendants(lotId));
    }

    @Transactional(readOnly = true)
    public BalanceView balance(String lotId) {
        Lot lot = loadLot(lotId);
        return new BalanceView(lot.lotId(), lot.unit(), lot.availableQty(), lot.status().name());
    }

    @Transactional(readOnly = true)
    public ImpactView impact(String lotId) {
        loadLot(lotId);
        List<String> carriedLots = repo.findLotsWithSource(lotId);
        boolean sourceActive = repo.findActiveSources(lotId).contains(lotId);
        List<ImpactView.AffectedLot> affectedLots = carriedLots.stream()
                .map(id -> new ImpactView.AffectedLot(id, repo.findActiveSources(id)))
                .toList();
        return new ImpactView(lotId, sourceActive, affectedLots);
    }

    // ----- 隔离传播内部实现 -----

    private List<String> applySet(String lotId, LocalDateTime now) {
        List<String> descendants = repo.findAllDescendants(lotId);
        List<String> affected = new ArrayList<>();
        affected.add(lotId);
        affected.addAll(descendants);
        for (String id : affected) {
            repo.addQuarantineSource(id, lotId, now);
            repo.applyStatus(id, LotStatus.QUARANTINED);
        }
        return affected;
    }

    private List<String> applyRelease(String lotId) {
        // 携带该来源的恰好是自身及传播覆盖的后代（含隔离后新建并继承来源的子批次）。
        List<String> carriedLots = repo.findLotsWithSource(lotId);
        List<String> affected = new ArrayList<>();
        for (String id : carriedLots) {
            int removed = repo.removeQuarantineSource(id, lotId);
            if (removed > 0) {
                affected.add(id);
                List<String> remaining = repo.findActiveSources(id);
                // 仅当所有来源均解除后才放行；仍有其他祖先来源时保持隔离。
                repo.applyStatus(id, remaining.isEmpty() ? LotStatus.AVAILABLE : LotStatus.QUARANTINED);
            }
        }
        return affected;
    }

    // ----- 幂等指纹与视图组装 -----

    private TransformResponse replay(TransformRecordRow row) {
        if (row.responseJson() == null) {
            // 理论不可达：首次成功与响应回填在同一事务内提交。
            throw new ConflictException("转换结果尚未完成提交: " + row.requestId());
        }
        try {
            TransformResponse first = objectMapper.readValue(row.responseJson(), TransformResponse.class);
            return new TransformResponse(first.requestId(), true, first.unit(),
                    first.totalConsumed(), first.totalProduced(), first.loss(),
                    first.parentLotIds(), first.childLotIds());
        } catch (Exception e) {
            throw new IllegalStateException("重放转换结果失败: " + row.requestId(), e);
        }
    }

    /**
     * 计算除 requestId 外完整参数的指纹：单位、允许损耗、各父批次（ID、expectedVersion、
     * 消费量）、各子批次（ID、产出量）及列表顺序均纳入；数量统一三位小数规范化。
     */
    private String fingerprint(TransformRequest request) {
        Map<String, Object> canonical = new LinkedHashMap<>();
        canonical.put("unit", request.unit());
        canonical.put("allowedLoss", scale3(request.allowedLoss()).toPlainString());
        List<List<Object>> parents = new ArrayList<>();
        for (ParentInput parent : request.parents()) {
            parents.add(List.of(parent.lotId(), parent.expectedVersion(),
                    scale3(parent.quantity()).toPlainString()));
        }
        canonical.put("parents", parents);
        List<List<Object>> children = new ArrayList<>();
        for (ChildOutput child : request.children()) {
            children.add(List.of(child.lotId(), scale3(child.quantity()).toPlainString()));
        }
        canonical.put("children", children);
        String json = writeJson(canonical);
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(json.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    private void validateShape(TransformRequest request) {
        Set<String> parentIds = new LinkedHashSet<>();
        for (ParentInput parent : request.parents()) {
            if (!parentIds.add(parent.lotId())) {
                throw new BadRequestException("父批次在本次请求中重复: " + parent.lotId());
            }
        }
    }

    private Lot loadLot(String lotId) {
        return repo.findLot(lotId)
                .orElseThrow(() -> new NotFoundException("批次不存在: " + lotId));
    }

    private LotView toView(Lot lot, List<String> sources) {
        return new LotView(lot.lotId(), lot.unit(), lot.initialQty(), lot.availableQty(),
                lot.consumedQty(), lot.status(), lot.version(), sources, lot.createdAt());
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("JSON 序列化失败", e);
        }
    }

    private static BigDecimal scale3(BigDecimal value) {
        return value.setScale(3, java.math.RoundingMode.HALF_UP);
    }

    /** 拒绝超过三位小数的数量，避免静默舍入改变业务口径。 */
    private static void requireThreeDecimals(BigDecimal value, String label) {
        if (value.stripTrailingZeros().scale() > 3) {
            throw new BadRequestException(label + " 最多允许三位小数: " + value.toPlainString());
        }
    }
}
