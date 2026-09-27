package com.example.starter.metro;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

import com.example.starter.metro.MetroRepository.BatchRow;
import com.example.starter.metro.MetroRepository.DetailRow;
import com.example.starter.metro.MetroRepository.IdemRow;
import com.example.starter.metro.MetroRepository.TxnRow;
import com.example.starter.metro.RefundRepository.AdjustmentBatchRow;
import com.example.starter.metro.RefundRepository.AdjustmentDetailRow;
import com.example.starter.metro.RefundRepository.AdjustmentRow;
import com.example.starter.metro.RefundRepository.BatchDetailRow;
import com.example.starter.metro.RefundRepository.CardRow;
import com.example.starter.metro.RefundRepository.LineShareRow;
import com.example.starter.metro.RefundRepository.RefundOrderRow;
import com.example.starter.metro.RefundRepository.ReviewRow;
import com.example.starter.metro.dto.AdjustmentBatchOpenRequest;
import com.example.starter.metro.dto.AdjustmentBatchOpenResponse;
import com.example.starter.metro.dto.AdjustmentBatchView;
import com.example.starter.metro.dto.AdjustmentIncludeRequest;
import com.example.starter.metro.dto.AdjustmentIncludeResponse;
import com.example.starter.metro.dto.AdjustmentSealRequest;
import com.example.starter.metro.dto.AdjustmentSealResponse;
import com.example.starter.metro.dto.CardCloseRequest;
import com.example.starter.metro.dto.CardCloseResponse;
import com.example.starter.metro.dto.RefundApplyRequest;
import com.example.starter.metro.dto.RefundApplyResponse;
import com.example.starter.metro.dto.RefundBalanceResponse;
import com.example.starter.metro.dto.RefundConfirmRequest;
import com.example.starter.metro.dto.RefundConfirmResponse;
import com.example.starter.metro.dto.ReplayableResponse;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 封账后冲正（退款）业务服务：退款申请、双人不同复核人确认、
 * 按原线路实收份额分摊并占用可退余额、独立负向调整单纳入调账批次、
 * 批次封存与逐线路余额查询。原流水、原清分明细与封账合计永不改写。
 *
 * <p>写操作以 requestId 幂等：指纹为结构化 JSON（数组、值带类型，分隔符会被
 * 转义），覆盖操作域与全部业务参数（含 expectedVersion、精确时刻）；
 * 同键同参重放首次冻结结果，同键异参 409，业务失败回滚不占键。
 */
@Service
public class RefundService {

    /** 幂等记录操作域。 */
    static final String SCOPE_APPLY = "REFUND_APPLY";
    static final String SCOPE_CONFIRM = "REFUND_CONFIRM";
    static final String SCOPE_CARD_CLOSE = "CARD_CLOSE";
    static final String SCOPE_BATCH_OPEN = "ADJ_BATCH_OPEN";
    static final String SCOPE_INCLUDE = "ADJ_INCLUDE";
    static final String SCOPE_BATCH_SEAL = "ADJ_BATCH_SEAL";

    private final RefundRepository refundRepository;
    private final MetroRepository metroRepository;
    private final TransactionTemplate txTemplate;
    private final ObjectMapper objectMapper;

    public RefundService(RefundRepository refundRepository, MetroRepository metroRepository,
                         PlatformTransactionManager transactionManager, ObjectMapper objectMapper) {
        this.refundRepository = refundRepository;
        this.metroRepository = metroRepository;
        this.txTemplate = new TransactionTemplate(transactionManager);
        this.objectMapper = objectMapper;
    }

    /** 申请退款：校验行程已封账且已按实结算，快照逐线路原份额。 */
    public RefundApplyResponse apply(RefundApplyRequest request) {
        String fingerprint = fingerprint(SCOPE_APPLY, List.of(request.requestId(), request.refundNo(),
                request.txnNo(), request.amountCents(), request.applyTime().toString()));
        return idempotent(SCOPE_APPLY, request.requestId(), fingerprint, RefundApplyResponse.class,
                () -> doApply(request));
    }

    /** 双人复核确认：两名不同复核人，最终确认事务内共同校验、分摊占用并生成负向调整单。 */
    public RefundConfirmResponse confirm(RefundConfirmRequest request) {
        String fingerprint = fingerprint(SCOPE_CONFIRM, List.of(request.requestId(), request.refundNo(),
                request.reviewer(), request.reviewTime().toString(), request.expectedVersion()));
        return idempotent(SCOPE_CONFIRM, request.requestId(), fingerprint, RefundConfirmResponse.class,
                () -> doConfirm(request));
    }

