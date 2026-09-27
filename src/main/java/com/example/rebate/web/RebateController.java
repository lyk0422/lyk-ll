package com.example.rebate.web;

import com.example.rebate.service.CollectionService;
import com.example.rebate.service.ClawbackService;
import com.example.rebate.service.DeclarationService;
import com.example.rebate.service.DoubtService;
import com.example.rebate.service.IdempotencyService;
import com.example.rebate.service.IdempotentOutcome;
import com.example.rebate.service.QueryService;
import com.example.rebate.service.ServiceResult;
import com.example.rebate.service.TreasuryService;
import com.example.rebate.service.CheckService;
import com.example.rebate.web.dto.BatchView;
import com.example.rebate.web.dto.ClawbackBalanceView;
import com.example.rebate.web.dto.ClawbackDetailView;
import com.example.rebate.web.dto.ClawbackReceiptRequest;
import com.example.rebate.web.dto.ClawbackResultView;
import com.example.rebate.web.dto.ClawbackReviewRequest;
import com.example.rebate.web.dto.ClawbackSubmitRequest;
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
import com.example.rebate.web.dto.RecalcResponse;
import com.example.rebate.web.dto.RequestIdRequest;
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
    private final ClawbackService clawbackService;
    private final QueryService queryService;
    private final IdempotencyService idempotency;

    public RebateController(CollectionService collectionService,
                            CheckService checkService,
                            DoubtService doubtService,
                            DeclarationService declarationService,
                            TreasuryService treasuryService,
                            ClawbackService clawbackService,
                            QueryService queryService,
                            IdempotencyService idempotency) {
        this.collectionService = collectionService;
        this.checkService = checkService;
        this.doubtService = doubtService;
        this.declarationService = declarationService;
        this.treasuryService = treasuryService;
        this.clawbackService = clawbackService;
        this.queryService = queryService;
        this.idempotency = idempotency;
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
     * 结案后提交冲回单（待复核，不占额）。
     */
    @PostMapping("/api/batches/{batchNo}/clawbacks")
    public ResponseEntity<?> submitClawback(@PathVariable String batchNo,
                                            @Valid @RequestBody ClawbackSubmitRequest req) {
        Map<String, Object> payload = payload(req, "batchNo", batchNo);
        return idempotentAtomic("CLAWBACK_SUBMIT", req.requestId(), payload,
                () -> ServiceResult.ok(clawbackService.submit(batchNo, req)));
    }

    /**
     * 复核冲回单：批准占用额度并产生追缴明细，驳回不占额。
     */
    @PostMapping("/api/batches/{batchNo}/clawbacks/{clawbackId}/review")
    public ResponseEntity<?> reviewClawback(@PathVariable String batchNo,
                                            @PathVariable long clawbackId,
                                            @Valid @RequestBody ClawbackReviewRequest req) {
        Map<String, Object> payload = payload(req, "batchNo", batchNo, "clawbackId", clawbackId);
        return idempotentAtomic("CLAWBACK_REVIEW", req.requestId(), payload,
                () -> ServiceResult.ok(clawbackService.review(batchNo, clawbackId, req)));
    }

    /**
     * 登记追缴回执（独立流水号，多笔部分到账）。
     */
    @PostMapping("/api/batches/{batchNo}/clawbacks/{clawbackId}/receipts")
    public ResponseEntity<?> receiveClawback(@PathVariable String batchNo,
                                             @PathVariable long clawbackId,
                                             @Valid @RequestBody ClawbackReceiptRequest req) {
        Map<String, Object> payload = payload(req, "batchNo", batchNo, "clawbackId", clawbackId);
        return idempotentAtomic("CLAWBACK_RECEIVE", req.requestId(), payload,
                () -> ServiceResult.ok(clawbackService.receive(batchNo, clawbackId, req)));
    }

    /**
     * 查询冲回单详情（含追缴明细与回执）。
     */
    @GetMapping("/api/batches/{batchNo}/clawbacks/{clawbackId}")
    public ClawbackDetailView getClawback(@PathVariable String batchNo,
                                          @PathVariable long clawbackId) {
        return clawbackService.getDetail(batchNo, clawbackId);
    }

    /**
     * 逐单余额查询：冻结退税额、累计占用、剩余可冲额。
     */
    @GetMapping("/api/batches/{batchNo}/clawbacks/balance/{declarationNo}")
    public ClawbackBalanceView getClawbackBalance(@PathVariable String batchNo,
                                                  @PathVariable String declarationNo) {
        return clawbackService.getBalance(batchNo, declarationNo);
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

    /**
     * 新增写操作：占坑、业务变更（含版本）与幂等结果同事务（失败不占键）。
     */
    private ResponseEntity<?> idempotentAtomic(String opType, String requestId, Object payload,
                                               java.util.function.Supplier<ServiceResult> action) {
        IdempotentOutcome outcome = idempotency.runAtomic(opType, requestId, payload, action);
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
