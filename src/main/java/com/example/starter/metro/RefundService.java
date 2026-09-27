package com.example.starter.metro;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.example.starter.metro.MetroRepository.BatchRow;
import com.example.starter.metro.MetroRepository.TxnRow;
import com.example.starter.metro.RefundRepository.AdjustBatchRow;
import com.example.starter.metro.RefundRepository.AdjustDetailRow;
import com.example.starter.metro.RefundRepository.AdjustOrderRow;
import com.example.starter.metro.RefundRepository.LineAmountRow;
import com.example.starter.metro.RefundRepository.RefundRow;
import com.example.starter.metro.dto.AdjustSealRequest;
import com.example.starter.metro.dto.AdjustSealResponse;
import com.example.starter.metro.dto.RefundApplyRequest;
import com.example.starter.metro.dto.RefundApplyResponse;
import com.example.starter.metro.dto.RefundBalanceResponse;
import com.example.starter.metro.dto.RefundConfirmRequest;
import com.example.starter.metro.dto.RefundConfirmResponse;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

/**
 * 封账后退款冲正服务。
 *
 * <p>退款只引用已封账且已按实结算的行程与原清分明细，不处理未出闸暂记；
 * 原交易流水与封账合计永不改写，退款经两名不同复核人确认后生成独立负向调整明细。
 * 写操作以 requestId 幂等，结构化指纹覆盖 requestId、版本、集合与精确时刻，
 * 业务、版本与幂等结果在同一事务提交。
 */
@Service
public class RefundService {

    private final MetroRepository metroRepository;
    private final RefundRepository refundRepository;
    private final IdempotencyExecutor idempotency;

    public RefundService(MetroRepository metroRepository, RefundRepository refundRepository,
                         IdempotencyExecutor idempotency) {
        this.metroRepository = metroRepository;
        this.refundRepository = refundRepository;
        this.idempotency = idempotency;
    }

    /** 退款申请：生成 PENDING 退款单（版本 1），不占用可退余额、不产生调整。 */
    public RefundApplyResponse apply(RefundApplyRequest request) {
        String fingerprint = IdempotencyExecutor.fingerprint("REFUND_APPLY", request.requestId(),
                request.txnNo(), Long.toString(request.amountCents()), request.reason(),
                request.applicant());
        return idempotency.execute("REFUND_APPLY", request.requestId(), fingerprint,
                RefundApplyResponse.class, () -> doApply(request));
    }

    /**
     * 双人确认：两名不同复核人在同一最终确认事务内共同校验；确认成功同时占用
     * 行程可退余额并登记独立负向调整单到唯一开放调账批次。
     */
    public RefundConfirmResponse confirm(RefundConfirmRequest request) {
        String fingerprint = IdempotencyExecutor.fingerprint("REFUND_CONFIRM", request.requestId(),
                request.refundNo(), request.reviewer1(), request.reviewer2(),
                Integer.toString(request.expectedVersion()), request.confirmTime().toString());
        return idempotency.execute("REFUND_CONFIRM", request.requestId(), fingerprint,
                RefundConfirmResponse.class, () -> doConfirm(request));
    }

    /** 调账封存：开放批次快照后只读（终态），重复纳入不重复计额。 */
    public AdjustSealResponse sealAdjustBatch(AdjustSealRequest request) {
        String fingerprint = IdempotencyExecutor.fingerprint("ADJUST_SEAL", request.requestId(),
                Integer.toString(request.expectedVersion()), request.sealTime().toString());
        return idempotency.execute("ADJUST_SEAL", request.requestId(), fingerprint,
                AdjustSealResponse.class, () -> doSeal(request));
    }

    /** 逐线路退款余额查询：原实收份额、累计已退与尚可退余额。 */
    public RefundBalanceResponse balance(String txnNo) {
        TxnRow txn = metroRepository.findTxnByNo(txnNo)
                .orElseThrow(() -> MetroException.notFound("TXN_NOT_FOUND", "交易流水不存在: " + txnNo));
        if (!"COMPLETED".equals(txn.status())) {
            throw MetroException.unprocessable("TRIP_NOT_SETTLED",
                    "仅已按实结算行程可查询退款余额，当前状态: " + txn.status());
        }
        Map<String, long[]> shares = loadLineShares(txn.txnNo());
        long originalTotal = 0L;
        long refundedTotal = 0L;
        List<RefundBalanceResponse.LineBalance> lines = new ArrayList<>();
        for (Map.Entry<String, long[]> entry : sortedEntries(shares)) {
            long original = entry.getValue()[0];
            long refunded = entry.getValue()[1];
            originalTotal += original;
            refundedTotal += refunded;
            lines.add(new RefundBalanceResponse.LineBalance(entry.getKey(), original, refunded,
                    original - refunded));
        }
        return new RefundBalanceResponse(txn.txnNo(), originalTotal, refundedTotal,
                originalTotal - refundedTotal, lines);
    }