    /** 开放调账批次。 */
    public AdjustmentBatchOpenResponse openBatch(AdjustmentBatchOpenRequest request) {
        String fingerprint = fingerprint(SCOPE_BATCH_OPEN,
                List.of(request.requestId(), request.batchNo(), request.openTime().toString()));
        return idempotent(SCOPE_BATCH_OPEN, request.requestId(), fingerprint,
                AdjustmentBatchOpenResponse.class, () -> doOpenBatch(request));
    }

    /** 将调整单纳入唯一开放调账批次；重复纳入不重复计额，封存后拒绝。 */
    public AdjustmentIncludeResponse include(AdjustmentIncludeRequest request) {
        String fingerprint = fingerprint(SCOPE_INCLUDE, List.of(request.requestId(),
                request.adjustmentNo(), request.batchNo(), request.includeTime().toString(),
                request.expectedVersion()));
        return idempotent(SCOPE_INCLUDE, request.requestId(), fingerprint,
                AdjustmentIncludeResponse.class, () -> doInclude(request));
    }

    /** 封存调账批次（终态，只读）。 */
    public AdjustmentSealResponse sealBatch(AdjustmentSealRequest request) {
        String fingerprint = fingerprint(SCOPE_BATCH_SEAL, List.of(request.requestId(),
                request.batchNo(), request.sealTime().toString(), request.expectedVersion()));
        return idempotent(SCOPE_BATCH_SEAL, request.requestId(), fingerprint,
                AdjustmentSealResponse.class, () -> doSealBatch(request));
    }

    /** 查询调账批次视图。 */
    public AdjustmentBatchView batchView(String batchNo) {
        AdjustmentBatchRow batch = refundRepository.findAdjustmentBatchByNo(batchNo)
                .orElseThrow(() -> MetroException.notFound("ADJ_BATCH_NOT_FOUND",
                        "调账批次不存在: " + batchNo));
        List<BatchDetailRow> rows = refundRepository.findBatchDetails(batch.id());
        boolean sealed = "SEALED".equals(batch.status());
        long total = sealed ? batch.totalCents() : rows.stream().mapToLong(BatchDetailRow::amountCents).sum();
        int count = sealed ? batch.adjustmentCount()
                : refundRepository.countAdjustmentsInBatch(batch.id());
        List<AdjustmentBatchView.LineDetail> details = new ArrayList<>();
        for (BatchDetailRow row : rows) {
            details.add(new AdjustmentBatchView.LineDetail(row.adjustmentNo(), row.txnNo(),
                    row.lineCode(), row.amountCents()));
        }
        return new AdjustmentBatchView(batch.batchNo(), batch.status(), total, count, batch.version(),
                details);
    }

    /** 查询行程逐线路可退余额。 */
    public RefundBalanceResponse balance(String txnNo) {
        TxnRow txn = metroRepository.findTxnByNo(txnNo)
                .orElseThrow(() -> MetroException.notFound("TXN_NOT_FOUND", "交易流水不存在: " + txnNo));
        List<LineShareRow> shares = refundRepository.findSharesByTxn(txnNo);
        long originalTotal;
        List<RefundBalanceResponse.LineBalance> lines = new ArrayList<>();
        if (shares.isEmpty()) {
            // 尚无退款申请：由已封账实结明细实时推导原份额，已退为 0
            Map<String, Long> originals = settledLineShares(txn);
            shares = originals.entrySet().stream()
                    .map(e -> new LineShareRow(0L, txnNo, e.getKey(), e.getValue(), 0L))
                    .toList();
        }
        long refundedTotal = 0L;
        for (LineShareRow share : shares) {
            long available = share.originalShareCents() - share.refundedCents();
            refundedTotal += share.refundedCents();
            lines.add(new RefundBalanceResponse.LineBalance(share.lineCode(),
                    share.originalShareCents(), share.refundedCents(), available));
        }
        originalTotal = shares.stream().mapToLong(LineShareRow::originalShareCents).sum();
        return new RefundBalanceResponse(txnNo, originalTotal, refundedTotal,
                originalTotal - refundedTotal, lines);
    }

