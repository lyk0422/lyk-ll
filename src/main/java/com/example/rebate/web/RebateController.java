package com.example.rebate.web;

import com.example.rebate.service.CollectionService;
import com.example.rebate.service.DeclarationService;
import com.example.rebate.service.DoubtService;
import com.example.rebate.service.IdempotencyService;
import com.example.rebate.service.IdempotentOutcome;
import com.example.rebate.service.QueryService;
import com.example.rebate.service.ReversalService;
import com.example.rebate.service.ServiceResult;
import com.example.rebate.service.TreasuryService;
import com.example.rebate.service.CheckService;
import com.example.rebate.service.AtomicIdempotencyService;
import com.example.rebate.web.dto.BatchView;
import com.example.rebate.web.dto.CheckRequest;
import com.example.rebate.web.dto.CheckResponse;
import com.example.rebate.web.dto.CollectCustomsRequest;
import com.example.rebate.web.dto.CollectInvoiceRequest;
import com.example.rebate.web.dto.CollectResponse;
import com.example.rebate.web.dto.DeclareRequest;
import com.example.rebate.web.dto.ExplainRequest;
import com.example.rebate.web.dto.LateDocumentRequest;
import com.example.rebate.web.dto.LateEventView;
import com.example.rebate.web.dto.LibraryRequest;
import com.example.rebate.web.dto.QuotaBalanceView;
import com.example.rebate.web.dto.RecalcResponse;
import com.example.rebate.web.dto.RequestIdRequest;
import com.example.rebate.web.dto.ReversalReceiptRequest;
import com.example.rebate.web.dto.ReversalReviewRequest;
import com.example.rebate.web.dto.ReversalSubmitRequest;
import com.example.rebate.web.dto.ReversalView;
import com.example.rebate.web.dto.ReviewRequest;
import com.example.rebate.web.dto.TreasuryRequest;
import com.example.rebate.web.dto.WriteoffView;
import jakarta.validation.Valid;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 出口退税申报服务 HTTP 入口。
 *
 * <p>数据采集 → 关联校验 → 正式申报 → 审核 → 退库（→结案）；
 * 除查询/复算外，全部写操作以 requestId 幂等，指纹覆盖 requestId 与全部业务字段（含路径变量）。</p>
 */
@RestController
public class RebateController {

    private final CollectionService collectionService;
    private final CheckService checkService;
    private final DoubtService doubtService;
    private final DeclarationService declarationService;
    private final TreasuryService treasuryService;
    private final QueryService queryService;
    private final IdempotencyService idempotency;
    private final AtomicIdempotencyService atomicIdempotency;
    private final ReversalService reversalService;

    public RebateController(CollectionService collectionService,
                            CheckService checkService,
                            DoubtService doubtService,
                            DeclarationService declarationService,
                            TreasuryService treasuryService,
                            QueryService queryService,
                            IdempotencyService idempotency,
                            AtomicIdempotencyService atomicIdempotency,
                            ReversalService reversalService) {
        this.collectionService = collectionService;
        this.checkService = checkService;
        this.doubtService = doubtService;
        this.declarationService = declarationService;
        this.treasuryService = treasuryService;
        this.queryService = queryService;
        this.idempotency = idempotency;
        this.atomicIdempotency = atomicIdempotency;
        this.reversalService = reversalService;
    }

    /**
     * 登记退税率文库版本（合成数据）。
     */
    @PostMapping("/api/library")
    public ResponseEntity<?> addLibrary(@Valid @RequestBody LibraryRequest req) {
        return idempotent("LIBRARY_UPSERT", req.requestId(), req,
                () -> ServiceResult.ok(collectionService.addLibrary(req)));
    }

    /**
     * 采集报关单（合成数据）。
     */
    @PostMapping("/api/collect/customs")
    public ResponseEntity<?> collectCustoms(@Valid @RequestBody CollectCustomsRequest req) {
        return idempotent("COLLECT_CUSTOMS", req.requestId(), req,
                () -> ServiceResult.ok(collectionService.collectCustoms(req)));
    }

    /**
     * 采集进项发票（合成数据），按“报关单号＋发票号”去重。
     */
    @PostMapping("/api/collect/invoices")
    public ResponseEntity<?> collectInvoice(@Valid @RequestBody CollectInvoiceRequest req) {
        return idempotent("COLLECT_INVOICE", req.requestId(), req,
                () -> ServiceResult.ok(collectionService.collectInvoice(req)));
    }

    /**
     * 关联校验：创建申报批次并逐单校验。
     */
    @PostMapping("/api/batches/check")
    public ResponseEntity<?> check(@Valid @RequestBody CheckRequest req) {
        return idempotent("BATCH_CHECK", req.requestId(), req,
                () -> ServiceResult.ok(checkService.check(req)));
    }

