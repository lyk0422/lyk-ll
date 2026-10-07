package com.example.starter.damage.service;

import com.example.starter.damage.api.ApiException;
import com.example.starter.damage.domain.CaseStatus;
import com.example.starter.damage.domain.ChangeOrderStatus;
import com.example.starter.damage.domain.Determination;
import com.example.starter.damage.domain.QualificationStatus;
import com.example.starter.damage.dto.ChangeOrderRequests.ChangeLineInput;
import com.example.starter.damage.dto.ChangeOrderRequests.CreateChangeOrderRequest;
import com.example.starter.damage.dto.ChangeOrderRequests.ReviewChangeOrderRequest;
import com.example.starter.damage.dto.ChangeOrderRequests.WithdrawChangeOrderRequest;
import com.example.starter.damage.dto.ChangeOrderViews.ChangeOrderLineView;
import com.example.starter.damage.dto.ChangeOrderViews.ChangeOrderReviewView;
import com.example.starter.damage.dto.ChangeOrderViews.ChangeOrderView;
import com.example.starter.damage.dto.Views.ApprovalView;
import com.example.starter.damage.dto.Views.DamageItemView;
import com.example.starter.damage.store.ChangeOrderRepository;
import com.example.starter.damage.store.ChangeOrderRepository.ChangeOrderLineRow;
import com.example.starter.damage.store.ChangeOrderRepository.ChangeOrderReviewRow;
import com.example.starter.damage.store.ChangeOrderRepository.ChangeOrderRow;
import com.example.starter.damage.store.ChangeOrderRepository.WinnerLineRow;
import com.example.starter.damage.store.DamageCaseRepository;
import com.example.starter.damage.store.DamageCaseRepository.CaseRow;
import com.example.starter.damage.store.DamageFlowRepository;
import com.example.starter.damage.store.InquiryRepository;
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
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 修理变更单事务内服务。
 *
 * <p>核心规则：
 * <ul>
 *   <li>仅已批复（APPROVED）且尚未整案完工的修箱案可发起；绑定当前有效中标批复与中标供应商，
 *       冻结原批复、全清单与资格版本，候选初始待复核。</li>
 *   <li>1 至 10 个本案不同残损项可改工法与正整数分报价，其余项沿用原中标；
 *       跨案项、未知工法、供应商不具对应工法资格均拒绝。每案最多一个活动候选，候选期间原完工入口拒绝。</li>
 *   <li>两名不同复核人均 AGREE 才生效：最终事务重查供应商资格、当前批复与案状态，
 *       形成新估价与新批复版本（旧批复的完工/复检资格失效）；新费用按逐残损项全损规则判断，
 *       任一单项超箱值 30% 即全损，不得转修理批复。</li>
 *   <li>待复核候选可撤回或被驳回，解除完工阻断且保留原有效批复；生效后不可撤回。</li>
 *   <li>完工与候选建立、批准与资格撤销经案件行锁与供应商行锁串行，保证一致次序。</li>
 * </ul>
 */
@Service
public class ChangeOrderTxService {

    /** 复核决定 */
    private static final String DECISION_AGREE = "AGREE";
    private static final String DECISION_REJECT = "REJECT";

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

    // ============================ 创建候选 ============================