    private RefundApplyResponse doApply(RefundApplyRequest request) {
        TxnRow txn = metroRepository.findTxnByNoForUpdate(request.txnNo())
                .orElseThrow(() -> MetroException.notFound("TXN_NOT_FOUND",
                        "交易流水不存在: " + request.txnNo()));
        requireSealedSettledTrip(txn);
        long fareCents = requireOriginalSharesFound(txn.txnNo(), txn.fareCents());
        long refunded = sumRefunded(txn.txnNo());
        if (request.amountCents() + refunded > fareCents) {
            throw MetroException.unprocessable("REFUND_EXCEEDS_FARE",
                    "累计退款不得超过原实收: 本次 " + request.amountCents() + " 已退 " + refunded
                            + " 原实收 " + fareCents);
        }
        String refundNo = newBusinessNo("RF");
        RefundRow row = new RefundRow(null, refundNo, txn.txnNo(), request.amountCents(),
                request.reason(), request.applicant(), "PENDING", null, null, 1,
                request.requestId(), "");
        refundRepository.insertRefund(row);
        return new RefundApplyResponse(refundNo, txn.txnNo(), request.amountCents(), "PENDING", 1, false);
    }

    private RefundConfirmResponse doConfirm(RefundConfirmRequest request) {
        RefundRow refund = refundRepository.findRefundByNoForUpdate(request.refundNo())
                .orElseThrow(() -> MetroException.notFound("REFUND_NOT_FOUND",
                        "退款单不存在: " + request.refundNo()));
        if (refund.version() != request.expectedVersion()) {
            throw MetroException.conflict("VERSION_MISMATCH",
                    "退款单版本不匹配: 期望 " + request.expectedVersion() + " 实际 " + refund.version());
        }
        if ("CONFIRMED".equals(refund.status())) {
            throw MetroException.unprocessable("REFUND_ALREADY_CONFIRMED",
                    "退款单已双人确认，单人重复操作不生效: " + request.refundNo());
        }
        if (request.reviewer1().equals(request.reviewer2())) {
            throw MetroException.unprocessable("REVIEWERS_SAME",
                    "两名复核人必须不同: " + request.reviewer1());
        }
        // 锁定原交易行：串行化同一行程的并发退款，防止超退
        TxnRow txn = metroRepository.findTxnByNoForUpdate(refund.txnNo())
                .orElseThrow(() -> MetroException.notFound("TXN_NOT_FOUND",
                        "交易流水不存在: " + refund.txnNo()));
        requireSealedSettledTrip(txn);
        long fareCents = requireOriginalSharesFound(txn.txnNo(), txn.fareCents());

        Map<String, long[]> shares = loadLineShares(txn.txnNo());
        long remainingTotal = 0L;
        for (long[] v : shares.values()) {
            remainingTotal += v[0] - v[1];
        }
        if (refund.amountCents() > remainingTotal) {
            throw MetroException.unprocessable("REFUND_EXCEEDS_FARE",
                    "本次退款超过行程可退余额: 本次 " + refund.amountCents() + " 可退 " + remainingTotal);
        }
        List<LineAllocationCents> allocations = allocateRefund(shares, refund.amountCents());

        long batchId = getOrCreateOpenAdjustBatchForUpdate();
        String adjustNo = newBusinessNo("ADJ");
        long adjustmentId = refundRepository.insertAdjustOrder(new AdjustOrderRow(0L, adjustNo,
                refund.refundNo(), txn.txnNo(), batchId, -refund.amountCents()));
        List<RefundConfirmResponse.LineAllocation> allocationViews = new ArrayList<>();
        for (LineAllocationCents allocation : allocations) {
            refundRepository.insertAdjustDetail(new AdjustDetailRow(null, adjustmentId, txn.txnNo(),
                    allocation.lineCode(), -allocation.amountCents()));
            allocationViews.add(new RefundConfirmResponse.LineAllocation(allocation.lineCode(),
                    -allocation.amountCents()));
        }
        refundRepository.confirmRefund(refund.id(), request.reviewer1(), request.reviewer2(),
                refund.version() + 1);
        return new RefundConfirmResponse(refund.refundNo(), "CONFIRMED", adjustNo, batchId,
                refund.amountCents(), allocationViews, refund.version() + 1, false);
    }

