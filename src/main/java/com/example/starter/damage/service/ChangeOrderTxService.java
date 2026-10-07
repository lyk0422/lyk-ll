package com.example.starter.damage.service;

import com.example.starter.damage.api.ApiException;
import com.example.starter.damage.domain.CaseStatus;
import com.example.starter.damage.domain.ChangeDecision;
import com.example.starter.damage.domain.ChangeOrderStatus;
import com.example.starter.damage.domain.Determination;
import com.example.starter.damage.domain.QualificationStatus;
import com.example.starter.damage.dto.ChangeOrderRequests.ChangeLineInput;
import com.example.starter.damage.dto.ChangeOrderRequests.ChangeReviewRequest;
import com.example.starter.damage.dto.ChangeOrderRequests.CreateChangeOrderRequest;
import com.example.starter.damage.dto.ChangeOrderRequests.WithdrawChangeOrderRequest;
import com.example.starter.damage.dto.ChangeOrderViews.ChangeOrderLineView;
import com.example.starter.damage.dto.ChangeOrderViews.ChangeOrderView;
import com.example.starter.damage.dto.ChangeOrderViews.ChangeReviewView;
import com.example.starter.damage.dto.Views.ApprovalView;
import com.example.starter.damage.dto.Views.DamageItemView;
import com.example.starter.damage.store.ChangeOrderRepository;
import com.example.starter.damage.store.ChangeOrderRepository.ChangeOrderLineRow;
import com.example.starter.damage.store.ChangeOrderRepository.ChangeOrderRow;
import com.example.starter.damage.store.DamageCaseRepository;
import com.example.starter.damage.store.DamageCaseRepository.CaseRow;
import com.example.starter.damage.store.DamageFlowRepository;
import com.example.starter.damage.store.InquiryRepository;
import com.example.starter.damage.store.InquiryRepository.BidLineRow;
import com.example.starter.damage.store.InquiryRepository.InquiryItemRow;
import com.example.starter.damage.store.InquiryRepository.OpeningRow;
import com.example.starter.damage.store.SupplierRepository;
import com.example.starter.damage.store.SupplierRepository.QualRow;
import com.example.starter.damage.store.SupplierRepository.SupplierRow;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 修理变更单事务内服务。
 *
 * <p>核心规则：
 * <ul>
 *   <li>仅已批复（询价中标来源）且尚未整案完工的修箱案可申请；申请冻结原批复、全清单与资格版本快照。</li>
 *   <li>每案最多一个活动候选；候选期间原完工入口拒绝（与完工经案件行锁串行，不能已完工又改批复）。</li>
 *   <li>两名不同复核人均同意才切换；最终事务重查供应商资格、当前批复与案状态，
 *       逐残损项按 30% 全损规则判定（不以全案合计当单项阈值），构成全损则不得转修理批复。</li>
 *   <li>生效形成新估价与新批复版本，旧批复/旧估价失效；原询价、报价与开标证据不覆盖。</li>
 *   <li>待生效候选可撤回或被驳回，解除完工阻断并保留原有效批复；生效后不可撤回。</li>
 *   <li>批准与资格撤销经供应商行锁串行；单据版本从 1 起，修改携带 expectedVersion，成功 +1，旧版 409。</li>
 * </ul>
 */
@Service
public class ChangeOrderTxService {

    /** 全损阈值：单项修理费超过箱值 30% 判全损，恰 30% 按修理处理（与原估价规则一致） */
    private static final BigDecimal TOTAL_LOSS_THRESHOLD = new BigDecimal("0.30");

    /** 单次申请最多变更残损项数 */
    private static final int MAX_CHANGE_LINES = 10;

    private final ChangeOrderRepository changeOrderRepository;
    private final DamageCaseRepository caseRepository;
    private final DamageFlowRepository flowRepository;
    private final InquiryRepository inquiryRepository;
    private final SupplierRepository supplierRepository;
    private final IdempotencyExecutor idempotency;
    private final Clock clock;