    /**
     * 创建修理变更单候选：绑定当前有效中标批复与中标供应商，冻结原批复、全清单与资格版本。
     * 仅 APPROVED（已批复未整案完工）案件可发起；每案最多一个活动候选。
     */
    @Transactional
    public ChangeOrderView create(long caseId, CreateChangeOrderRequest request) {
        // 与完工竞争：先锁案件行，完工/候选建立在案件行上串行，不能已完工又建候选
        CaseRow caseRow = caseRepository.findByIdForUpdate(caseId)
                .orElseThrow(() -> ApiException.notFound("残损案不存在: " + caseId));
        if (caseRow.status() != CaseStatus.APPROVED) {
            throw ApiException.unprocessable("仅已批复且尚未整案完工的修箱案可发起修理变更，当前状态: "
                    + caseRow.status());
        }
        ApprovalView active = flowRepository.findActiveApproval(caseId);
        if (active == null || !"INQUIRY".equals(active.source()) || active.openingId() == null) {
            throw ApiException.unprocessable("仅密封询价中标批复可发起修理变更，旧入口批复无中标供应商");
        }
        int maxSeq = changeOrderRepository.maxSeq(caseId);
        if (request.expectedVersion() != maxSeq) {
            throw ApiException.conflict("变更单序号已变化，期望序号 " + request.expectedVersion()
                    + " 与当前序号数 " + maxSeq + " 不一致");
        }
        if (changeOrderRepository.hasPendingForCase(caseId)) {
            throw ApiException.unprocessable("本案已存在待复核的变更候选，每案最多一个活动候选");
        }

        OpeningRow opening = inquiryRepository.findOpeningByIdForUpdate(active.openingId())
                .orElseThrow(() -> ApiException.unprocessable("中标批复关联的开标记录不存在: " + active.openingId()));
        if (opening.winnerSupplierId() == null || opening.winnerBidVersionId() == null) {
            throw ApiException.unprocessable("开标记录无中标供应商，无法发起修理变更");
        }

        // 锁供应商行并冻结当前资格版本（与资格撤销在供应商行上串行）
        SupplierRow supplier = supplierRepository.findByIdForUpdate(opening.winnerSupplierId())
                .orElseThrow(() -> ApiException.unprocessable("中标供应商不存在: " + opening.winnerSupplierId()));
        QualRow qualification = supplierRepository.findLatestQualificationForUpdate(supplier.id())
                .orElseThrow(() -> ApiException.unprocessable("中标供应商无任何资格版本，无法冻结资格快照"));
        List<String> supplierMethods = supplierRepository.listMethods(supplier.id());

        // 原中标报价行：本案全清单的前后对照基线
        Map<Long, WinnerLineRow> winnerByItem = changeOrderRepository
                .listWinnerLines(opening.winnerBidVersionId()).stream()
                .collect(Collectors.toMap(WinnerLineRow::damageItemId, Function.identity(),
                        (a, b) -> a, LinkedHashMap::new));
        Map<Long, DamageItemView> caseItems = flowRepository.listItems(caseId).stream()
                .collect(Collectors.toMap(DamageItemView::id, Function.identity(),
                        (a, b) -> a, LinkedHashMap::new));
        if (winnerByItem.size() != caseItems.size() || !winnerByItem.keySet().equals(caseItems.keySet())) {
            throw ApiException.unprocessable("原中标清单与案件残损项不一致，无法发起修理变更");
        }

        // 变更行整版校验：仅本案已有项、工法已知且供应商具备对应工法资格
        Map<Long, ChangeLineInput> changeByItem = new LinkedHashMap<>();
        for (ChangeLineInput line : request.lines()) {
            if (!caseItems.containsKey(line.damageItemId())) {
                throw ApiException.badRequest("变更残损项不属于该案件或不存在: " + line.damageItemId());
            }
            if (changeByItem.put(line.damageItemId(), line) != null) {
                throw ApiException.badRequest("变更残损项不得重复: " + line.damageItemId());
            }
            if (!supplierRepository.methodExists(line.methodCode())) {
                throw ApiException.badRequest("未知修箱工法代码: " + line.methodCode());
            }
            if (!supplierMethods.contains(line.methodCode())) {
                throw ApiException.unprocessable("中标供应商不具备工法 " + line.methodCode() + " 的对应资格，拒绝变更");
            }
            if (line.amountCents() <= 0) {
                throw ApiException.badRequest("变更报价必须为正整数分");
            }
        }

        long oldTotal = 0;
        long newTotal = 0;
        List<ChangeOrderLineRow> lines = new ArrayList<>();
        for (Map.Entry<Long, WinnerLineRow> entry : winnerByItem.entrySet()) {
            WinnerLineRow won = entry.getValue();
            ChangeLineInput change = changeByItem.get(entry.getKey());
            boolean changed = change != null;
            String newMethod = changed ? change.methodCode() : won.methodCode();
            long newAmount = changed ? change.amountCents() : won.amountCents();
            oldTotal += won.amountCents();
            newTotal += newAmount;
            lines.add(new ChangeOrderLineRow(0, 0, entry.getKey(), changed,
                    won.methodCode(), won.amountCents(), newMethod, newAmount));
        }

        long orderId;
        try {
            orderId = changeOrderRepository.insertChangeOrder(caseId, maxSeq + 1, active.id(), active.estimateId(),
                    opening.id(), supplier.id(), qualification.version(), oldTotal, newTotal,
                    request.requestId(), now());
        } catch (DuplicateKeyException e) {
            // 并发活动候选由 pending_key 唯一索引仲裁
            throw ApiException.unprocessable("本案已存在待复核的变更候选，每案最多一个活动候选");
        }
        for (ChangeOrderLineRow line : lines) {
            changeOrderRepository.insertLine(orderId, line.damageItemId(), line.changed(),
                    line.oldMethodCode(), line.oldAmountCents(), line.newMethodCode(), line.newAmountCents());
        }

        ChangeOrderView view = toView(requireOrder(orderId));
        idempotency.completeInTransaction(request.requestId(), view);
        return view;
    }