    /** 关闭原票卡：不影响已成功退款的历史重放。 */
    public CardCloseResponse closeCard(CardCloseRequest request) {
        String fingerprint = fingerprint(SCOPE_CARD_CLOSE, List.of(request.requestId(), request.cardId(),
                request.closeTime().toString(), request.expectedVersion()));
        return idempotent(SCOPE_CARD_CLOSE, request.requestId(), fingerprint, CardCloseResponse.class,
                () -> doCloseCard(request));
    }

    // ---- 申请 ----

    private RefundApplyResponse doApply(RefundApplyRequest request) {
        TxnRow txn = metroRepository.findTxnByNoForUpdate(request.txnNo())
                .orElseThrow(() -> MetroException.notFound("TXN_NOT_FOUND",
                        "交易流水不存在: " + request.txnNo()));
        requireSealedSettled(txn);
        Map<String, Long> originals = settledLineShares(txn);
        long originalTotal = originals.values().stream().mapToLong(Long::longValue).sum();

        // 锁定该行程全部线路余额行（与最终确认互斥），缺失则以封账实结明细初始化快照
        List<LineShareRow> shares = ensureLineShares(txn.txnNo(), originals);
        long refundedTotal = shares.stream().mapToLong(LineShareRow::refundedCents).sum();
        long available = originalTotal - refundedTotal;
        if (request.amountCents() > available) {
            throw MetroException.unprocessable("REFUND_EXCEEDS_AVAILABLE",
                    "退款金额超过行程可退余额：申请 " + request.amountCents() + " 分，可退 " + available + " 分");
        }

        refundRepository.insertCardIfAbsent(txn.cardId(), LocalDateTime.now());        try {
            refundRepository.insertRefundOrder(new RefundOrderRow(0L, request.refundNo(),
                    request.txnNo(), request.amountCents(), "PENDING", 1, null, null, null, null,
                    request.requestId(), request.applyTime()));
        } catch (DuplicateKeyException ex) {
            throw MetroException.conflict("DUPLICATE_REFUND_NO", "退款单号重复: " + request.refundNo());
        }
        return new RefundApplyResponse(request.refundNo(), request.txnNo(), request.amountCents(),
                "PENDING", 1, false);
    }

    // ---- 双人确认 ----