    public ChangeOrderTxService(ChangeOrderRepository changeOrderRepository,
                                DamageCaseRepository caseRepository,
                                DamageFlowRepository flowRepository,
                                InquiryRepository inquiryRepository,
                                SupplierRepository supplierRepository,
                                IdempotencyExecutor idempotency,
                                Clock clock) {
        this.changeOrderRepository = changeOrderRepository;
        this.caseRepository = caseRepository;
        this.flowRepository = flowRepository;
        this.inquiryRepository = inquiryRepository;
        this.supplierRepository = supplierRepository;
        this.idempotency = idempotency;
        this.clock = clock;
    }

    private Instant now() {
        return Instant.now(clock);
    }

    // ============================ 申请候选 ============================

    /**
     * 申请修理变更候选：绑定当前有效批复与中标供应商，冻结原批复、全清单与资格版本快照。
     * 与完工登记经案件行锁串行：完工先提交则案状态已变，申请拒绝；候选先建立则完工被阻断。
     */
    @Transactional
    public ChangeOrderView create(long caseId, CreateChangeOrderRequest request) {
        CaseRow caseRow = caseRepository.findByIdForUpdate(caseId)
                .orElseThrow(() -> ApiException.notFound("残损案不存在: " + caseId));
        if (caseRow.status() != CaseStatus.APPROVED || caseRow.determination() != Determination.REPAIR) {
            throw ApiException.unprocessable("仅已批复且尚未整案完工的修箱案可申请修理变更，当前状态: "
                    + caseRow.status() + "，判定: " + caseRow.determination());
        }
        ApprovalView approval = flowRepository.findActiveApproval(caseId);
        if (approval == null) {
            throw ApiException.unprocessable("无有效修理批复，无法申请修理变更");
        }
        if (!"INQUIRY".equals(approval.source()) || approval.openingId() == null) {
            throw ApiException.unprocessable("仅密封询价中标批复可申请修理变更，当前批复来源: " + approval.source());
        }
        OpeningRow opening = inquiryRepository.findOpeningById(approval.openingId())
                .orElseThrow(() -> ApiException.unprocessable("原中标开标证据缺失，无法申请修理变更"));
        if (!"AWARDED".equals(opening.status()) || opening.winnerSupplierId() == null) {
            throw ApiException.unprocessable("原开标无中标供应商，无法申请修理变更");
        }
        SupplierRow supplier = supplierRepository.findById(opening.winnerSupplierId())
                .orElseThrow(() -> ApiException.unprocessable("原中标供应商不存在，无法申请修理变更"));

        int existing = changeOrderRepository.countByCase(caseId);
        if (request.expectedVersion() != existing) {
            throw ApiException.conflict("变更单版本已变化，期望数量 " + request.expectedVersion()
                    + " 与当前数量 " + existing + " 不一致");
        }
        if (changeOrderRepository.hasActiveCandidate(caseId)) {
            throw ApiException.unprocessable("本案已存在待生效的修理变更候选，每案最多一个活动候选");
        }

        List<ChangeLineInput> lines = request.lines();
        if (lines.isEmpty() || lines.size() > MAX_CHANGE_LINES) {
            throw ApiException.badRequest("变更残损项数量须为 1 至 " + MAX_CHANGE_LINES + "，实供 " + lines.size());
        }
        Map<Long, ChangeLineInput> changeByItem = new LinkedHashMap<>();
        for (ChangeLineInput line : lines) {
            if (changeByItem.put(line.damageItemId(), line) != null) {
                throw ApiException.badRequest("变更残损项不得重复: " + line.damageItemId());
            }
        }

        // 原中标全清单：按询价行锚定的残损项还原每项原工法与金额
        Map<Long, Long> itemIdByInquiryItem = inquiryRepository.listInquiryItems(opening.inquiryId()).stream()
                .collect(Collectors.toMap(InquiryItemRow::id, InquiryItemRow::damageItemId));
        Map<Long, BidLineRow> winLineByItem = new LinkedHashMap<>();
        for (BidLineRow line : inquiryRepository.listBidLines(opening.winnerBidVersionId())) {
            Long damageItemId = itemIdByInquiryItem.get(line.inquiryItemId());
            if (damageItemId != null) {
                winLineByItem.put(damageItemId, line);
            }
        }

        List<DamageItemView> caseItems = flowRepository.listItems(caseId);
        Map<Long, DamageItemView> caseItemMap = caseItems.stream()
                .collect(Collectors.toMap(DamageItemView::id, Function.identity(), (a, b) -> a, LinkedHashMap::new));
        Set<String> supplierMethods = new LinkedHashSet<>(supplierRepository.listMethods(supplier.id()));

        // 整单校验全部通过后再落库：跨案项、未知工法、供应商不具资格一律拒绝
        for (ChangeLineInput line : lines) {
            if (!caseItemMap.containsKey(line.damageItemId())) {
                throw ApiException.badRequest("变更残损项不属于本案或不存在（不允许跨案变更）: " + line.damageItemId());
            }
            if (!supplierRepository.methodExists(line.methodCode())) {
                throw ApiException.badRequest("未知修箱工法代码: " + line.methodCode());
            }
            if (!supplierMethods.contains(line.methodCode())) {
                throw ApiException.unprocessable("残损项 " + line.damageItemId() + " 的工法 " + line.methodCode()
                        + " 不在中标供应商适用工法内，供应商不具对应资格");
            }
            if (line.amountCents() <= 0) {
                throw ApiException.badRequest("变更报价必须为正整数分");
            }
        }

        QualRow qual = supplierRepository.findLatestQualification(supplier.id())
                .orElseThrow(() -> ApiException.unprocessable("中标供应商无资格版本快照，无法申请修理变更"));

        // 冻结全清单快照：未改项沿用原中标工法与金额
        List<SnapshotLine> snapshot = new ArrayList<>();
        long originalTotal = 0;
        long newTotal = 0;
        int lineNo = 1;
        for (DamageItemView item : caseItems) {
            BidLineRow winLine = winLineByItem.get(item.id());
            if (winLine == null) {
                throw ApiException.unprocessable("残损项 " + item.id() + " 缺少原中标报价行，无法冻结全清单");
            }
            ChangeLineInput change = changeByItem.get(item.id());
            String afterMethod = change == null ? winLine.methodCode() : change.methodCode();
            long afterAmount = change == null ? winLine.amountCents() : change.amountCents();
            snapshot.add(new SnapshotLine(item.id(), lineNo, change != null,
                    winLine.methodCode(), winLine.amountCents(), afterMethod, afterAmount));
            originalTotal += winLine.amountCents();
            newTotal += afterAmount;
            lineNo++;
        }

        Instant now = now();
        long changeOrderId;
        try {
            changeOrderId = changeOrderRepository.insertChangeOrder(caseId, approval.id(), opening.id(),
                    supplier.id(), qual.version(), originalTotal, newTotal, request.requestId(), now);
        } catch (DuplicateKeyException e) {
            // 并发申请由 active_case_id 唯一索引仲裁
            throw ApiException.unprocessable("本案已存在待生效的修理变更候选，每案最多一个活动候选");
        }
        for (SnapshotLine line : snapshot) {
            changeOrderRepository.insertLine(changeOrderId, line.damageItemId, line.lineNo, line.changed,
                    line.beforeMethod, line.beforeAmountCents, line.afterMethod, line.afterAmountCents);
        }

        ChangeOrderView view = toView(requireOrder(changeOrderId));
        idempotency.completeInTransaction(request.requestId(), view);
        return view;
    }