    private AdjustSealResponse doSeal(AdjustSealRequest request) {
        AdjustBatchRow batch = refundRepository.findOpenAdjustBatchForUpdate()
                .orElseThrow(() -> MetroException.notFound("ADJUST_BATCH_NOT_FOUND",
                        "不存在开放调账批次"));
        if (batch.version() != request.expectedVersion()) {
            throw MetroException.conflict("VERSION_MISMATCH",
                    "调账批次版本不匹配: 期望 " + request.expectedVersion() + " 实际 " + batch.version());
        }
        List<AdjustOrderRow> orders = refundRepository.findAdjustOrdersByBatch(batch.id());
        long totalCents = orders.stream().mapToLong(AdjustOrderRow::amountCents).sum();
        refundRepository.sealAdjustBatch(batch.id(), totalCents, orders.size(), batch.version() + 1,
                request.requestId(), request.sealTime());

        List<AdjustSealResponse.AdjustOrderView> views = new ArrayList<>();
        for (AdjustOrderRow order : orders) {
            List<RefundConfirmResponse.LineAllocation> lines = new ArrayList<>();
            for (AdjustDetailRow detail : refundRepository.findAdjustDetailsByAdjustment(order.id())) {
                lines.add(new RefundConfirmResponse.LineAllocation(detail.lineCode(),
                        detail.amountCents()));
            }
            views.add(new AdjustSealResponse.AdjustOrderView(order.adjustNo(), order.refundNo(),
                    order.txnNo(), order.amountCents(), lines));
        }
        return new AdjustSealResponse(batch.id(), "SEALED", totalCents, orders.size(),
                batch.version() + 1, views, false);
    }

    /**
     * 行程资格校验：必须已出闸按实结算（COMPLETED），其原清分明细所在批次已封账；
     * 未出闸暂记（ENTERED/ESTIMATED）与封账后才到账（无原清分明细）不得退款。
     */
    private void requireSealedSettledTrip(TxnRow txn) {
        if (!"COMPLETED".equals(txn.status())) {
            throw MetroException.unprocessable("TRIP_NOT_SETTLED",
                    "仅已封账且按实结算行程可退款，未出闸暂记不处理，当前状态: " + txn.status());
        }
        if (txn.batchId() == null) {
            throw MetroException.unprocessable("TRIP_NOT_CLEARED",
                    "行程无封账清分记录，不能退款: " + txn.txnNo());
        }
        BatchRow originalBatch = metroRepository.findBatchById(txn.batchId())
                .orElseThrow(() -> new IllegalStateException("原清分批次缺失: " + txn.batchId()));
        if (!"SEALED".equals(originalBatch.status())) {
            throw MetroException.unprocessable("BATCH_NOT_SEALED",
                    "原清分批次尚未封账，不能退款: " + originalBatch.bizDate());
        }
    }

    /**
     * 读取原线路实收份额并校验合计等于原实收；金额单位分。
     *
     * @return 原实收合计（分）
     */
    private long requireOriginalSharesFound(String txnNo, Long fareCents) {
        List<LineAmountRow> rows = refundRepository.findOriginalShares(txnNo);
        if (rows.isEmpty()) {
            throw MetroException.unprocessable("TRIP_NOT_CLEARED",
                    "行程缺少原清分明细，不能退款: " + txnNo);
        }
        long sum = rows.stream().mapToLong(LineAmountRow::amountCents).sum();
        if (fareCents != null && sum != fareCents) {
            throw new IllegalStateException("原清分份额合计与实收不一致: " + txnNo);
        }
        return sum;
    }

    /**
     * 载入各线路 [原实收份额, 累计已退]；已退为非负数（调整明细绝对值口径）。
     */
    private Map<String, long[]> loadLineShares(String txnNo) {
        Map<String, long[]> shares = new LinkedHashMap<>();
        for (LineAmountRow row : refundRepository.findOriginalShares(txnNo)) {
            shares.put(row.lineCode(), new long[] {row.amountCents(), 0L});
        }
        for (LineAmountRow row : refundRepository.findRefundedByLine(txnNo)) {
            long[] value = shares.get(row.lineCode());
            if (value != null) {
                value[1] = row.amountCents();
            }
        }
        return shares;
    }