    // ============================ 复核与生效 ============================

    /**
     * 复核变更单候选：两名不同复核人均 AGREE 才生效换版；任一 REJECT 即驳回。
     * 生效为最终事务：重查供应商资格、当前批复与案状态，再形成新估价及批复版本。
     */
    @Transactional
    public ChangeOrderView review(long changeOrderId, ReviewChangeOrderRequest request) {
        ChangeOrderRow order = changeOrderRepository.findByIdForUpdate(changeOrderId)
                .orElseThrow(() -> ApiException.notFound("修理变更单不存在: " + changeOrderId));
        if (order.status() != ChangeOrderStatus.PENDING) {
            throw ApiException.unprocessable("变更单当前状态不允许复核: " + order.status());
        }
        requireVersion(order, request.expectedVersion());
        String decision = request.decision();
        if (!DECISION_AGREE.equals(decision) && !DECISION_REJECT.equals(decision)) {
            throw ApiException.badRequest("复核决定仅允许 AGREE 或 REJECT: " + decision);
        }
        List<ChangeOrderReviewRow> reviews = changeOrderRepository.listReviews(changeOrderId);
        if (reviews.stream().anyMatch(r -> r.reviewer().equals(request.reviewer()))) {
            throw ApiException.unprocessable("同一复核人不得重复复核: " + request.reviewer());
        }
        try {
            changeOrderRepository.insertReview(changeOrderId, request.reviewer(), decision,
                    request.requestId(), now());
        } catch (DuplicateKeyException e) {
            throw ApiException.unprocessable("同一复核人不得重复复核: " + request.reviewer());
        }

        if (DECISION_REJECT.equals(decision)) {
            // 驳回：解除完工阻断，保留原有效批复
            transition(order, ChangeOrderStatus.REJECTED);
            ChangeOrderView view = toView(requireOrder(changeOrderId));
            idempotency.completeInTransaction(request.requestId(), view);
            return view;
        }

        long agrees = changeOrderRepository.listReviews(changeOrderId).stream()
                .filter(r -> DECISION_AGREE.equals(r.decision()))
                .map(ChangeOrderReviewRow::reviewer).distinct().count();
        if (agrees >= 2) {
            activate(order, request.requestId());
        }
        ChangeOrderView view = toView(requireOrder(changeOrderId));
        idempotency.completeInTransaction(request.requestId(), view);
        return view;
    }

    /**
     * 生效换版（最终事务）：重查案状态、当前批复与供应商最新资格；
     * 新费用按逐残损项全损规则判断，构成全损则拒绝转修理批复。
     */
    private void activate(ChangeOrderRow order, String requestId) {
        CaseRow caseRow = caseRepository.findByIdForUpdate(order.caseId())
                .orElseThrow(() -> ApiException.notFound("残损案不存在: " + order.caseId()));
        if (caseRow.status() != CaseStatus.APPROVED) {
            throw ApiException.unprocessable("案件已不满足变更生效条件（状态 " + caseRow.status()
                    + "），不能已完工又改批复");
        }
        ApprovalView active = flowRepository.findActiveApproval(order.caseId());
        if (active == null || active.id() != order.approvalId()) {
            throw ApiException.unprocessable("当前有效批复已变化，与变更单冻结批复不一致，拒绝生效");
        }
        // 与资格撤销竞争：锁供应商行后重查最新资格，撤销/生效在供应商行上串行
        SupplierRow supplier = supplierRepository.findByIdForUpdate(order.supplierId())
                .orElseThrow(() -> ApiException.unprocessable("中标供应商不存在: " + order.supplierId()));
        QualRow latest = supplierRepository.findLatestQualificationForUpdate(supplier.id())
                .orElseThrow(() -> ApiException.unprocessable("中标供应商无任何资格版本，拒绝生效"));
        if (latest.status() != QualificationStatus.ACTIVE || !covers(latest, now())) {
            throw ApiException.unprocessable("中标供应商资格已失效（最新版本 " + latest.version()
                    + " 状态 " + latest.status() + "），拒绝生效");
        }

        // 逐残损项全损规则：任一单项新费用超箱值 30% 即全损（恰 30% 仍为修理），不看全案合计
        BigDecimal threshold = caseRow.containerValue().multiply(DamageCaseTxService.TOTAL_LOSS_THRESHOLD);
        List<ChangeOrderLineRow> lines = changeOrderRepository.listLines(order.id());
        for (ChangeOrderLineRow line : lines) {
            BigDecimal itemCost = BigDecimal.valueOf(line.newAmountCents(), 2);
            if (itemCost.compareTo(threshold) > 0) {
                throw ApiException.unprocessable("变更后残损项 " + line.damageItemId()
                        + " 费用超箱值 30% 构成全损，不得转修理批复");
            }
        }

        LocalDateTime now = LocalDateTime.now(clock);
        BigDecimal newTotal = BigDecimal.valueOf(order.newTotalCents(), 2);
        long newEstimateId = flowRepository.insertEstimate(order.caseId(), newTotal,
                Determination.REPAIR, requestId, now);
        long newApprovalId = flowRepository.insertApproval(order.caseId(), newEstimateId,
                LocalDate.now(clock), "CHANGE", order.openingId(), order.id(), now);
        int updated = changeOrderRepository.markEffective(order.id(), order.version(),
                newEstimateId, newApprovalId, now());
        if (updated == 0) {
            throw ApiException.conflict("变更生效并发冲突，变更单版本已变化，请重试");
        }
    }