    private record SnapshotLine(long damageItemId, int lineNo, boolean changed,
                                String beforeMethod, long beforeAmountCents,
                                String afterMethod, long afterAmountCents) {
    }

    // ============================ 复核与切换 ============================

    /**
     * 复核变更候选：两名不同复核人均同意才切换。
     * 切换在同一事务内重查供应商资格、当前批复与案状态，再形成新估价及批复版本，旧批复/旧估价失效。
     */
    @Transactional
    public ChangeOrderView review(long changeOrderId, ChangeReviewRequest request) {
        ChangeOrderRow order = lockOrder(changeOrderId);
        if (order.status() != ChangeOrderStatus.PENDING) {
            throw ApiException.unprocessable("变更候选已" + order.status() + "，不能再复核");
        }
        if (request.expectedVersion() != order.version()) {
            throw ApiException.conflict("变更单版本已变化，期望版本 " + request.expectedVersion()
                    + " 与当前版本 " + order.version() + " 不一致");
        }
        List<String> reviewers = changeOrderRepository.listReviews(changeOrderId).stream()
                .map(ChangeOrderRepository.ChangeReviewRow::reviewer).distinct().toList();
        if (reviewers.contains(request.reviewer())) {
            throw ApiException.unprocessable("同一复核人不得重复复核: " + request.reviewer());
        }
        Instant now = now();
        try {
            changeOrderRepository.insertReview(changeOrderId, request.reviewer(),
                    request.decision().name(), request.requestId(), now);
        } catch (DuplicateKeyException e) {
            throw ApiException.unprocessable("同一复核人不得重复复核: " + request.reviewer());
        }
        int newVersion = order.version() + 1;

        if (request.decision() == ChangeDecision.REJECT) {
            // 任一驳回即驳回候选：解除完工阻断，保留原有效批复
            changeOrderRepository.decide(changeOrderId, order.version(), ChangeOrderStatus.REJECTED,
                    newVersion, null, null, now, now);
            ChangeOrderView view = toView(requireOrder(changeOrderId));
            idempotency.completeInTransaction(request.requestId(), view);
            return view;
        }

        List<String> approvers = changeOrderRepository.listReviews(changeOrderId).stream()
                .filter(r -> ChangeDecision.APPROVE.name().equals(r.decision()))
                .map(ChangeOrderRepository.ChangeReviewRow::reviewer).distinct().toList();
        if (approvers.size() < 2) {
            // 仅一人同意：不切换，仅推进单据版本
            changeOrderRepository.bumpVersion(changeOrderId, order.version(), newVersion, now);
            ChangeOrderView view = toView(requireOrder(changeOrderId));
            idempotency.completeInTransaction(request.requestId(), view);
            return view;
        }

        // 两名不同复核人齐：最终事务重查资格、当前批复与案状态后切换
        CaseRow caseRow = caseRepository.findByIdForUpdate(order.caseId())
                .orElseThrow(() -> ApiException.notFound("残损案不存在: " + order.caseId()));
        if (caseRow.status() != CaseStatus.APPROVED || caseRow.determination() != Determination.REPAIR) {
            throw ApiException.unprocessable("案状态已变化（" + caseRow.status() + "），不能切换批复");
        }
        ApprovalView active = flowRepository.findActiveApproval(order.caseId());
        if (active == null || active.id() != order.approvalId()) {
            throw ApiException.unprocessable("当前有效批复已非申请时冻结的批复，切换拒绝");
        }
        SupplierRow supplier = supplierRepository.findByIdForUpdate(order.supplierId())
                .orElseThrow(() -> ApiException.unprocessable("中标供应商不存在，切换拒绝"));
        QualRow qual = supplierRepository.findLatestQualificationForUpdate(supplier.id()).orElse(null);
        if (qual == null || qual.status() != QualificationStatus.ACTIVE || !covers(qual, now)) {
            throw ApiException.unprocessable("中标供应商资格已失效或有效期未覆盖当前时刻，切换拒绝");
        }

        // 逐残损项全损判定：新费用按单项对箱值 30%，不以全案合计当单项阈值
        BigDecimal threshold = caseRow.containerValue().multiply(TOTAL_LOSS_THRESHOLD);
        List<ChangeOrderLineRow> lines = changeOrderRepository.listLines(changeOrderId);
        for (ChangeOrderLineRow line : lines) {
            BigDecimal itemCost = BigDecimal.valueOf(line.afterAmountCents()).movePointLeft(2);
            if (itemCost.compareTo(threshold) > 0) {
                throw ApiException.unprocessable("变更后残损项 " + line.damageItemId()
                        + " 费用超箱值 30% 构成全损，不得转修理批复");
            }
        }
        BigDecimal newTotal = BigDecimal.valueOf(order.newTotalCents()).movePointLeft(2);

        LocalDateTime nowDateTime = LocalDateTime.now(clock);
        long newEstimateId = flowRepository.insertEstimate(order.caseId(), newTotal, Determination.REPAIR,
                request.requestId(), nowDateTime);
        long newApprovalId = flowRepository.insertApproval(order.caseId(), newEstimateId,
                LocalDate.now(clock), "CHANGE", order.openingId(), nowDateTime);
        caseRepository.updateState(order.caseId(), CaseStatus.APPROVED, Determination.REPAIR, nowDateTime);
        changeOrderRepository.decide(changeOrderId, order.version(), ChangeOrderStatus.EFFECTIVE,
                newVersion, newEstimateId, newApprovalId, now, now);

        ChangeOrderView view = toView(requireOrder(changeOrderId));
        idempotency.completeInTransaction(request.requestId(), view);
        return view;
    }