    private long sumRefunded(String txnNo) {
        return refundRepository.findRefundedByLine(txnNo).stream()
                .mapToLong(LineAmountRow::amountCents).sum();
    }

    /**
     * 按原线路实收份额分摊本次退款（整数分）：
     * 先向下取整并限制到各线路可退余额，剩余分逐一分给尚有可退余额的线路，
     * 按原份额降序、线路编号升序循环；合计等于退款且各线路累计不超原份额。
     */
    static List<LineAllocationCents> allocateRefund(Map<String, long[]> shares, long refundCents) {
        record Line(String code, long original, long refunded, long remaining) {
        }
        List<Line> lines = new ArrayList<>();
        long originalTotal = 0L;
        long remainingTotal = 0L;
        for (Map.Entry<String, long[]> entry : shares.entrySet()) {
            long original = entry.getValue()[0];
            long refunded = entry.getValue()[1];
            long remaining = original - refunded;
            lines.add(new Line(entry.getKey(), original, refunded, remaining));
            originalTotal += original;
            remainingTotal += remaining;
        }
        if (refundCents <= 0 || refundCents > remainingTotal || originalTotal <= 0) {
            throw new IllegalArgumentException("退款金额非法或超过可退余额");
        }
        List<Line> order = lines.stream()
                .sorted(Comparator.comparingLong(Line::original).reversed()
                        .thenComparing(Line::code))
                .toList();
        Map<String, Long> allocated = new LinkedHashMap<>();
        long assigned = 0L;
        for (Line line : order) {
            long floor = refundCents * line.original() / originalTotal;
            long capped = Math.min(floor, line.remaining());
            allocated.put(line.code(), capped);
            assigned += capped;
        }
        long leftover = refundCents - assigned;
        // 剩余分按份额降序、线路编号升序在仍有可退余额的线路间循环分发
        while (leftover > 0) {
            boolean progressed = false;
            for (Line line : order) {
                long current = allocated.get(line.code());
                long alreadyRefunded = line.refunded() + current;
                if (alreadyRefunded < line.original()) {
                    allocated.put(line.code(), current + 1);
                    leftover--;
                    progressed = true;
                    if (leftover == 0) {
                        break;
                    }
                }
            }
            if (!progressed) {
                throw new IllegalStateException("退款余额耗尽但仍有剩余分未分摊");
            }
        }
        List<LineAllocationCents> result = new ArrayList<>();
        long total = 0L;
        for (Line line : order) {
            long amount = allocated.get(line.code());
            if (line.refunded() + amount > line.original()) {
                throw new IllegalStateException("线路累计退款超过原份额: " + line.code());
            }
            total += amount;
            result.add(new LineAllocationCents(line.code(), amount));
        }
        if (total != refundCents) {
            throw new IllegalStateException("退款分摊合计不等于退款金额");
        }
        return result;
    }

    private static List<Map.Entry<String, long[]>> sortedEntries(Map<String, long[]> shares) {
        return shares.entrySet().stream()
                .sorted(Map.Entry.<String, long[]>comparingByValue(
                        Comparator.comparingLong((long[] v) -> v[0]).reversed())
                        .thenComparing(Map.Entry.comparingByKey()))
                .toList();
    }

    /** 开放调账批次加锁读取，不存在则创建（open_flag 唯一约束兜底并发）。 */
    private long getOrCreateOpenAdjustBatchForUpdate() {
        Optional<AdjustBatchRow> existing = refundRepository.findOpenAdjustBatchForUpdate();
        if (existing.isPresent()) {
            return existing.get().id();
        }
        try {
            return refundRepository.insertAdjustBatch();
        } catch (DuplicateKeyException ex) {
            // 并发建批：唯一开放批次约束冲突，重新加锁读取
            return refundRepository.findOpenAdjustBatchForUpdate()
                    .orElseThrow(() -> new IllegalStateException("开放调账批次创建失败"))
                    .id();
        }
    }

    private static String newBusinessNo(String prefix) {
        return prefix + Long.toHexString(System.currentTimeMillis())
                + java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 10);
    }

    /**
     * 单线路退款分摊结果（正数口径，单位分）。
     */
    record LineAllocationCents(String lineCode, long amountCents) {
    }
}
