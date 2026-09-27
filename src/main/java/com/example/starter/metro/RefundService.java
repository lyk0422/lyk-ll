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
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Supplier;

import com.example.starter.metro.MetroRepository.BatchRow;
import com.example.starter.metro.MetroRepository.DetailRow;
import com.example.starter.metro.MetroRepository.IdemRow;
import com.example.starter.metro.MetroRepository.TxnRow;
import com.example.starter.metro.RefundRepository.AdjustmentBatchRow;
import com.example.starter.metro.RefundRepository.AdjustmentDetailRow;
import com.example.starter.metro.RefundRepository.AdjustmentRow;
import com.example.starter.metro.RefundRepository.RefundRow;
import com.example.starter.metro.dto.ReplayableResponse;
import com.example.starter.metro.dto.refund.AdjustmentIncludeRequest;
import com.example.starter.metro.dto.refund.AdjustmentIncludeResponse;
import com.example.starter.metro.dto.refund.AdjustmentSealRequest;
import com.example.starter.metro.dto.refund.AdjustmentSealResponse;
import com.example.starter.metro.dto.refund.CardCloseRequest;
import com.example.starter.metro.dto.refund.CardCloseResponse;
import com.example.starter.metro.dto.refund.RefundApplyRequest;
import com.example.starter.metro.dto.refund.RefundApplyResponse;
import com.example.starter.metro.dto.refund.RefundBalanceResponse;
import com.example.starter.metro.dto.refund.RefundConfirmRequest;
import com.example.starter.metro.dto.refund.RefundConfirmResponse;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 已封账行程退款冲正服务。
 *
 * <p>退款只能引用已封账且按实结算（COMPLETED 且所属清分批次 SEALED）的行程，
 * 不处理未出闸暂记。金额整数分、单次为正、累计不超原实收，恰退满合法。
 * 退款单经两名不同复核人在最终确认事务内共同校验后生效，生成独立负向调整明细，
 * 原流水与封账合计永不改写。</p>
 *
 * <p>新增写操作均带 requestId 幂等：同键同参重放首次冻结结果，同键异参 409，
 * 失败不占键；expectedVersion 从 1 开始、成功变更加 1，业务、版本与幂等结果同事务。</p>
 */
@Service
public class RefundService {

    static final String SCOPE_APPLY = "REFUND_APPLY";
    static final String SCOPE_CONFIRM = "REFUND_CONFIRM";
    static final String SCOPE_INCLUDE = "ADJ_INCLUDE";
    static final String SCOPE_SEAL = "ADJ_SEAL";
    static final String SCOPE_CARD_CLOSE = "CARD_CLOSE";

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

    /** 退款申请：创建 PENDING 退款单（版本 1），不占用行程可退余额。 */
    public RefundApplyResponse apply(RefundApplyRequest request) {
        String fingerprint = structuredFingerprint(SCOPE_APPLY,
                request.refundNo(), request.txnNo(), Long.toString(request.amountCents()),
                request.applyTime().toString());
        return idempotent(SCOPE_APPLY, request.requestId(), fingerprint, RefundApplyResponse.class,
                () -> doApply(request, fingerprint));
    }

    /**
     * 双人最终确认：同一事务内共同校验两名不同复核人，占用行程可退余额，
     * 登记独立调账单与逐线路负向调整明细，退款单版本加 1。
     */
    public RefundConfirmResponse confirm(RefundConfirmRequest request) {
        String fingerprint = structuredFingerprint(SCOPE_CONFIRM,
                request.refundNo(), Integer.toString(request.expectedVersion()),
                request.confirmerOne(), request.confirmerOneTime().toString(),
                request.confirmerTwo(), request.confirmerTwoTime().toString());
        return idempotent(SCOPE_CONFIRM, request.requestId(), fingerprint, RefundConfirmResponse.class,
                () -> doConfirm(request));
    }

    /** 调账单纳入唯一开放调账批次；重复纳入不重复计额。 */
    public AdjustmentIncludeResponse include(AdjustmentIncludeRequest request) {
        String fingerprint = structuredFingerprint(SCOPE_INCLUDE,
                request.adjustmentNo(), Integer.toString(request.expectedVersion()));
        return idempotent(SCOPE_INCLUDE, request.requestId(), fingerprint,
                AdjustmentIncludeResponse.class, () -> doInclude(request));
    }