    /** 资格版本是否覆盖指定时刻：ACTIVE 且 validFrom &lt;= t &lt; validTo（validTo 为空表示长期有效） */
    private boolean covers(QualRow qual, Instant t) {
        if (qual.status() != QualificationStatus.ACTIVE) {
            return false;
        }
        if (t.isBefore(qual.validFrom())) {
            return false;
        }
        return qual.validTo() == null || t.isBefore(qual.validTo());
    }

    // ============================ 撤回 ============================

    /** 撤回待生效候选：解除本候选完工阻断并保留原有效批复；生效后不可撤回 */
    @Transactional
    public ChangeOrderView withdraw(long changeOrderId, WithdrawChangeOrderRequest request) {
        ChangeOrderRow order = lockOrder(changeOrderId);
        if (order.status() != ChangeOrderStatus.PENDING) {
            throw ApiException.unprocessable("仅待复核候选可撤回，当前状态: " + order.status() + "（生效后不可撤回）");
        }
        if (request.expectedVersion() != order.version()) {
            throw ApiException.conflict("变更单版本已变化，期望版本 " + request.expectedVersion()
                    + " 与当前版本 " + order.version() + " 不一致");
        }
        Instant now = now();
        changeOrderRepository.decide(changeOrderId, order.version(), ChangeOrderStatus.WITHDRAWN,
                order.version() + 1, null, null, now, now);
        ChangeOrderView view = toView(requireOrder(changeOrderId));
        idempotency.completeInTransaction(request.requestId(), view);
        return view;
    }