    private RefundConfirmResponse doConfirm(RefundConfirmRequest request) {
        RefundOrderRow refund = refundRepository.findRefundByNoForUpdate(request.refundNo())
                .orElseThrow(() -> MetroException.notFound("REFUND_NOT_FOUND",
                        "退款单不存在: " + request.refundNo()));
        if ("CONFIRMED".equals(refund.status())) {
            if (request.expectedVersion() != refund.version()) {
                throw versionConflict(request.expectedVersion(), refund.version());
            }
            throw MetroException.unprocessable("REFUND_ALREADY_CONFIRMED",
                    "退款单已完成双人确认: " + request.refundNo());
        }

        List<ReviewRow> reviews = refundRepository.findReviewsForUpdate(request.refundNo());
        if ("PENDING".equals(refund.status())) {
            if (request.expectedVersion() != 1) {
                throw versionConflict(request.expectedVersion(), refund.version());
            }
            // 首人复核：登记后单人不生效，等待不同的第二人
            if (reviews.stream().anyMatch(r -> r.reviewer().equals(request.reviewer()))) {
                throw MetroException.unprocessable("REVIEWER_DUPLICATE",
                        "同一复核人不能重复复核: " + request.reviewer());
            }
            insertReviewOrConflict(request);
            if (!refundRepository.markFirstReview(request.refundNo(), request.reviewer(),
                    request.reviewTime())) {
                throw new IllegalStateException("首人复核状态变更失败: " + request.refundNo());
            }
            return new RefundConfirmResponse(request.refundNo(), null, refund.txnNo(),
                    refund.amountCents(), "IN_REVIEW", 2, request.reviewer(), null, false,
                    List.of(), false);
        }

        // IN_REVIEW：最终确认。版本必须为 2，且两名复核人在本事务内共同校验为不同两人
        if (request.expectedVersion() != 2) {
            throw versionConflict(request.expectedVersion(), refund.version());
        }
        if (refund.firstReviewer() != null && refund.firstReviewer().equals(request.reviewer())) {
            throw MetroException.unprocessable("REVIEWER_MUST_DIFFER",
                    "最终确认人必须与首人复核不同，单人确认不生效");
        }
        insertReviewOrConflict(request);
        List<ReviewRow> committedReviews = refundRepository.findReviewsForUpdate(request.refundNo());
        long distinctReviewers = committedReviews.stream().map(ReviewRow::reviewer).distinct().count();
        if (committedReviews.size() < 2 || distinctReviewers < 2) {
            throw MetroException.unprocessable("REVIEWERS_INSUFFICIENT",
                    "最终确认须两名不同复核人共同校验");
        }

        // 分摊并逐线路占用可退余额（行已锁，条件 UPDATE 兜底，并发退款不得超退）
        List<LineShareRow> shares = refundRepository.findSharesByTxnForUpdate(refund.txnNo());
        Map<String, Long> allocation = allocateRefund(shares, refund.amountCents());
        for (Map.Entry<String, Long> entry : allocation.entrySet()) {
            int updated = refundRepository.occupyLineShare(refund.txnNo(), entry.getKey(),
                    entry.getValue());
            if (updated != 1) {
                throw MetroException.unprocessable("REFUND_EXCEEDS_AVAILABLE",
                        "线路 " + entry.getKey() + " 可退余额不足，并发退款拒绝超退");
            }
        }

        if (!refundRepository.markConfirmed(request.refundNo(), request.reviewer(),
                request.reviewTime())) {
            throw new IllegalStateException("最终确认状态变更失败: " + request.refundNo());
        }
        String adjustmentNo = "ADJ-" + request.refundNo();
        long adjustmentId;
        try {
            adjustmentId = refundRepository.insertAdjustment(adjustmentNo, request.refundNo(),
                    refund.txnNo(), refund.amountCents(), request.requestId());
        } catch (DuplicateKeyException ex) {
            throw new IllegalStateException("调整单重复生成: " + adjustmentNo, ex);
        }
        List<RefundConfirmResponse.LineAllocation> allocations = new ArrayList<>();
        for (LineShareRow share : shares) {
            long lineRefund = allocation.getOrDefault(share.lineCode(), 0L);
            if (lineRefund <= 0) {
                continue;
            }
            refundRepository.insertAdjustmentDetail(adjustmentId, share.lineCode(), -lineRefund);
            allocations.add(new RefundConfirmResponse.LineAllocation(share.lineCode(), lineRefund));
        }
        long allocatedTotal = allocations.stream()
                .mapToLong(RefundConfirmResponse.LineAllocation::refundCents).sum();
        if (allocatedTotal != refund.amountCents()) {
            throw new IllegalStateException("退款分摊合计不等于退款金额");
        }
        return new RefundConfirmResponse(request.refundNo(), adjustmentNo, refund.txnNo(),
                refund.amountCents(), "CONFIRMED", 3, refund.firstReviewer(), request.reviewer(),
                true, allocations, false);
    }

    /**
     * 按每条原线路实收份额分摊本次退款：各线路先按原份额比例向下取整并限制到
     * 可退余额，剩余分逐一分给尚有可退余额的线路，按原份额降序、线路编号升序
     * 循环；合计等于退款金额，且各线路累计不超原份额。
     */
    static Map<String, Long> allocateRefund(List<LineShareRow> shares, long refundCents) {
        long totalOriginal = shares.stream().mapToLong(LineShareRow::originalShareCents).sum();
        long totalAvailable = shares.stream()
                .mapToLong(s -> s.originalShareCents() - s.refundedCents()).sum();
        if (totalOriginal <= 0 || refundCents > totalAvailable) {
            throw MetroException.unprocessable("REFUND_EXCEEDS_AVAILABLE",
                    "可退余额不足：退款 " + refundCents + " 分，可退 " + totalAvailable + " 分");
        }
        Map<String, Long> allocated = new LinkedHashMap<>();
        long assigned = 0L;
        for (LineShareRow share : shares) {
            long available = share.originalShareCents() - share.refundedCents();
            long floor = refundCents * share.originalShareCents() / totalOriginal;
            long value = Math.min(available, floor);
            allocated.put(share.lineCode(), value);
            assigned += value;
        }
        long remainder = refundCents - assigned;
        List<String> cyclicOrder = shares.stream()
                .sorted(Comparator.comparingLong(LineShareRow::originalShareCents).reversed()
                        .thenComparing(LineShareRow::lineCode))
                .map(LineShareRow::lineCode)
                .toList();
        int cursor = 0;
        int guard = 0;
        while (remainder > 0) {
            if (++guard > cyclicOrder.size() * (refundCents + 1)) {
                throw new IllegalStateException("退款尾差分摊无法收敛");
            }
            String line = cyclicOrder.get(cursor % cyclicOrder.size());
            cursor++;
            LineShareRow share = shares.stream().filter(s -> s.lineCode().equals(line)).findFirst()
                    .orElseThrow();
            long available = share.originalShareCents() - share.refundedCents()
                    - allocated.get(line);
            if (available > 0) {
                allocated.merge(line, 1L, Long::sum);
                remainder--;
            }
        }
        return allocated;
    }