    /** 调账批次封存：封存后只读，重复封存幂等成功。 */
    public AdjustmentSealResponse sealBatch(AdjustmentSealRequest request) {
        String fingerprint = structuredFingerprint(SCOPE_SEAL,
                request.batchNo(), Integer.toString(request.expectedVersion()));
        return idempotent(SCOPE_SEAL, request.requestId(), fingerprint,
                AdjustmentSealResponse.class, () -> doSeal(request));
    }

    /** 查询行程逐线路原份额、已退与可退余额。 */
    public RefundBalanceResponse balance(String txnNo) {
        TxnRow txn = metroRepository.findTxnByNo(txnNo)
                .orElseThrow(() -> MetroException.notFound("TXN_NOT_FOUND", "交易流水不存在: " + txnNo));
        requireSealedSettled(txn);
        Map<String, OriginalShare> originals = loadOriginalShares(txnNo);
        Map<String, Long> refundedByLine = refundedByLine(txnNo);
        long refundedTotal = refundedByLine.values().stream().mapToLong(Long::longValue).sum();
        List<RefundBalanceResponse.LineBalance> lines = new ArrayList<>();
        for (String line : originals.keySet().stream().sorted().toList()) {
            long original = originals.get(line).amountCents();
            long refunded = refundedByLine.getOrDefault(line, 0L);
            lines.add(new RefundBalanceResponse.LineBalance(line, original, refunded,
                    original - refunded));
        }
        long originalTotal = originals.values().stream().mapToLong(OriginalShare::amountCents).sum();
        return new RefundBalanceResponse(txnNo, originalTotal, refundedTotal,
                originalTotal - refundedTotal, lines);
    }

    /** 关闭票卡；关闭不影响已成功退款结果的历史重放。 */
    public CardCloseResponse closeCard(CardCloseRequest request) {
        String fingerprint = structuredFingerprint(SCOPE_CARD_CLOSE,
                request.cardId(), request.closeTime().toString());
        return idempotent(SCOPE_CARD_CLOSE, request.requestId(), fingerprint, CardCloseResponse.class,
                () -> doCloseCard(request));
    }

    private RefundApplyResponse doApply(RefundApplyRequest request, String fingerprint) {
        TxnRow txn = metroRepository.findTxnByNoForUpdate(request.txnNo())
                .orElseThrow(() -> MetroException.notFound("TXN_NOT_FOUND",
                        "交易流水不存在: " + request.txnNo()));
        requireSealedSettled(txn);
        if (refundRepository.findRefund(request.refundNo()).isPresent()) {
            throw MetroException.conflict("DUPLICATE_REFUND_NO", "退款单号重复: " + request.refundNo());
        }
        long originalTotal = originalTotal(txn.txnNo());
        long refundedTotal = refundedTotal(txn.txnNo());
        if (request.amountCents() > originalTotal - refundedTotal) {
            throw MetroException.unprocessable("REFUND_EXCEEDS_RECEIVED",
                    "累计退款不得超过原实收: 申请 " + request.amountCents() + " 分，可退 "
                            + (originalTotal - refundedTotal) + " 分");
        }
        try {
            refundRepository.insertRefund(new RefundRow(0, request.refundNo(), request.txnNo(),
                    request.amountCents(), "PENDING", null, null, null, null, null, 1,
                    request.requestId(), fingerprint, null));
        } catch (DuplicateKeyException ex) {
            throw MetroException.conflict("DUPLICATE_REFUND_NO",
                    "退款单号重复: " + request.refundNo());
        }
        return new RefundApplyResponse(request.refundNo(), request.txnNo(), request.amountCents(),
                "PENDING", 1, false);
    }