    // ============================ 查询 ============================

    @Transactional(readOnly = true)
    public ChangeOrderView get(long changeOrderId) {
        return toView(requireOrder(changeOrderId));
    }

    @Transactional(readOnly = true)
    public List<ChangeOrderView> listByCase(long caseId) {
        caseRepository.findById(caseId)
                .orElseThrow(() -> ApiException.notFound("残损案不存在: " + caseId));
        return changeOrderRepository.listByCase(caseId).stream().map(this::toView).toList();
    }

    // ============================ 视图装配 ============================

    private ChangeOrderRow lockOrder(long changeOrderId) {
        return changeOrderRepository.findByIdForUpdate(changeOrderId)
                .orElseThrow(() -> ApiException.notFound("修理变更单不存在: " + changeOrderId));
    }

    private ChangeOrderRow requireOrder(long changeOrderId) {
        return changeOrderRepository.findById(changeOrderId)
                .orElseThrow(() -> ApiException.notFound("修理变更单不存在: " + changeOrderId));
    }

    private ChangeOrderView toView(ChangeOrderRow order) {
        List<ChangeOrderLineView> lines = changeOrderRepository.listLines(order.id()).stream()
                .map(l -> new ChangeOrderLineView(l.damageItemId(), l.lineNo(), l.changed(),
                        l.beforeMethod(), l.beforeAmountCents(), l.afterMethod(), l.afterAmountCents()))
                .toList();
        List<ChangeReviewView> reviews = changeOrderRepository.listReviews(order.id()).stream()
                .map(r -> new ChangeReviewView(r.reviewer(), ChangeDecision.valueOf(r.decision())))
                .toList();
        String supplierNo = supplierRepository.findById(order.supplierId())
                .map(SupplierRow::supplierNo).orElse(null);
        return new ChangeOrderView(order.id(), order.caseId(), order.version(), order.status(),
                order.approvalId(), order.openingId(), order.supplierId(), supplierNo,
                order.qualificationVersion(), order.originalTotalCents(), order.newTotalCents(),
                order.newEstimateId(), order.newApprovalId(), lines, reviews,
                order.createdAt(), order.decidedAt());
    }
}