    // ---- 调账批次 ----

    private AdjustmentBatchOpenResponse doOpenBatch(AdjustmentBatchOpenRequest request) {
        if (refundRepository.findAdjustmentBatchByNo(request.batchNo()).isPresent()) {
            throw MetroException.conflict("DUPLICATE_ADJ_BATCH_NO",
                    "调账批次号重复: " + request.batchNo());
        }
        try {
            refundRepository.insertAdjustmentBatch(request.batchNo(), request.requestId(),
                    request.openTime());
        } catch (DuplicateKeyException ex) {
            throw MetroException.conflict("DUPLICATE_ADJ_BATCH_NO",
                    "调账批次号重复: " + request.batchNo());
        }
        return new AdjustmentBatchOpenResponse(request.batchNo(), "OPEN", 1, false);
    }

    private AdjustmentIncludeResponse doInclude(AdjustmentIncludeRequest request) {
        AdjustmentRow adjustment = refundRepository.findAdjustmentByNoForUpdate(request.adjustmentNo())
                .orElseThrow(() -> MetroException.notFound("ADJUSTMENT_NOT_FOUND",
                        "调整单不存在: " + request.adjustmentNo()));
        if (adjustment.batchId() != null) {
            AdjustmentBatchRow existing = refundRepository.findAdjustmentBatchById(adjustment.batchId())
                    .orElseThrow(() -> new IllegalStateException("调整单所属批次缺失"));
            if (!existing.batchNo().equals(request.batchNo())) {
                throw MetroException.unprocessable("ADJUSTMENT_ALREADY_IN_BATCH",
                        "调整单只能纳入一个批次，已纳入: " + existing.batchNo());
            }
            if ("SEALED".equals(existing.status())) {
                throw MetroException.unprocessable("ADJ_BATCH_SEALED",
                        "调账批次已封存，只读不可纳入: " + existing.batchNo());
            }
            // 重复纳入同一开放批次：幂等成功，不重复计额
            return new AdjustmentIncludeResponse(request.adjustmentNo(), request.batchNo(),
                    existing.status(), existing.version(), true, false);
        }
        AdjustmentBatchRow batch = refundRepository.findAdjustmentBatchByNoForUpdate(request.batchNo())
                .orElseThrow(() -> MetroException.notFound("ADJ_BATCH_NOT_FOUND",
                        "调账批次不存在: " + request.batchNo()));
        if ("SEALED".equals(batch.status())) {
            throw MetroException.unprocessable("ADJ_BATCH_SEALED",
                    "调账批次已封存，只读不可纳入: " + request.batchNo());
        }
        if (request.expectedVersion() != batch.version()) {
            throw versionConflict(request.expectedVersion(), batch.version());
        }
        int assigned = refundRepository.assignAdjustmentBatch(adjustment.id(), batch.id());
        if (assigned != 1) {
            throw new IllegalStateException("调整单纳入批次状态变更失败");
        }
        List<AdjustmentDetailRow> details = refundRepository.findAdjustmentDetails(adjustment.id());
        try {
            for (AdjustmentDetailRow detail : details) {
                refundRepository.insertBatchDetail(batch.id(), adjustment.id(), adjustment.txnNo(),
                        detail.lineCode(), detail.amountCents());
            }
        } catch (DuplicateKeyException ex) {
            // 唯一约束兜底：重复纳入不重复计额
            return new AdjustmentIncludeResponse(request.adjustmentNo(), request.batchNo(), "OPEN",
                    batch.version(), true, false);
        }
        if (!refundRepository.incrementBatchAfterInclude(batch.id(), batch.version())) {
            throw new IllegalStateException("批次版本变更失败");
        }
        return new AdjustmentIncludeResponse(request.adjustmentNo(), request.batchNo(), "OPEN",
                batch.version() + 1, false, false);
    }