    /**
     * 提交疑点补充说明。
     */
    @PostMapping("/api/batches/{batchNo}/doubts/{doubtId}/explain")
    public ResponseEntity<?> explain(@PathVariable String batchNo,
                                     @PathVariable long doubtId,
                                     @Valid @RequestBody ExplainRequest req) {
        Map<String, Object> payload = payload(req, "batchNo", batchNo, "doubtId", doubtId);
        return idempotent("DOUBT_EXPLAIN", req.requestId(), payload,
                () -> ServiceResult.ok(doubtService.explain(batchNo, doubtId, req.explanation())));
    }

    /**
     * 复核确认疑点。
     */
    @PostMapping("/api/batches/{batchNo}/doubts/{doubtId}/review")
    public ResponseEntity<?> review(@PathVariable String batchNo,
                                    @PathVariable long doubtId,
                                    @Valid @RequestBody ReviewRequest req) {
        Map<String, Object> payload = payload(req, "batchNo", batchNo, "doubtId", doubtId);
        return idempotent("DOUBT_REVIEW", req.requestId(), payload,
                () -> ServiceResult.ok(doubtService.review(batchNo, doubtId)));
    }

    /**
     * 正式申报（可同事务复核已说明疑点），批次冻结。
     */
    @PostMapping("/api/batches/{batchNo}/declare")
    public ResponseEntity<?> declare(@PathVariable String batchNo,
                                     @Valid @RequestBody DeclareRequest req) {
        Map<String, Object> payload = payload(req, "batchNo", batchNo);
        return idempotent("BATCH_DECLARE", req.requestId(), payload,
                () -> ServiceResult.ok(declarationService.declare(batchNo, req.reviewConfirmed())));
    }

    /**
     * 审核通过。
     */
    @PostMapping("/api/batches/{batchNo}/approve")
    public ResponseEntity<?> approve(@PathVariable String batchNo,
                                     @Valid @RequestBody RequestIdRequest req) {
        Map<String, Object> payload = payload(req, "batchNo", batchNo);
        return idempotent("BATCH_APPROVE", req.requestId(), payload,
                () -> ServiceResult.ok(declarationService.approve(batchNo)));
    }

    /**
     * 撤批：整体作废并释放全部关联单证。
     */
    @PostMapping("/api/batches/{batchNo}/withdraw")
    public ResponseEntity<?> withdraw(@PathVariable String batchNo,
                                      @Valid @RequestBody RequestIdRequest req) {
        Map<String, Object> payload = payload(req, "batchNo", batchNo);
        return idempotent("BATCH_WITHDRAW", req.requestId(), payload,
                () -> ServiceResult.ok(declarationService.withdraw(batchNo)));
    }

    /**
     * 结案（终态）；重复结案幂等成功。
     */
    @PostMapping("/api/batches/{batchNo}/close")
    public ResponseEntity<?> close(@PathVariable String batchNo,
                                   @Valid @RequestBody RequestIdRequest req) {
        Map<String, Object> payload = payload(req, "batchNo", batchNo);
        return idempotent("BATCH_CLOSE", req.requestId(), payload,
                () -> ServiceResult.ok(declarationService.close(batchNo)));
    }

    /**
     * 结案后迟到单证登记（只记事件）。
     */
    @PostMapping("/api/batches/{batchNo}/late-documents")
    public ResponseEntity<?> lateDocument(@PathVariable String batchNo,
                                          @Valid @RequestBody LateDocumentRequest req) {
        Map<String, Object> payload = payload(req, "batchNo", batchNo);
        return idempotent("LATE_DOCUMENT", req.requestId(), payload,
                () -> ServiceResult.ok(declarationService.recordLateDocument(
                        batchNo, req.declarationNo(), req.invoiceNo(), req.note())));
    }

    /**
     * 退库核销：批次＋国库流水号唯一登记。
     */
    @PostMapping("/api/batches/{batchNo}/treasury")
    public ResponseEntity<?> treasury(@PathVariable String batchNo,
                                      @Valid @RequestBody TreasuryRequest req) {
        Map<String, Object> payload = payload(req, "batchNo", batchNo);
        return idempotent("TREASURY_WRITEOFF", req.requestId(), payload,
                () -> ServiceResult.ok(treasuryService.register(
                        batchNo, req.treasurySerial(), req.receivedAmount())));
    }

    /**
     * 查询批次状态。
     */
    @GetMapping("/api/batches/{batchNo}")
    public BatchView getBatch(@PathVariable String batchNo) {
        return queryService.getBatch(batchNo);
    }

    /**
     * 查询批次校验明细与疑点。
     */
    @GetMapping("/api/batches/{batchNo}/check")
    public CheckResponse getCheck(@PathVariable String batchNo) {
        return queryService.getCheck(batchNo);
    }

    /**
     * 查询批次核销登记。
     */
    @GetMapping("/api/batches/{batchNo}/treasury")
    public List<WriteoffView> listTreasury(@PathVariable String batchNo) {
        return queryService.listWriteoffs(batchNo);
    }

    /**
     * 查询批次迟到事件。
     */
    @GetMapping("/api/batches/{batchNo}/late-documents")
    public List<LateEventView> listLate(@PathVariable String batchNo) {
        return declarationService.listLateEvents(batchNo);
    }