    private RefundConfirmResponse doConfirm(RefundConfirmRequest request) {
        // 先锁退款单，再锁原交易行：对同一行程的并发确认在原交易行上串行，杜绝并发超退
        RefundRow refund = refundRepository.findRefundForUpdate(request.refundNo())
                .orElseThrow(() -> MetroException.notFound("REFUND_NOT_FOUND",
                        "退款单不存在: " + request.refundNo()));
        TxnRow txn = metroRepository.findTxnByNoForUpdate(refund.txnNo())
                .orElseThrow(() -> MetroException.notFound("TXN_NOT_FOUND", "交易流水不存在"));
        if (refund.version() != request.expectedVersion()) {
            throw MetroException.conflict("VERSION_CONFLICT",
                    "退款单版本不匹配: 期望 " + request.expectedVersion() + "，实际 " + refund.version());
        }
        if (!"PENDING".equals(refund.status())) {
            throw MetroException.conflict("REFUND_ALREADY_CONFIRMED",
                    "退款单已确认生效: " + request.refundNo());
        }
        if (request.confirmerOne().equals(request.confirmerTwo())) {
            throw MetroException.unprocessable("CONFIRMERS_MUST_DIFFER",
                    "两名复核人必须不同，单人确认不生效");
        }
        requireSealedSettled(txn);

        Map<String, OriginalShare> originals = loadOriginalShares(txn.txnNo());
        Map<String, Long> refundedByLine = refundedByLine(txn.txnNo());
        long originalTotal = originals.values().stream().mapToLong(OriginalShare::amountCents).sum();
        long availableTotal = originalTotal
                - refundedByLine.values().stream().mapToLong(Long::longValue).sum();
        if (refund.amountCents() > availableTotal) {
            throw MetroException.unprocessable("REFUND_EXCEEDS_RECEIVED",
                    "累计退款不得超过原实收: 本次 " + refund.amountCents() + " 分，可退 "
                            + availableTotal + " 分");
        }

        Map<String, Long> allocation = allocate(shareAmounts(originals), refundedByLine,
                refund.amountCents());
        String adjustmentNo = "ADJ-" + refund.refundNo();
        refundRepository.insertAdjustment(new AdjustmentRow(0, adjustmentNo, refund.refundNo(),
                txn.txnNo(), refund.amountCents(), null, "REGISTERED", 1, null));
        List<RefundConfirmResponse.LineAdjustment> lineAdjustments = new ArrayList<>();
        for (String line : originals.keySet().stream().sorted().toList()) {
            long amount = allocation.getOrDefault(line, 0L);
            if (amount > 0) {
                refundRepository.insertAdjustmentDetail(new AdjustmentDetailRow(0, adjustmentNo,
                        txn.txnNo(), originals.get(line).originalDetailId(), line, -amount));
            }
            lineAdjustments.add(new RefundConfirmResponse.LineAdjustment(line, amount));
        }
        LocalDateTime confirmedAt = request.confirmerTwoTime()
                .isAfter(request.confirmerOneTime())
                ? request.confirmerTwoTime() : request.confirmerOneTime();
        int updated = refundRepository.confirmRefund(refund.refundNo(), request.expectedVersion(),
                request.confirmerOne(), request.confirmerOneTime(), request.confirmerTwo(),
                request.confirmerTwoTime(), adjustmentNo, confirmedAt);
        if (updated != 1) {
            throw MetroException.conflict("VERSION_CONFLICT", "退款单状态已变更，确认未生效");
        }
        return new RefundConfirmResponse(refund.refundNo(), adjustmentNo, txn.txnNo(),
                refund.amountCents(), "CONFIRMED", refund.version() + 1, lineAdjustments, false);
    }

    private AdjustmentIncludeResponse doInclude(AdjustmentIncludeRequest request) {
        AdjustmentRow adjustment = refundRepository.findAdjustmentForUpdate(request.adjustmentNo())
                .orElseThrow(() -> MetroException.notFound("ADJUSTMENT_NOT_FOUND",
                        "调账单不存在: " + request.adjustmentNo()));
        // 重复纳入（含并发、异键）：返回已属批次，不重复计额
        if ("INCLUDED".equals(adjustment.status()) && adjustment.batchId() != null) {
            AdjustmentBatchRow batch = refundRepository.findAdjustmentBatchById(adjustment.batchId())
                    .orElseThrow(() -> new IllegalStateException("调账批次缺失"));
            return new AdjustmentIncludeResponse(adjustment.adjustmentNo(), batch.batchNo(),
                    "INCLUDED", adjustment.version(), false);
        }
        if (adjustment.version() != request.expectedVersion()) {
            throw MetroException.conflict("VERSION_CONFLICT",
                    "调账单版本不匹配: 期望 " + request.expectedVersion() + "，实际 "
                            + adjustment.version());
        }
        AdjustmentBatchRow batch = getOrCreateOpenBatchForUpdate();
        int updated = refundRepository.includeAdjustment(adjustment.adjustmentNo(),
                request.expectedVersion(), batch.id());
        if (updated != 1) {
            AdjustmentRow reloaded = refundRepository.findAdjustmentForUpdate(adjustment.adjustmentNo())
                    .orElseThrow();
            if ("INCLUDED".equals(reloaded.status()) && reloaded.batchId() != null) {
                AdjustmentBatchRow existingBatch =
                        refundRepository.findAdjustmentBatchById(reloaded.batchId()).orElseThrow();
                return new AdjustmentIncludeResponse(reloaded.adjustmentNo(),
                        existingBatch.batchNo(), "INCLUDED", reloaded.version(), false);
            }
            throw MetroException.conflict("VERSION_CONFLICT", "调账单状态已变更，纳入未生效");
        }
        return new AdjustmentIncludeResponse(adjustment.adjustmentNo(), batch.batchNo(),
                "INCLUDED", adjustment.version() + 1, false);
    }