    private AdjustmentSealResponse doSealBatch(AdjustmentSealRequest request) {
        AdjustmentBatchRow batch = refundRepository.findAdjustmentBatchByNoForUpdate(request.batchNo())
                .orElseThrow(() -> MetroException.notFound("ADJ_BATCH_NOT_FOUND",
                        "调账批次不存在: " + request.batchNo()));
        if ("SEALED".equals(batch.status())) {
            // 封存为终态：重复封存幂等成功，只读快照不变
            return new AdjustmentSealResponse(batch.batchNo(), "SEALED", batch.totalCents(),
                    batch.adjustmentCount(), batch.version(), true);
        }
        if (request.expectedVersion() != batch.version()) {
            throw versionConflict(request.expectedVersion(), batch.version());
        }
        long total = refundRepository.findBatchDetails(batch.id()).stream()
                .mapToLong(BatchDetailRow::amountCents).sum();
        int count = refundRepository.countAdjustmentsInBatch(batch.id());
        if (!refundRepository.sealAdjustmentBatch(batch.id(), total, count, request.requestId(),
                request.sealTime(), batch.version())) {
            // 并发封存：胜出方已置终态，重读后按终态幂等返回
            AdjustmentBatchRow latest = refundRepository.findAdjustmentBatchByNo(request.batchNo())
                    .orElseThrow(() -> new IllegalStateException("调账批次丢失: " + request.batchNo()));
            if ("SEALED".equals(latest.status())) {
                return new AdjustmentSealResponse(latest.batchNo(), "SEALED", latest.totalCents(),
                        latest.adjustmentCount(), latest.version(), true);
            }
            throw new IllegalStateException("调账批次封存失败: " + request.batchNo());
        }
        return new AdjustmentSealResponse(batch.batchNo(), "SEALED", total, count,
                batch.version() + 1, false);
    }

    // ---- 票卡 ----

    private CardCloseResponse doCloseCard(CardCloseRequest request) {
        CardRow card = refundRepository.findCardForUpdate(request.cardId())
                .orElseThrow(() -> MetroException.notFound("CARD_NOT_FOUND",
                        "票卡不存在: " + request.cardId()));
        if (request.expectedVersion() != card.version()) {
            throw versionConflict(request.expectedVersion(), card.version());
        }
        if ("CLOSED".equals(card.status())) {
            // 已关闭终态：版本匹配时幂等返回，不重复变更
            return new CardCloseResponse(request.cardId(), "CLOSED", card.version(), true);
        }
        if (!refundRepository.closeCard(request.cardId(), card.version(), request.closeTime())) {
            throw versionConflict(request.expectedVersion(), card.version());
        }
        return new CardCloseResponse(request.cardId(), "CLOSED", card.version() + 1, false);
    }

    // ---- 共享校验与工具 ----

    /** 校验行程已按实结算且所属原清分批次已封账；未出闸/暂记一律拒绝。 */
    private void requireSealedSettled(TxnRow txn) {
        if (!"COMPLETED".equals(txn.status()) || txn.batchId() == null || txn.fareCents() == null) {
            throw MetroException.unprocessable("REFUND_TXN_NOT_SETTLED",
                    "仅已出闸按实结算的行程可退款，不能处理未出闸暂记: " + txn.txnNo());
        }
        BatchRow batch = metroRepository.findBatchById(txn.batchId())
                .orElseThrow(() -> new IllegalStateException("行程所属清分批次缺失: " + txn.batchId()));
        if (!"SEALED".equals(batch.status())) {
            throw MetroException.unprocessable("REFUND_BATCH_NOT_SEALED",
                    "原清分批次尚未封账，不可退款: " + batch.bizDate());
        }
    }

