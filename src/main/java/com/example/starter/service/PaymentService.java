package com.example.starter.service;

import com.example.starter.domain.DiffType;
import com.example.starter.domain.Resolution;
import com.example.starter.dto.AdjustRequest;
import com.example.starter.dto.AdjustResponse;
import com.example.starter.dto.BatchSettlementResponse;
import com.example.starter.dto.CloseBatchRequest;
import com.example.starter.dto.PaymentRequest;
import com.example.starter.dto.PaymentResponse;
import com.example.starter.dto.ReconRequest;
import com.example.starter.dto.ReconResponse;
import com.example.starter.dto.ResolveRequest;
import com.example.starter.entity.Adjustment;
import com.example.starter.entity.Merchant;
import com.example.starter.entity.PaymentTxn;
import com.example.starter.entity.ReconDiff;
import com.example.starter.entity.SettlementBatch;
import com.example.starter.entity.SettlementOrder;
import com.example.starter.exception.BusinessException;
import com.example.starter.exception.ConflictException;
import com.example.starter.repository.AdjustmentRepository;
import com.example.starter.repository.BatchRepository;
import com.example.starter.repository.MerchantRepository;
import com.example.starter.repository.ReconRepository;
import com.example.starter.repository.SettlementOrderRepository;
import com.example.starter.repository.TxnRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 支付日切批次、封账清算、挂账调账与渠道对账主服务。 */
@Service
public class PaymentService {

    /** UTC 日切时刻：达到 16:00:00 整点即归次日批次。 */
    private static final int CUTOFF_HOUR = 16;

    private final Clock clock;
    private final MerchantRepository merchantRepository;
    private final BatchRepository batchRepository;
    private final TxnRepository txnRepository;
    private final SettlementOrderRepository orderRepository;
    private final AdjustmentRepository adjustmentRepository;
    private final ReconRepository reconRepository;

    public PaymentService(Clock clock,
                          MerchantRepository merchantRepository,
                          BatchRepository batchRepository,
                          TxnRepository txnRepository,
                          SettlementOrderRepository orderRepository,
                          AdjustmentRepository adjustmentRepository,
                          ReconRepository reconRepository) {
        this.clock = clock;
        this.merchantRepository = merchantRepository;
        this.batchRepository = batchRepository;
        this.txnRepository = txnRepository;
        this.orderRepository = orderRepository;
        this.adjustmentRepository = adjustmentRepository;
        this.reconRepository = reconRepository;
    }

    /** UTC 16:00 日切：时刻早于 16:00 归当日，达到或晚于 16:00（含整点）归次日。 */
    public LocalDate resolveBatchDate(Instant instant) {
        LocalDateTime utc = LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
        LocalDate date = utc.toLocalDate();
        return utc.getHour() >= CUTOFF_HOUR ? date.plusDays(1) : date;
    }

    /**
     * 入账：requestId 幂等 + 批次内渠道流水号去重；批次已封账则挂账。
     * 批次行级锁保证与封账竞态下交易只归属唯一批次。
     */
    @Transactional
    public PaymentResponse postPayment(PaymentRequest request) {
        String fingerprint = fingerprint(request);

        // 幂等快速路径：同 requestId 同参返回首次结果，异参 409。
        PaymentTxn existing = txnRepository.findByRequestId(request.requestId());
        if (existing != null) {
            if (!existing.getFingerprint().equals(fingerprint)) {
                throw new ConflictException("requestId=" + request.requestId() + " 已存在但请求参数不一致");
            }
            return toPaymentResponse(existing, true);
        }

        Merchant merchant = merchantRepository.findByNo(request.merchantNo());
        if (merchant == null) {
            throw new BusinessException("商户不存在: " + request.merchantNo());
        }
        if (!"ACTIVE".equals(merchant.getStatus())) {
            throw new BusinessException("商户已停用: " + request.merchantNo());
        }

        Instant occurredAt = request.occurredAt() != null ? request.occurredAt() : clock.instant();
        LocalDate batchDate = resolveBatchDate(occurredAt);
        Instant now = clock.instant();

        ensureBatch(batchDate, now);
        // 锁定批次行，与封账事务串行化。
        SettlementBatch batch = batchRepository.findByDateForUpdate(batchDate);
        boolean closed = "CLOSED".equals(batch.getStatus());

        PaymentTxn duplicate = txnRepository.findByBatchAndFlow(batchDate, request.channelFlowNo());
        if (duplicate != null) {
            // 并发同 requestId 同参在批次锁上排队后到达此处：回放首次结果，不报冲突。
            if (duplicate.getRequestId().equals(request.requestId())
                    && duplicate.getFingerprint().equals(fingerprint)) {
                return toPaymentResponse(duplicate, true);
            }
            throw new ConflictException("批次 " + batchDate + " 内渠道流水号重复: " + request.channelFlowNo());
        }

        PaymentTxn txn = new PaymentTxn();
        txn.setRequestId(request.requestId());
        txn.setFingerprint(fingerprint);
        txn.setChannelFlowNo(request.channelFlowNo());
        txn.setMerchantNo(request.merchantNo());
        txn.setAmount(request.amount());
        txn.setFee(calculateFee(request.amount(), merchant.getFeeRate()));
        txn.setBatchDate(batchDate);
        txn.setStatus(closed ? "SUSPENDED" : "POSTED");
        txn.setOccurredAt(occurredAt);
        txn.setCreatedAt(now);

        try {
            txnRepository.insert(txn);
        } catch (DuplicateKeyException ex) {
            // 并发兜底：requestId 唯一或 (批次,流水号) 唯一约束命中。
            PaymentTxn concurrent = txnRepository.findByRequestId(request.requestId());
            if (concurrent != null) {
                if (!concurrent.getFingerprint().equals(fingerprint)) {
                    throw new ConflictException("requestId=" + request.requestId() + " 已存在但请求参数不一致");
                }
                return toPaymentResponse(concurrent, true);
            }
            throw new ConflictException("批次 " + batchDate + " 内渠道流水号重复: " + request.channelFlowNo());
        }
        return toPaymentResponse(txn, false);
    }

