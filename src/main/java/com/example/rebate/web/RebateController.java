package com.example.rebate.web;

import com.example.rebate.service.CollectionService;
import com.example.rebate.service.DeclarationService;
import com.example.rebate.service.DoubtService;
import com.example.rebate.service.IdempotencyService;
import com.example.rebate.service.IdempotentOutcome;
import com.example.rebate.service.QueryService;
import com.example.rebate.service.ServiceResult;
import com.example.rebate.service.TreasuryService;
import com.example.rebate.service.CheckService;
import com.example.rebate.service.ReversalService;
import com.example.rebate.web.dto.BatchView;
import com.example.rebate.web.dto.CheckRequest;
import com.example.rebate.web.dto.CheckResponse;
import com.example.rebate.web.dto.ClawbackReceiptRequest;
import com.example.rebate.web.dto.CollectCustomsRequest;
import com.example.rebate.web.dto.CollectInvoiceRequest;
import com.example.rebate.web.dto.CollectResponse;
import com.example.rebate.web.dto.DeclareRequest;
import com.example.rebate.web.dto.ExplainRequest;
import com.example.rebate.web.dto.LateDocumentRequest;
import com.example.rebate.web.dto.LateEventView;
import com.example.rebate.web.dto.LibraryRequest;
import com.example.rebate.web.dto.RecalcResponse;
import com.example.rebate.web.dto.RequestIdRequest;
import com.example.rebate.web.dto.ReversalBalanceView;
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
    private final ReversalService reversalService;

    public RebateController(CollectionService collectionService,
                            CheckService checkService,
                            DoubtService doubtService,
                            DeclarationService declarationService,
                            TreasuryService treasuryService,
                            QueryService queryService,
                            IdempotencyService idempotency,
                            ReversalService reversalService) {
        this.collectionService = collectionService;
        this.checkService = checkService;
        this.doubtService = doubtService;
        this.declarationService = declarationService;
        this.treasuryService = treasuryService;
        this.queryService = queryService;
        this.idempotency = idempotency;
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

    // ------------------------------------------------------------------
    // 结案批次分批冲回（金额为正数整数分；写操作携带 expectedVersion，版本从 1 开始）
    // ------------------------------------------------------------------

    /**
     * 提交分批冲回单：仅引用结案批次内一张报关单及其冻结退税额，初为 PENDING。
     */
    @PostMapping("/api/batches/{batchNo}/reversals")
    public ReversalView submitReversal(@PathVariable String batchNo,
                                       @Valid @RequestBody ReversalSubmitRequest req) {
        return reversalService.submit(batchNo, req);
    }

    /**
     * 复核冲回单：由不同于提交人的复核人 APPROVE/REJECT；批准占用可冲额并产生追缴明细。
     */
    @PostMapping("/api/batches/{batchNo}/reversals/{reversalId}/review")
    public ReversalView reviewReversal(@PathVariable String batchNo,
                                       @PathVariable long reversalId,
                                       @Valid @RequestBody ReversalReviewRequest req) {
        return reversalService.review(batchNo, reversalId, req);
    }

    /**
     * 登记追缴到账回执：允许多笔部分到账，恰收齐转 COMPLETED。
     */
    @PostMapping("/api/batches/{batchNo}/reversals/{reversalId}/receipts")
    public ReversalView registerClawbackReceipt(@PathVariable String batchNo,
                                                @PathVariable long reversalId,
                                                @Valid @RequestBody ClawbackReceiptRequest req) {
        return reversalService.registerReceipt(batchNo, reversalId, req);
    }

    /**
     * 查询冲回单（含追缴流水号与逐笔回执、当前逐单余额快照）。
     */
    @GetMapping("/api/batches/{batchNo}/reversals/{reversalId}")
    public ReversalView getReversal(@PathVariable String batchNo,
                                    @PathVariable long reversalId) {
        return reversalService.getOrder(batchNo, reversalId);
    }

    /**
     * 查询冲回单全部追缴到账回执。
     */
    @GetMapping("/api/batches/{batchNo}/reversals/{reversalId}/receipts")
    public List<ReversalView.ReceiptView> listClawbackReceipts(@PathVariable String batchNo,
                                                               @PathVariable long reversalId) {
        return reversalService.listReceipts(batchNo, reversalId);
    }

    /**
     * 逐单可退余额台账查询（冻结/已占用/剩余可冲/已到账，金额单位：整数分）。
     */
    @GetMapping("/api/batches/{batchNo}/declarations/{declarationNo}/reversal-balance")
    public ReversalBalanceView reversalBalance(@PathVariable String batchNo,
                                               @PathVariable String declarationNo) {
        return reversalService.getBalance(batchNo, declarationNo);
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