    /**
     * 取行程在已封账批次中的按实结算逐线路实收份额：NORMAL（正常结算）与
     * SETTLE（暂记后补登实结）为正向原账；ESTIMATE/REVERSAL 不计入原实收。
     */
    private Map<String, Long> settledLineShares(TxnRow txn) {
        if (txn.batchId() == null) {
            throw MetroException.unprocessable("REFUND_TXN_NOT_SETTLED",
                    "行程未入账，不可退款: " + txn.txnNo());
        }
        Map<String, Long> shares = new LinkedHashMap<>();
        for (DetailRow detail : metroRepository.findDetailsByBatch(txn.batchId())) {
            if (!detail.txnNo().equals(txn.txnNo())) {
                continue;
            }
            if ("NORMAL".equals(detail.entryType()) || "SETTLE".equals(detail.entryType())) {
                shares.merge(detail.lineCode(), detail.amountCents(), Long::sum);
            }
        }
        if (shares.isEmpty() || shares.values().stream().anyMatch(v -> v <= 0)) {
            throw MetroException.unprocessable("REFUND_TXN_NOT_SETTLED",
                    "原清分明细中无按实结算正向份额: " + txn.txnNo());
        }
        return shares;
    }

    /** 确保行程逐线路份额快照存在（首次退款申请时按封账明细初始化），并加锁返回。 */
    private List<LineShareRow> ensureLineShares(String txnNo, Map<String, Long> originals) {
        List<LineShareRow> existing = refundRepository.findSharesByTxnForUpdate(txnNo);
        if (!existing.isEmpty()) {
            return existing;
        }
        originals.forEach((line, share) -> {
            try {
                refundRepository.insertLineShare(txnNo, line, share);
            } catch (DuplicateKeyException ex) {
                // 并发首次申请：唯一约束兜底，以已有快照为准
            }
        });
        return refundRepository.findSharesByTxnForUpdate(txnNo);
    }

    private static MetroException versionConflict(int expected, int actual) {
        return MetroException.conflict("VERSION_CONFLICT",
                "expectedVersion=" + expected + " 与当前版本 " + actual + " 不一致");
    }

    /** 插入复核登记；同退款单同复核人唯一约束冲突时转换为 422。 */
    private void insertReviewOrConflict(RefundConfirmRequest request) {
        try {
            refundRepository.insertReview(request.refundNo(), request.reviewer(),
                    request.reviewTime(), request.requestId());
        } catch (DuplicateKeyException ex) {
            throw MetroException.unprocessable("REVIEWER_DUPLICATE",
                    "同一复核人不能重复复核: " + request.reviewer());
        }
    }

    private <T extends ReplayableResponse<T>> T idempotent(String scope, String requestId,
                                                           String fingerprint, Class<T> type,
                                                           Supplier<T> action) {
        Optional<IdemRow> existing = metroRepository.findIdem(scope, requestId);
        if (existing.isPresent()) {
            return replay(existing.get(), fingerprint, type);
        }
        try {
            return txTemplate.execute(status -> {
                // 事务开头占位：并发同键在此阻塞或冲突，随后走重放路径；失败回滚不占键
                metroRepository.insertIdemPlaceholder(scope, requestId, fingerprint);
                T response = action.get();
                metroRepository.updateIdemResponse(scope, requestId, toJson(response));
                return response;
            });
        } catch (DuplicateKeyException ex) {
            return metroRepository.findIdem(scope, requestId)
                    .map(row -> replay(row, fingerprint, type))
                    .orElseThrow(() -> ex);
        }
    }

    private <T extends ReplayableResponse<T>> T replay(IdemRow row, String fingerprint, Class<T> type) {
        if (!row.fingerprint().equals(fingerprint)) {
            throw MetroException.conflict("IDEMPOTENCY_CONFLICT",
                    "相同 requestId 但业务参数不一致，拒绝重复写操作");
        }
        try {
            return objectMapper.readValue(row.responseBody(), type).asReplayed();
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("幂等响应反序列化失败", ex);
        }
    }

    private String toJson(Object response) {
        try {
            return objectMapper.writeValueAsString(response);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("幂等响应序列化失败", ex);
        }
    }

    /**
     * 结构化指纹：将操作域与全部参数序列化为定长 JSON 数组后取 SHA-256。
     * JSON 转义保证含分隔符的合法文本也无法伪造字段边界，数值保持数值类型。
     */
    private String fingerprint(String scope, List<Object> parts) {
        List<Object> structured = new ArrayList<>();
        structured.add(scope);
        structured.addAll(parts);
        String json;
        try {
            json = objectMapper.writeValueAsString(structured);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("指纹序列化失败", ex);
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(json.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 不可用", ex);
        }
    }
}