    /**
     * 复算批次校验明细（文库版本、计税依据、换汇成本）。
     */
    @GetMapping("/api/recalc/check/{batchNo}")
    public RecalcResponse recalcCheck(@PathVariable String batchNo) {
        return queryService.recalcCheck(batchNo);
    }

    /**
     * 复算批次核销差异。
     */
    @GetMapping("/api/recalc/treasury/{batchNo}")
    public RecalcResponse recalcTreasury(@PathVariable String batchNo) {
        return queryService.recalcWriteoffs(batchNo);
    }

    /**
     * 复算按 UTC 申报日命中的文库版本。
     */
    @GetMapping("/api/recalc/library")
    public Map<String, Object> recalcLibrary(@RequestParam String productCode,
                                             @RequestParam LocalDate declareDate) {
        return queryService.recalcLibrary(productCode, declareDate);
    }

    // ------------------------------------------------------------------
    // 已结案批次分批冲回：提交 → 复核 → 追缴到账；写操作业务与幂等同事务
    // ------------------------------------------------------------------

    /**
     * 提交冲回单（初为待复核，版本 1）。
     */
    @PostMapping("/api/batches/{batchNo}/reversals")
    public ResponseEntity<?> submitReversal(@PathVariable String batchNo,
                                            @Valid @RequestBody ReversalSubmitRequest req) {
        Map<String, Object> payload = payload(req, "batchNo", batchNo);
        return atomic("REVERSAL_SUBMIT", req.requestId(), payload,
                () -> ServiceResult.ok(reversalService.submit(
                        batchNo, req.declarationNo(), req.correctionNo(), req.reason(),
                        req.amountCents(), req.submittedBy())));
    }

    /**
     * 复核冲回单：批准占用剩余可冲额；驳回不占额。
     */
    @PostMapping("/api/batches/{batchNo}/reversals/{correctionNo}/review")
    public ResponseEntity<?> reviewReversal(@PathVariable String batchNo,
                                            @PathVariable String correctionNo,
                                            @Valid @RequestBody ReversalReviewRequest req) {
        Map<String, Object> payload = payload(req, "batchNo", batchNo, "correctionNo", correctionNo);
        return atomic("REVERSAL_REVIEW", req.requestId(), payload,
                () -> ServiceResult.ok(reversalService.review(
                        batchNo, correctionNo, req.expectedVersion(), req.reviewedBy(),
                        req.approved(), req.rejectReason())));
    }

    /**
     * 登记追缴回执：独立流水号、实际收回金额与精确到账时刻。
     */
    @PostMapping("/api/batches/{batchNo}/reversals/{correctionNo}/receipts")
    public ResponseEntity<?> reversalReceipt(@PathVariable String batchNo,
                                             @PathVariable String correctionNo,
                                             @Valid @RequestBody ReversalReceiptRequest req) {
        Map<String, Object> payload = payload(req, "batchNo", batchNo, "correctionNo", correctionNo);
        return atomic("REVERSAL_RECEIPT", req.requestId(), payload,
                () -> ServiceResult.ok(reversalService.registerReceipt(
                        batchNo, correctionNo, req.expectedVersion(), req.receiptSerial(),
                        req.amountCents(), req.occurredAt())));
    }

    /**
     * 查询冲回单详情（含余额与追缴明细）。
     */
    @GetMapping("/api/batches/{batchNo}/reversals/{correctionNo}")
    public ReversalView getReversal(@PathVariable String batchNo,
                                    @PathVariable String correctionNo) {
        return reversalService.get(batchNo, correctionNo);
    }

    /**
     * 逐单可冲余额查询。
     */
    @GetMapping("/api/batches/{batchNo}/reversals/balance/{declarationNo}")
    public QuotaBalanceView reversalBalance(@PathVariable String batchNo,
                                            @PathVariable String declarationNo) {
        return reversalService.balance(batchNo, declarationNo);
    }

    private ResponseEntity<?> atomic(String opType, String requestId, Object payload,
                                     java.util.function.Supplier<ServiceResult> action) {
        IdempotentOutcome outcome = atomicIdempotency.run(opType, requestId, payload, action);
        if (outcome.first()) {
            return ResponseEntity.status(outcome.status()).body(outcome.firstBody());
        }
        return ResponseEntity.status(outcome.status())
                .contentType(MediaType.APPLICATION_JSON)
                .body(outcome.replayBody());
    }

    private ResponseEntity<?> idempotent(String opType, String requestId, Object payload,
                                         java.util.function.Supplier<ServiceResult> action) {
        IdempotentOutcome outcome = idempotency.run(opType, requestId, payload, action);
        if (outcome.first()) {
            return ResponseEntity.status(outcome.status()).body(outcome.firstBody());
        }
        return ResponseEntity.status(outcome.status())
                .contentType(MediaType.APPLICATION_JSON)
                .body(outcome.replayBody());
    }

    private Map<String, Object> payload(Object body, Object... kv) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("body", body);
        for (int i = 0; i + 1 < kv.length; i += 2) {
            map.put((String) kv[i], kv[i + 1]);
        }
        return map;
    }
}