    /** 封账：终态幂等，重复封账直接返回首次清算单且不重新计算。 */
    @Transactional
    public BatchSettlementResponse closeBatch(CloseBatchRequest request) {
        LocalDate batchDate = request != null && request.batchDate() != null
                ? request.batchDate()
                : resolveBatchDate(clock.instant());
        Instant now = clock.instant();

        ensureBatch(batchDate, now);
        SettlementBatch batch = batchRepository.findByDateForUpdate(batchDate);
        if ("CLOSED".equals(batch.getStatus())) {
            return buildSettlementResponse(batch);
        }

        List<PaymentTxn> txns = txnRepository.findEffectiveTxnsByBatch(batchDate);
        Map<String, long[]> byMerchant = new LinkedHashMap<>();
        for (PaymentTxn txn : txns) {
            long[] agg = byMerchant.computeIfAbsent(txn.getMerchantNo(), k -> new long[3]);
            agg[0] += 1;
            agg[1] += txn.getAmount();
            agg[2] += txn.getFee();
        }

        long totalAmount = 0;
        long totalFee = 0;
        for (Map.Entry<String, long[]> entry : byMerchant.entrySet()) {
            long[] agg = entry.getValue();
            SettlementOrder order = new SettlementOrder();
            order.setBatchDate(batchDate);
            order.setMerchantNo(entry.getKey());
            order.setTxnCount((int) agg[0]);
            order.setTotalAmount(agg[1]);
            order.setTotalFee(agg[2]);
            order.setSettledAmount(agg[1] - agg[2]);
            order.setCreatedAt(now);
            orderRepository.insert(order);
            totalAmount += agg[1];
            totalFee += agg[2];
        }

        int updated = batchRepository.markClosed(batchDate, now, byMerchant.size(),
                totalAmount, totalFee, totalAmount - totalFee);
        if (updated == 0) {
            // 防御：持锁理论上不会发生，并发已封账时返回首次清算单。
            SettlementBatch concurrent = batchRepository.findByDate(batchDate);
            return buildSettlementResponse(concurrent);
        }

        SettlementBatch closed = batchRepository.findByDate(batchDate);
        return buildSettlementResponse(closed);
    }

    public BatchSettlementResponse getBatch(LocalDate batchDate) {
        SettlementBatch batch = batchRepository.findByDate(batchDate);
        if (batch == null) {
            throw new BusinessException("批次不存在: " + batchDate);
        }
        return buildSettlementResponse(batch);
    }