    private AdjustmentSealResponse doSeal(AdjustmentSealRequest request) {
        AdjustmentBatchRow batch = refundRepository.findAdjustmentBatchForUpdate(request.batchNo())
                .orElseThrow(() -> MetroException.notFound("ADJUSTMENT_BATCH_NOT_FOUND",
                        "调账批次不存在: " + request.batchNo()));
        if ("SEALED".equals(batch.status())) {
            // 封存为终态：重复封存幂等成功，只读快照不改
            return new AdjustmentSealResponse(batch.batchNo(), "SEALED", batch.totalCents(),
                    batch.adjustmentCount(), batch.version(), false);
        }
        if (batch.version() != request.expectedVersion()) {
            throw MetroException.conflict("VERSION_CONFLICT",
                    "调账批次版本不匹配: 期望 " + request.expectedVersion() + "，实际 "
                            + batch.version());
        }
        int count = refundRepository.countAdjustmentsInBatch(batch.id());
        long totalCents = -refundRepository.sumAdjustmentAmountsInBatch(batch.id());
        LocalDateTime sealedAt = LocalDateTime.now();
        int updated = refundRepository.sealAdjustmentBatch(batch.id(), request.expectedVersion(),
                totalCents, count, request.requestId(), sealedAt);
        if (updated != 1) {
            throw MetroException.conflict("VERSION_CONFLICT", "调账批次状态已变更，封存未生效");
        }
        return new AdjustmentSealResponse(batch.batchNo(), "SEALED", totalCents, count,
                batch.version() + 1, false);
    }

    private CardCloseResponse doCloseCard(CardCloseRequest request) {
        if (!refundRepository.isCardClosed(request.cardId())) {
            try {
                refundRepository.insertCardClose(request.cardId(), request.requestId(),
                        request.closeTime());
            } catch (DuplicateKeyException ex) {
                // 并发关闭：以已关闭为准
            }
        }
        return new CardCloseResponse(request.cardId(), true, false);
    }

    private Map<String, Long> shareAmounts(Map<String, OriginalShare> originals) {
        Map<String, Long> amounts = new TreeMap<>();
        originals.forEach((line, share) -> amounts.put(line, share.amountCents()));
        return amounts;
    }

    /**
     * 纯分摊规则（无数据库依赖，便于单元测试）：
     * 先向下取整并夹到各线路可退余额，剩余分按原份额降序、线路编号升序逐分循环补发。
     */
    static Map<String, Long> allocate(Map<String, Long> originalByLine,
                                      Map<String, Long> refundedByLine, long refundCents) {
        long originalTotal = originalByLine.values().stream().mapToLong(Long::longValue).sum();
        List<String> order = originalByLine.keySet().stream()
                .sorted(Comparator.<String>comparingLong(originalByLine::get)
                        .reversed()
                        .thenComparing(Comparator.naturalOrder()))
                .toList();
        Map<String, Long> allocation = new LinkedHashMap<>();
        long assigned = 0L;
        for (String line : order) {
            long available = originalByLine.get(line) - refundedByLine.getOrDefault(line, 0L);
            long floor = refundCents * originalByLine.get(line) / originalTotal;
            long amount = Math.min(floor, available);
            allocation.put(line, amount);
            assigned += amount;
        }
        long remaining = refundCents - assigned;
        int cursor = 0;
        int guard = 0;
        int maxGuards = order.size() * (int) Math.min(refundCents + 1, Integer.MAX_VALUE) + order.size();
        while (remaining > 0) {
            String line = order.get(cursor % order.size());
            cursor++;
            long available = originalByLine.get(line) - refundedByLine.getOrDefault(line, 0L);
            if (allocation.get(line) < available) {
                allocation.merge(line, 1L, Long::sum);
                remaining--;
            }
            if (++guard > maxGuards) {
                throw new IllegalStateException("退款分摊无法收敛");
            }
        }
        return allocation;
    }

