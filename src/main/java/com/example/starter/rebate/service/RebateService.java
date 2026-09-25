package com.example.starter.rebate.service;

import com.example.starter.rebate.api.BatchResponse;
import com.example.starter.rebate.api.DoubtResponse;
import com.example.starter.rebate.api.DocumentResponse;
import com.example.starter.rebate.api.LateEventResponse;
import com.example.starter.rebate.api.RateVersionResponse;
import com.example.starter.rebate.api.RefundResponse;
import com.example.starter.rebate.api.ValidationDetailResponse;
import com.example.starter.rebate.domain.DoubtStatus;
import com.example.starter.rebate.domain.ValidationResultCode;
import com.example.starter.rebate.repo.BatchRow;
import com.example.starter.rebate.repo.DocumentRow;
import com.example.starter.rebate.repo.DoubtRow;
import com.example.starter.rebate.repo.LateEventRow;
import com.example.starter.rebate.repo.RateLibraryRow;
import com.example.starter.rebate.repo.RebateJdbcRepository;
import com.example.starter.rebate.repo.RefundRow;
import com.example.starter.rebate.repo.ValidationDetailRow;
import com.example.starter.rebate.support.BusinessConflictException;
import com.example.starter.rebate.support.BusinessRuleException;
import com.example.starter.rebate.support.NotFoundException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 出口退税申报核心领域服务：
 * 数据采集 → 关联校验（建批次）→ 疑点说明/复核 → 正式申报 → 审核 → 退库核销 → 结案。
 */
@Service
public class RebateService {

    /** 换汇成本合法区间（端点合法）。 */
    static final BigDecimal COST_LOWER = new BigDecimal("5.0");
    static final BigDecimal COST_UPPER = new BigDecimal("8.0");
    /** 到账差额容忍比例：恰 0.5% 视为一致，仅严格大于才转人工。 */
    static final BigDecimal DIFF_TOLERANCE = new BigDecimal("0.005");

    private static final int COST_SCALE = 10;

    private final RebateJdbcRepository repo;
    private final IdempotentExecutor idempotent;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;