    /**
     * 挂账调账：挂账交易改挂目标开放批次，调账留痕与原交易更新在同一事务提交，
     * 任一失败整体回滚，不会两边同时计额。
     */
    @Transactional
    public AdjustResponse adjust(AdjustRequest request) {
        Adjustment existing = adjustmentRepository.findByAdjustNo(request.adjustNo());
        if (existing != null) {
            return toAdjustResponse(existing, true);
        }
        if (request.fromBatchDate() == null) {
            throw new BusinessException("fromBatchDate 不能为空：须指定挂账所在的已封账批次");
        }
        Instant now = clock.instant();

        PaymentTxn suspended = txnRepository.findSuspendedForUpdate(
                request.fromBatchDate(), request.channelFlowNo());
        if (suspended == null) {
            // 并发竞态：挂账已被其他调账指令转入新批时返回 409。
            PaymentTxn handled = txnRepository.findMovedByOriginAndFlow(
                    request.fromBatchDate(), request.channelFlowNo());
            if (handled != null) {
                throw new ConflictException("挂账交易已被其他调账指令处理: " + request.channelFlowNo());
            }
            throw new BusinessException("批次 " + request.fromBatchDate() + " 下不存在挂账交易: "
                    + request.channelFlowNo());
        }

        LocalDate toBatchDate = request.toBatchDate() != null
                ? request.toBatchDate()
                : nextOpenBatchDate(request.fromBatchDate(), now);
        if (!toBatchDate.isAfter(request.fromBatchDate())) {
            throw new BusinessException("目标批次必须晚于挂账批次");
        }
        ensureBatch(toBatchDate, now);
        SettlementBatch target = batchRepository.findByDateForUpdate(toBatchDate);
        if ("CLOSED".equals(target.getStatus())) {
            throw new BusinessException("目标批次已封账，不能调入: " + toBatchDate);
        }
        PaymentTxn conflict = txnRepository.findByBatchAndFlow(toBatchDate, request.channelFlowNo());
        if (conflict != null) {
            throw new ConflictException("目标批次已存在相同渠道流水号: " + request.channelFlowNo());
        }

        int moved = txnRepository.markMoved(suspended.getId(), toBatchDate, now);
        if (moved == 0) {
            throw new ConflictException("挂账交易已被其他调账指令处理: " + request.channelFlowNo());
        }

        Adjustment adjustment = new Adjustment();
        adjustment.setAdjustNo(request.adjustNo());
        adjustment.setTxnId(suspended.getId());
        adjustment.setFromBatchDate(request.fromBatchDate());
        adjustment.setToBatchDate(toBatchDate);
        adjustment.setAmount(suspended.getAmount());
        adjustment.setStatus("DONE");
        adjustment.setCreatedAt(now);
        try {
            adjustmentRepository.insert(adjustment);
        } catch (DuplicateKeyException ex) {
            throw new ConflictException("调账指令号重复: " + request.adjustNo());
        }
        return toAdjustResponse(adjustment, false);
    }

    /** 渠道对账文件逐笔勾对，生成本地多账/渠道多账/金额差错三类差异。 */
    @Transactional
    public ReconResponse reconcile(ReconRequest request) {
        if (reconRepository.existsFile(request.fileNo())) {
            // 文件号幂等：不重复生成，返回该批次当前差异全貌。
            return buildReconResponse(request.fileNo(), request.batchDate());
        }
        Instant now = clock.instant();

        Map<String, PaymentTxn> localByFlow = new LinkedHashMap<>();
        for (PaymentTxn txn : txnRepository.findEffectiveTxnsByBatch(request.batchDate())) {
            localByFlow.put(txn.getChannelFlowNo(), txn);
        }

        Map<String, ReconRequest.ReconEntry> channelByFlow = new LinkedHashMap<>();
        for (ReconRequest.ReconEntry entry : request.entries()) {
            if (channelByFlow.put(entry.channelFlowNo(), entry) != null) {
                throw new BusinessException("对账文件内渠道流水号重复: " + entry.channelFlowNo());
            }
        }

        for (PaymentTxn local : localByFlow.values()) {
            ReconRequest.ReconEntry channel = channelByFlow.get(local.getChannelFlowNo());
            if (channel == null) {
                insertDiff(request.batchDate(), local.getChannelFlowNo(), local.getMerchantNo(),
                        DiffType.LOCAL_EXTRA, local.getAmount(), null, now);
            } else if (channel.amount() != local.getAmount()) {
                insertDiff(request.batchDate(), local.getChannelFlowNo(), local.getMerchantNo(),
                        DiffType.AMOUNT_MISMATCH, local.getAmount(), channel.amount(), now);
            }
        }
        for (ReconRequest.ReconEntry channel : channelByFlow.values()) {
            if (!localByFlow.containsKey(channel.channelFlowNo())) {
                insertDiff(request.batchDate(), channel.channelFlowNo(), channel.merchantNo(),
                        DiffType.CHANNEL_EXTRA, null, channel.amount(), now);
            }
        }

        reconRepository.insertFile(request.fileNo(), request.batchDate(), request.channelCode(), now);
        return buildReconResponse(request.fileNo(), request.batchDate());
    }