    private void requireSealedSettled(TxnRow txn) {
        if (!"COMPLETED".equals(txn.status()) || txn.batchId() == null) {
            throw MetroException.unprocessable("REFUND_TXN_NOT_SETTLED",
                    "退款只能引用已按实结算行程，当前状态: " + txn.status());
        }
        BatchRow batch = metroRepository.findBatchById(txn.batchId())
                .orElseThrow(() -> new IllegalStateException("原清分批次缺失: " + txn.batchId()));
        if (!"SEALED".equals(batch.status())) {
            throw MetroException.unprocessable("REFUND_TXN_NOT_SEALED",
                    "退款只能引用已封账行程，原批次尚未封账: " + batch.bizDate());
        }
    }

    /** 原按实结算正份额：entry_type 为 NORMAL/SETTLE 且金额为正的原清分明细。 */
    private Map<String, OriginalShare> loadOriginalShares(String txnNo) {
        Map<String, OriginalShare> shares = new TreeMap<>();
        for (String type : List.of("NORMAL", "SETTLE")) {
            for (DetailRow detail : metroRepository.findDetailsByTxnAndType(txnNo, type)) {
                if (detail.amountCents() <= 0) {
                    continue;
                }
                shares.merge(detail.lineCode(),
                        new OriginalShare(detail.id(), detail.amountCents()),
                        (a, b) -> new OriginalShare(a.originalDetailId(),
                                a.amountCents() + b.amountCents()));
            }
        }
        if (shares.isEmpty()) {
            throw MetroException.unprocessable("REFUND_NO_ORIGINAL_DETAIL",
                    "原清分明细缺失或非按实结算，无法退款: " + txnNo);
        }
        return shares;
    }

    private long originalTotal(String txnNo) {
        return loadOriginalShares(txnNo).values().stream()
                .mapToLong(OriginalShare::amountCents).sum();
    }

    private Map<String, Long> refundedByLine(String txnNo) {
        Map<String, Long> result = new TreeMap<>();
        for (AdjustmentDetailRow detail : refundRepository.findAdjustmentDetailsByTxn(txnNo)) {
            result.merge(detail.lineCode(), -detail.amountCents(), Long::sum);
        }
        return result;
    }

    private long refundedTotal(String txnNo) {
        return refundedByLine(txnNo).values().stream().mapToLong(Long::longValue).sum();
    }

    private AdjustmentBatchRow getOrCreateOpenBatchForUpdate() {
        Optional<AdjustmentBatchRow> existing = refundRepository.findOpenBatchForUpdate();
        if (existing.isPresent()) {
            return existing.get();
        }
        String batchNo = "AB-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
        try {
            long id = refundRepository.insertAdjustmentBatch(batchNo);
            return refundRepository.findAdjustmentBatchById(id)
                    .orElseThrow(() -> new IllegalStateException("调账批次创建失败: " + batchNo));
        } catch (DuplicateKeyException ex) {
            // 并发创建开放批次：唯一占位约束保证只有一个，重新加锁读取
            return refundRepository.findOpenBatchForUpdate()
                    .orElseThrow(() -> new IllegalStateException("开放调账批次查询失败"));
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
                metroRepository.insertIdemPlaceholder(scope, requestId, fingerprint);
                T response = action.get();
                metroRepository.updateIdemResponse(scope, requestId, toJson(response));
                return response;
            });
        } catch (DuplicateKeyException ex) {
            return metroRepository.findIdem(scope, requestId)
                    .map(row -> replay(row, fingerprint, type))
                    .orElseThrow(() -> MetroException.conflict("DUPLICATE_BUSINESS_KEY",
                            "业务唯一键冲突，操作未生效"));
        }
    }

    private <T extends ReplayableResponse<T>> T replay(IdemRow row, String fingerprint, Class<T> type) {
        if (!row.fingerprint().equals(fingerprint)) {
            throw MetroException.conflict("IDEMPOTENCY_CONFLICT",
                    "相同 requestId 但业务参数不一致，拒绝重复执行");
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
     * 结构化指纹：对各字段做“长度前缀 + 原文”编码，合法文本即使含分隔符 '|'
     * 或长度数字也不会与其他参数组合产生歧义，再做 SHA-256。
     */
    static String structuredFingerprint(String scope, String... parts) {
        StringBuilder joined = new StringBuilder(scope);
        for (String part : parts) {
            joined.append('|').append(part.length()).append(':').append(part);
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(joined.toString().getBytes(StandardCharsets.UTF_8));
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

    /**
     * 原线路实收份额（含证据：原清分明细 ID）。
     */
    private record OriginalShare(long originalDetailId, long amountCents) {
    }
}