    public RebateService(RebateJdbcRepository repo, IdempotentExecutor idempotent,
                         org.springframework.transaction.PlatformTransactionManager transactionManager,
                         Clock clock) {
        this.repo = repo;
        this.idempotent = idempotent;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    // ---------------- 退税率文库 ----------------

    /** 录入/覆盖退税率文库版本（合成数据）。 */
    public RateVersionResponse saveRate(String requestId, String hsCode,
                                        java.time.LocalDate effectiveDate, BigDecimal rate) {
        Object fp = new Fingerprint("SAVE_RATE", hsCode, effectiveDate.toString(), rate.stripTrailingZeros());
        return idempotent.execute(requestId, "SAVE_RATE", idempotent.fingerprint(fp), () -> {
            if (rate.signum() < 0 || rate.compareTo(BigDecimal.ONE) > 0) {
                throw new BusinessRuleException("退税率必须在 0~1 之间");
            }
            repo.upsertRate(hsCode, effectiveDate, rate);
            return new RateVersionResponse(hsCode, effectiveDate, rate);
        }, RateVersionResponse.class).data();
    }

    /** 复算入口：取申报日（UTC 日界）有效文库版本。 */
    public RateVersionResponse effectiveRate(String hsCode, java.time.LocalDate declareDate) {
        RateLibraryRow row = repo.findEffectiveRate(hsCode, declareDate);
        if (row == null) {
            throw new NotFoundException("商品编码 " + hsCode + " 在 " + declareDate + " 无有效退税率文库版本");
        }
        return new RateVersionResponse(row.hsCode(), row.effectiveDate(), row.rate());
    }

    // ---------------- 数据采集 ----------------

    /**
     * 采集报关单-发票配对单证。
     * 按“报关单号+发票号”去重；同一报关单号只允许参与一个有效批次（换发票号亦拒绝）。
     */
    public DocumentResponse collect(String requestId, String declarationNo, String invoiceNo,
                                    String hsCode, BigDecimal fobRmb, BigDecimal fobUsd) {
        Object fp = new Fingerprint("COLLECT", declarationNo, invoiceNo, hsCode,
                fobRmb.stripTrailingZeros().toPlainString(),
                fobUsd.stripTrailingZeros().toPlainString());
        return idempotent.execute(requestId, "COLLECT", idempotent.fingerprint(fp), () -> {
            DocumentRow existing = repo.findDocumentByPair(declarationNo, invoiceNo);
            if (existing != null) {
                return toDocumentResponse(existing, true);
            }
            if (repo.isDeclarationRegistered(declarationNo)) {
                throw new BusinessConflictException(
                        "报关单号 " + declarationNo + " 已参与有效申报批次，不得重复采集");
            }
            long id;
            try {
                id = repo.insertDocument(declarationNo, invoiceNo, hsCode, fobRmb, fobUsd);
            } catch (DuplicateKeyException concurrentDup) {
                DocumentRow winner = repo.findDocumentByPair(declarationNo, invoiceNo);
                return toDocumentResponse(winner, true);
            }
            return toDocumentResponse(repo.findDocumentById(id), false);
        }, DocumentResponse.class).data();
    }

    // ---------------- 关联校验（建批次） ----------------

    /**
     * 建批次、关联单证并执行换汇成本校验；越界生成 OPEN 疑点。
     * 全部单证必须未挂任何批次；同一报关单号不得借不同单证进入两个有效批次。
     */
    public BatchResponse createBatch(String requestId, String batchNo,
                                     java.time.LocalDate declareDate, List<Long> documentIds) {
        List<Long> distinctIds = new java.util.ArrayList<>(new LinkedHashSet<>(documentIds));
        Object fp = new Fingerprint("CREATE_BATCH", batchNo, declareDate.toString(), distinctIds);
        return idempotent.execute(requestId, "CREATE_BATCH", idempotent.fingerprint(fp), () -> {
            List<DocumentRow> docs = repo.findDocumentsByIds(distinctIds);
            if (docs.size() != distinctIds.size()) {
                throw new BusinessRuleException("存在不存在的单证 ID");
            }
            for (DocumentRow doc : docs) {
                if (doc.batchId() != null) {
                    throw new BusinessConflictException(
                            "单证 " + doc.id() + "（报关单 " + doc.declarationNo()
                                    + "）已关联批次，不得重复关联");
                }
            }

            long batchId;
            try {
                batchId = repo.insertBatch(batchNo, declareDate);
            } catch (DuplicateKeyException dupBatchNo) {
                throw new BusinessConflictException("批次号 " + batchNo + " 已存在");
            }
            // 先占用报关单号：主键唯一约束兜底并发，保证同一报关单号只进入一个有效批次。
            List<String> declarationNos = docs.stream().map(DocumentRow::declarationNo).distinct().toList();
            try {
                for (String declarationNo : declarationNos) {
                    repo.registerDeclaration(declarationNo, batchId);
                }
            } catch (DuplicateKeyException occupied) {
                throw new BusinessConflictException("报关单号已参与有效申报批次，不得重复关联");
            }
            int attached = repo.attachDocuments(distinctIds, batchId);
            if (attached != distinctIds.size()) {
                throw new BusinessConflictException("存在已被其他批次抢占的单证，关联失败");
            }

            BigDecimal taxTotal = BigDecimal.ZERO;
            for (DocumentRow doc : docs) {
                RateLibraryRow rateRow = repo.findEffectiveRate(doc.hsCode(), declareDate);
                if (rateRow == null) {
                    throw new BusinessRuleException(
                            "商品编码 " + doc.hsCode() + " 在申报日 " + declareDate + " 无有效退税率文库版本");
                }
                BigDecimal taxBase = doc.fobRmb().multiply(rateRow.rate())
                        .setScale(4, RoundingMode.HALF_UP);
                BigDecimal cost = taxBase.divide(doc.fobUsd(), COST_SCALE, RoundingMode.HALF_UP);
                boolean inRange = cost.compareTo(COST_LOWER) >= 0 && cost.compareTo(COST_UPPER) <= 0;
                String result = inRange ? ValidationResultCode.PASS.name() : ValidationResultCode.DOUBT.name();
                repo.insertValidationDetail(batchId, doc.id(), doc.hsCode(), rateRow.rate(),
                        rateRow.effectiveDate(), taxBase, cost, result);
                if (!inRange) {
                    repo.insertOpenDoubt(batchId, doc.id());
                }
                taxTotal = taxTotal.add(taxBase);
            }
            repo.updateTaxTotal(batchId, taxTotal);
            return assembleBatch(repo.findBatchById(batchId));
        }, BatchResponse.class).data();
    }

    // ---------------- 疑点说明 ----------------

    /** 提交疑点补充说明：OPEN→EXPLAINED，等待复核确认。 */
    public DoubtResponse explain(long doubtId, String requestId, String explanation) {
        Object fp = new Fingerprint("EXPLAIN", doubtId, explanation);
        return idempotent.execute(requestId, "SUBMIT_EXPLANATION", idempotent.fingerprint(fp), () -> {
            DoubtRow doubt = repo.findDoubtById(doubtId);
            if (doubt == null) {
                throw new NotFoundException("疑点 " + doubtId + " 不存在");
            }
            if (doubt.status() == DoubtStatus.CLEARED.name()) {
                return toDoubtResponse(doubt);
            }
            if (doubt.status() == DoubtStatus.EXPLAINED.name()) {
                throw new BusinessConflictException("疑点 " + doubtId + " 已提交说明，等待复核确认");
            }
            int updated = repo.markDoubtExplained(doubtId, explanation, requestId, Instant.now(clock));
            if (updated == 0) {
                throw new BusinessConflictException("疑点 " + doubtId + " 状态已变化，说明提交失败");
            }
            return toDoubtResponse(repo.findDoubtById(doubtId));
        }, DoubtResponse.class).data();
    }

    // ---------------- 正式申报（复核原子推进） ----------------

    /**
     * 正式申报：先复核确认传入的“已说明”疑点，再确认批次无残留未解除疑点，最后冻结批次。
     * 任一环节失败整体回滚，不得疑点未解除而批次已报出。
     */
    public BatchResponse declare(long batchId, String requestId, List<Long> reviewDoubtIds) {
        Set<Long> distinctDoubtIds = new LinkedHashSet<>(reviewDoubtIds);
        Object fp = new Fingerprint("DECLARE", batchId, new java.util.ArrayList<>(distinctDoubtIds));
        return idempotent.execute(requestId, "DECLARE", idempotent.fingerprint(fp), () -> {
            BatchRow batch = requireBatch(batchId);
            if (!"DRAFT".equals(batch.status())) {
                throw new BusinessConflictException("批次 " + batchId + " 状态为 "
                        + batch.status() + "，仅 DRAFT 批次可正式申报");
            }
            Instant now = Instant.now(clock);
            for (Long doubtId : distinctDoubtIds) {
                DoubtRow doubt = repo.findDoubtById(doubtId);
                if (doubt == null || doubt.batchId() != batchId) {
                    throw new NotFoundException("疑点 " + doubtId + " 不属于批次 " + batchId);
                }
                if (DoubtStatus.CLEARED.name().equals(doubt.status())) {
                    continue;
                }
                if (!DoubtStatus.EXPLAINED.name().equals(doubt.status())) {
                    throw new BusinessRuleException(
                            "疑点 " + doubtId + " 尚未提交补充说明，不能复核确认");
                }
                int reviewed = repo.markDoubtReviewed(doubtId, now);
                if (reviewed == 0) {
                    throw new BusinessConflictException("疑点 " + doubtId + " 复核失败，状态已变化");
                }
            }
            long uncleared = repo.countUnclearedDoubts(batchId);
            if (uncleared > 0) {
                throw new BusinessRuleException(
                        "批次仍有 " + uncleared + " 个疑点未复核解除，不得正式申报");
            }
            int declared = repo.declareBatch(batchId, now);
            if (declared == 0) {
                throw new BusinessConflictException("批次 " + batchId + " 已被并发申报或状态已变化");
            }
            return assembleBatch(repo.findBatchById(batchId));
        }, BatchResponse.class).data();
    }

    // ---------------- 审核 ----------------

    /** 审核通过：DECLARED→APPROVED。 */
    public BatchResponse approve(long batchId) {
        return transactionTemplate.execute(status -> {
            BatchRow batch = requireBatch(batchId);
            if (!"DECLARED".equals(batch.status())) {
                throw new BusinessConflictException("批次 " + batchId + " 状态为 "
                        + batch.status() + "，仅已正式申报批次可审核通过");
            }
            int updated = repo.approveBatch(batchId, Instant.now(clock));
            if (updated == 0) {
                throw new BusinessConflictException("批次 " + batchId + " 审核失败，状态已变化");
            }
            return assembleBatch(repo.findBatchById(batchId));
        });
    }

    // ---------------- 撤批整体作废 ----------------

    /**
     * 撤批：仅 DRAFT 可撤；整批作废、删除报关单占用并释放全部关联单证，同一事务完成，
     * 不支持部分撤出。
     */
    public BatchResponse voidBatch(long batchId) {
        return transactionTemplate.execute(status -> {
            BatchRow batch = requireBatch(batchId);
            if ("VOID".equals(batch.status())) {
                return assembleBatch(batch);
            }
            if ("CLOSED".equals(batch.status())) {
                throw new BusinessConflictException("批次 " + batchId + " 已结案，不得撤批");
            }
            if (!"DRAFT".equals(batch.status())) {
                throw new BusinessConflictException(
                        "批次 " + batchId + " 已正式申报并冻结，不得撤批；撤批仅允许在正式申报前整体作废");
            }
            Instant now = Instant.now(clock);
            repo.releaseDocumentsByBatch(batchId, now);
            repo.deleteDeclarationRegistration(batchId);
            int voided = repo.voidBatch(batchId, now);
            if (voided == 0) {
                throw new BusinessConflictException("批次 " + batchId + " 撤批失败，状态已变化");
            }
            return assembleBatch(repo.findBatchById(batchId));
        });
    }

    // ---------------- 退库核销 ----------------

    /**
     * 退库核销登记：批次+国库流水号唯一；差额恰 0.5%（含）一致，批次转 REFUNDED；
     * 严格超出 0.5% 登记 DISCREPANCY 并转人工，批次保持 APPROVED。
     */
    public RefundResponse refund(long batchId, String requestId,
                                 String treasurySerialNo, BigDecimal receivedAmount) {
        Object fp = new Fingerprint("REFUND", batchId, treasurySerialNo,
                receivedAmount.stripTrailingZeros().toPlainString());
        return idempotent.execute(requestId, "REFUND", idempotent.fingerprint(fp), () -> {
            BatchRow batch = requireBatch(batchId);
            RefundRow existing = repo.findRefund(batchId, treasurySerialNo);
            if (existing != null) {
                return toRefundResponse(existing, true);
            }
            if (!"APPROVED".equals(batch.status())) {
                throw new BusinessConflictException("批次 " + batchId + " 状态为 "
                        + batch.status() + "，仅审核通过批次可办理退库核销");
            }
            BigDecimal declaredTax = batch.taxTotal();
            if (declaredTax.signum() == 0) {
                throw new BusinessRuleException("批次申报税额为 0，无法核销");
            }
            BigDecimal diff = receivedAmount.subtract(declaredTax).abs();
            BigDecimal ratio = diff.divide(declaredTax, COST_SCALE, RoundingMode.HALF_UP);
            boolean consistent = ratio.compareTo(DIFF_TOLERANCE) <= 0;
            String status = consistent ? "CONSISTENT" : "DISCREPANCY";

            long id;
            try {
                id = repo.insertRefund(batchId, treasurySerialNo, declaredTax,
                        receivedAmount, ratio, status);
            } catch (DuplicateKeyException concurrentDup) {
                return toRefundResponse(repo.findRefund(batchId, treasurySerialNo), true);
            }
            if (consistent) {
                int refunded = repo.markRefunded(batchId, Instant.now(clock));
                if (refunded == 0) {
                    throw new BusinessConflictException("批次状态已变化，退库核销失败");
                }
            }
            return toRefundResponse(repo.findRefundById(id), false);
        }, RefundResponse.class).data();
    }

    // ---------------- 结案（终态） ----------------

    /**
     * 结案：仅 REFUNDED 可结案；CLOSED 为终态，重复结案幂等成功。
     */
    public BatchResponse close(long batchId, String requestId) {
        Object fp = new Fingerprint("CLOSE", batchId);
        return idempotent.execute(requestId, "CLOSE", idempotent.fingerprint(fp), () -> {
            BatchRow batch = requireBatch(batchId);
            if ("CLOSED".equals(batch.status())) {
                return assembleBatch(batch);
            }
            if (!"REFUNDED".equals(batch.status())) {
                throw new BusinessConflictException("批次 " + batchId + " 状态为 "
                        + batch.status() + "，仅已退库批次可结案");
            }
            int closed = repo.closeBatch(batchId, Instant.now(clock));
            if (closed == 0) {
                BatchRow current = repo.findBatchById(batchId);
                if ("CLOSED".equals(current.status())) {
                    return assembleBatch(current);
                }
                throw new BusinessConflictException("批次 " + batchId + " 结案失败，状态已变化");
            }
            return assembleBatch(repo.findBatchById(batchId));
        }, BatchResponse.class).data();
    }

    // ---------------- 结案后迟到单证 ----------------

    /**
     * 结案后迟到单证：批次须为 CLOSED，仅登记迟到事件，不改变终态。
     */
    public LateEventResponse registerLate(long batchId, String requestId,
                                          String declarationNo, String invoiceNo, String payload) {
        Object fp = new Fingerprint("LATE", batchId, declarationNo, invoiceNo, payload);
        return idempotent.execute(requestId, "LATE_DOCUMENT", idempotent.fingerprint(fp), () -> {
            BatchRow batch = requireBatch(batchId);
            if (!"CLOSED".equals(batch.status())) {
                throw new BusinessConflictException("批次 " + batchId + " 状态为 "
                        + batch.status() + "，仅已结案批次接受迟到单证登记");
            }
            long id = repo.insertLateEvent(batchId, declarationNo, invoiceNo, payload);
            LateEventRow row = repo.findLateEventById(id);
            return new LateEventResponse(row.id(), row.batchId(), row.declarationNo(),
                    row.invoiceNo(), row.payload(), row.createdAt(), false);
        }, LateEventResponse.class).data();
    }

    // ---------------- 查询 / 复算 ----------------

    public BatchResponse getBatch(long batchId) {
        return assembleBatch(requireBatch(batchId));
    }

    /** 复算校验明细与核销差异均通过查询接口返回首次计算留痕结果。 */
    public List<RefundResponse> getRefunds(long batchId) {
        requireBatch(batchId);
        return repo.findRefundsByBatch(batchId).stream().map(r -> toRefundResponse(r, false)).toList();
    }

    public List<LateEventResponse> getLateEvents(long batchId) {
        requireBatch(batchId);
        return repo.findLateEventsByBatch(batchId).stream()
                .map(r -> new LateEventResponse(r.id(), r.batchId(), r.declarationNo(),
                        r.invoiceNo(), r.payload(), r.createdAt(), false))
                .toList();
    }

    // ---------------- 内部映射 ----------------

    private BatchRow requireBatch(long batchId) {
        BatchRow batch = repo.findBatchById(batchId);
        if (batch == null) {
            throw new NotFoundException("批次 " + batchId + " 不存在");
        }
        return batch;
    }

    private BatchResponse assembleBatch(BatchRow batch) {
        List<ValidationDetailResponse> details = repo.findDetailsByBatch(batch.id()).stream()
                .map(d -> new ValidationDetailResponse(d.docId(), d.hsCode(), d.rateEffectiveDate(),
                        d.rate(), d.taxBase(), d.cost(), d.result()))
                .toList();
        List<DoubtResponse> doubts = repo.findDoubtsByBatch(batch.id()).stream()
                .map(this::toDoubtResponse)
                .toList();
        return new BatchResponse(batch.id(), batch.batchNo(), batch.declareDate(), batch.status(),
                batch.taxTotal(), details, doubts, batch.createdAt(), batch.declaredAt(),
                batch.approvedAt(), batch.refundedAt(), batch.voidedAt(), batch.closedAt());
    }

    private DocumentResponse toDocumentResponse(DocumentRow row, boolean replayed) {
        return new DocumentResponse(row.id(), row.declarationNo(), row.invoiceNo(), row.hsCode(),
                row.fobRmb(), row.fobUsd(), row.status(), row.batchId(), row.collectedAt(), replayed);
    }

    private DoubtResponse toDoubtResponse(DoubtRow row) {
        return new DoubtResponse(row.id(), row.batchId(), row.docId(), row.status(),
                row.explanation(), row.createdAt(), row.explainedAt(), row.reviewedAt());
    }

    private RefundResponse toRefundResponse(RefundRow row, boolean replayed) {
        if (row == null) {
            throw new IllegalStateException("退库核销登记不存在");
        }
        return new RefundResponse(row.id(), row.batchId(), row.treasurySerialNo(),
                row.declaredTax(), row.receivedAmount(), row.diffRatio(), row.status(),
                row.createdAt(), replayed);
    }

    /** 指纹输入的稳定载体：类型标记 + 有序业务字段。 */
    private record Fingerprint(String type, Object... fields) {
    }
}