    /** 差异核销：PENDING 终态化，已核销差异重复核销返回 409。 */
    @Transactional
    public ReconDiff resolveDiff(Long diffId, ResolveRequest request) {
        ReconDiff diff = reconRepository.findById(diffId);
        if (diff == null) {
            throw new BusinessException("差异记录不存在: " + diffId);
        }
        if ("RESOLVED".equals(diff.getStatus())) {
            throw new ConflictException("差异已核销，不允许重复核销: id=" + diffId);
        }
        Resolution resolution;
        try {
            resolution = Resolution.valueOf(request.resolution());
        } catch (IllegalArgumentException ex) {
            throw new BusinessException("非法处理结果: " + request.resolution()
                    + "，允许值 WRITE_OFF / SUPPLEMENT / REFUND");
        }
        Instant now = clock.instant();
        int updated = reconRepository.markResolved(diffId, resolution.name(),
                request.resolvedBy() == null ? "system" : request.resolvedBy(),
                request.remark(), now);
        if (updated == 0) {
            throw new ConflictException("差异已被并发核销: id=" + diffId);
        }
        return reconRepository.findById(diffId);
    }

    private void insertDiff(LocalDate batchDate, String flowNo, String merchantNo,
                            DiffType type, Long localAmount, Long channelAmount, Instant now) {
        ReconDiff diff = new ReconDiff();
        diff.setBatchDate(batchDate);
        diff.setChannelFlowNo(flowNo);
        diff.setMerchantNo(merchantNo);
        diff.setDiffType(type.name());
        diff.setLocalAmount(localAmount);
        diff.setChannelAmount(channelAmount);
        diff.setCreatedAt(now);
        reconRepository.insertDiffIfAbsent(diff);
    }

    private LocalDate nextOpenBatchDate(LocalDate fromBatchDate, Instant now) {
        LocalDate candidate = fromBatchDate.plusDays(1);
        for (int i = 0; i < 366; i++) {
            SettlementBatch batch = batchRepository.findByDate(candidate);
            if (batch == null || "OPEN".equals(batch.getStatus())) {
                return candidate;
            }
            candidate = candidate.plusDays(1);
        }
        throw new BusinessException("未找到下一开放批次");
    }

    private long calculateFee(long amount, BigDecimal feeRate) {
        return BigDecimal.valueOf(amount)
                .multiply(feeRate)
                .setScale(0, RoundingMode.HALF_UP)
                .longValueExact();
    }

    private String fingerprint(PaymentRequest request) {
        String raw = String.join("|",
                request.requestId(),
                request.channelFlowNo(),
                String.valueOf(request.amount()),
                request.merchantNo());
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private PaymentResponse toPaymentResponse(PaymentTxn txn, boolean replayed) {
        return new PaymentResponse(txn.getRequestId(), txn.getChannelFlowNo(), txn.getMerchantNo(),
                txn.getAmount(), txn.getFee(), txn.getStatus(), txn.getBatchDate(), replayed);
    }

    private AdjustResponse toAdjustResponse(Adjustment adjustment, boolean replayed) {
        PaymentTxn txn = txnRepository.findById(adjustment.getTxnId());
        return new AdjustResponse(adjustment.getAdjustNo(),
                txn == null ? null : txn.getChannelFlowNo(),
                adjustment.getFromBatchDate(), adjustment.getToBatchDate(), adjustment.getAmount(),
                adjustment.getStatus(), adjustment.getCreatedAt(), replayed);
    }

    private BatchSettlementResponse buildSettlementResponse(SettlementBatch batch) {
        List<SettlementOrder> orders = "CLOSED".equals(batch.getStatus())
                ? orderRepository.findByBatch(batch.getBatchDate())
                : List.of();
        return new BatchSettlementResponse(batch.getBatchDate(), batch.getStatus(), batch.getClosedAt(),
                batch.getMerchantCount(), batch.getTotalAmount(), batch.getTotalFee(),
                batch.getSettledAmount(), orders);
    }

    private ReconResponse buildReconResponse(String fileNo, LocalDate batchDate) {
        List<ReconDiff> diffs = reconRepository.findByBatch(batchDate);
        int localExtra = 0;
        int channelExtra = 0;
        int mismatch = 0;
        for (ReconDiff diff : diffs) {
            switch (DiffType.valueOf(diff.getDiffType())) {
                case LOCAL_EXTRA -> localExtra++;
                case CHANNEL_EXTRA -> channelExtra++;
                case AMOUNT_MISMATCH -> mismatch++;
            }
        }
        return new ReconResponse(fileNo, batchDate, localExtra, channelExtra, mismatch,
                new ArrayList<>(diffs));
    }

    /** 确保批次行存在；并发首建撞主键时忽略，随后调用方以 FOR UPDATE 取锁。 */
    private void ensureBatch(LocalDate batchDate, Instant now) {
        try {
            batchRepository.insertIfOpenAbsent(batchDate, now);
        } catch (DuplicateKeyException ignored) {
            // 其他事务已创建该批次，直接进入加锁读取即可。
        }
    }
}