    // ============================ 撤回 ============================

    /** 撤回待复核候选：解除完工阻断，保留原有效批复；生效后不可撤回 */
    @Transactional
    public ChangeOrderView withdraw(long changeOrderId, WithdrawChangeOrderRequest request) {
        ChangeOrderRow order = changeOrderRepository.findByIdForUpdate(changeOrderId)
                .orElseThrow(() -> ApiException.notFound("修理变更单不存在: " + changeOrderId));
        if (order.status() != ChangeOrderStatus.PENDING) {
            throw ApiException.unprocessable("变更单当前状态不允许撤回: " + order.status() + "，生效后不可撤回");
        }
        requireVersion(order, request.expectedVersion());
        transition(order, ChangeOrderStatus.WITHDRAWN);
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
    public List<ChangeOrderView> listForCase(long caseId) {
        caseRepository.findById(caseId)
                .orElseThrow(() -> ApiException.notFound("残损案不存在: " + caseId));
        return changeOrderRepository.listByCase(caseId).stream().map(this::toView).toList();
    }

    // ============================ 内部 ============================

    private void requireVersion(ChangeOrderRow order, int expectedVersion) {
        if (expectedVersion != order.version()) {
            throw ApiException.conflict("变更单版本已变化，期望版本 " + expectedVersion
                    + " 与当前版本 " + order.version() + " 不一致");
        }
    }

    private void transition(ChangeOrderRow order, ChangeOrderStatus target) {
        int updated = changeOrderRepository.compareAndSetStatus(order.id(), order.version(), target, now());
        if (updated == 0) {
            throw ApiException.conflict("变更单并发修改冲突，版本已变化，请重试");
        }
    }

    /** 资格版本是否覆盖指定时刻：ACTIVE 且 validFrom &lt;= t &lt; validTo（validTo 为空表示长期有效） */
    private boolean covers(QualRow qual, Instant t) {
        if (t.isBefore(qual.validFrom())) {
            return false;
        }
        return qual.validTo() == null || t.isBefore(qual.validTo());
    }

    private ChangeOrderRow requireOrder(long changeOrderId) {
        return changeOrderRepository.findById(changeOrderId)
                .orElseThrow(() -> ApiException.notFound("修理变更单不存在: " + changeOrderId));
    }

    private ChangeOrderView toView(ChangeOrderRow order) {
        Map<Long, DamageItemView> caseItems = flowRepository.listItems(order.caseId()).stream()
                .collect(Collectors.toMap(DamageItemView::id, Function.identity()));
        List<ChangeOrderLineView> lines = changeOrderRepository.listLines(order.id()).stream()
                .map(line -> {
                    DamageItemView item = caseItems.get(line.damageItemId());
                    return new ChangeOrderLineView(line.damageItemId(),
                            item == null ? null : item.iiclCode(),
                            item == null ? null : item.component(),
                            line.changed(), line.oldMethodCode(), line.oldAmountCents(),
                            line.newMethodCode(), line.newAmountCents());
                })
                .toList();
        List<ChangeOrderReviewView> reviews = changeOrderRepository.listReviews(order.id()).stream()
                .map(r -> new ChangeOrderReviewView(r.reviewer(), r.decision(), r.createdAt()))
                .toList();
        String supplierNo = supplierRepository.findById(order.supplierId())
                .map(SupplierRow::supplierNo).orElse(null);
        return new ChangeOrderView(order.id(), order.caseId(), order.seq(), order.version(), order.status(),
                order.approvalId(), order.estimateId(), order.openingId(), order.supplierId(), supplierNo,
                order.qualificationVersion(), order.oldTotalCents(), order.newTotalCents(),
                order.newEstimateId(), order.newApprovalId(), lines, reviews,
                order.createdAt(), order.updatedAt());
    }
}
