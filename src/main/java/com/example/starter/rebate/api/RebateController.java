package com.example.starter.rebate.api;

import com.example.starter.rebate.service.RebateService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

/**
 * 出口退税申报 REST 入口。
 */
@RestController
@RequestMapping("/api/rebate")
public class RebateController {

    private final RebateService service;

    public RebateController(RebateService service) {
        this.service = service;
    }

    /** 录入退税率文库版本（合成数据）。 */
    @PostMapping("/rates")
    public RateVersionResponse saveRate(@Valid @RequestBody SaveRateRequest request) {
        return service.saveRate(request.requestId(), request.hsCode(),
                request.effectiveDate(), request.rate());
    }

    /** 查询/复算申报日有效文库版本。 */
    @GetMapping("/rates/effective")
    public RateVersionResponse effectiveRate(@RequestParam String hsCode,
                                             @RequestParam LocalDate declareDate) {
        return service.effectiveRate(hsCode, declareDate);
    }

    /** 数据采集：报关单 + 进项发票配对。 */
    @PostMapping("/documents")
    public DocumentResponse collect(@Valid @RequestBody CollectDocumentRequest request) {
        return service.collect(request.requestId(), request.declarationNo(), request.invoiceNo(),
                request.hsCode(), request.fobRmb(), request.fobUsd());
    }

    /** 关联校验：建批次、关联单证、执行换汇成本校验并生成疑点。 */
    @PostMapping("/batches")
    public BatchResponse createBatch(@Valid @RequestBody CreateBatchRequest request) {
        return service.createBatch(request.requestId(), request.batchNo(),
                request.declareDate(), request.documentIds());
    }

    /** 查询批次（含校验明细、疑点，可复算）。 */
    @GetMapping("/batches/{batchId}")
    public BatchResponse getBatch(@PathVariable long batchId) {
        return service.getBatch(batchId);
    }

    /** 疑点补充说明（OPEN→EXPLAINED）。 */
    @PostMapping("/doubts/{doubtId}/explanations")
    public DoubtResponse explain(@PathVariable long doubtId,
                                 @Valid @RequestBody ExplainRequest request) {
        return service.explain(doubtId, request.requestId(), request.explanation());
    }

    /** 正式申报：复核确认疑点与批次冻结同一事务原子完成。 */
    @PostMapping("/batches/{batchId}/declare")
    public BatchResponse declare(@PathVariable long batchId,
                                 @Valid @RequestBody DeclareRequest request) {
        return service.declare(batchId, request.requestId(), request.reviewDoubtIds());
    }

    /** 审核通过。 */
    @PostMapping("/batches/{batchId}/approve")
    public ResponseEntity<BatchResponse> approve(@PathVariable long batchId) {
        return ResponseEntity.status(HttpStatus.OK).body(service.approve(batchId));
    }

    /** 撤批：整体作废并释放全部关联单证（仅 DRAFT）。 */
    @PostMapping("/batches/{batchId}/void")
    public BatchResponse voidBatch(@PathVariable long batchId) {
        return service.voidBatch(batchId);
    }

    /** 退库核销登记（批次+国库流水号唯一，差额恰 0.5% 含内一致）。 */
    @PostMapping("/batches/{batchId}/refunds")
    public RefundResponse refund(@PathVariable long batchId,
                                 @Valid @RequestBody RefundRequest request) {
        return service.refund(batchId, request.requestId(),
                request.treasurySerialNo(), request.receivedAmount());
    }

    /** 查询批次核销登记（差异可复算）。 */
    @GetMapping("/batches/{batchId}/refunds")
    public List<RefundResponse> refunds(@PathVariable long batchId) {
        return service.getRefunds(batchId);
    }

    /** 结案（终态，重复结案幂等成功）。 */
    @PostMapping("/batches/{batchId}/close")
    public BatchResponse close(@PathVariable long batchId,
                               @RequestParam String requestId) {
        return service.close(batchId, requestId);
    }

    /** 结案后迟到单证：仅登记事件。 */
    @PostMapping("/batches/{batchId}/late-documents")
    public LateEventResponse lateDocument(@PathVariable long batchId,
                                          @Valid @RequestBody LateDocumentRequest request) {
        return service.registerLate(batchId, request.requestId(), request.declarationNo(),
                request.invoiceNo(), request.payload());
    }

    /** 查询迟到单证事件。 */
    @GetMapping("/batches/{batchId}/late-documents")
    public List<LateEventResponse> lateDocuments(@PathVariable long batchId) {
        return service.getLateEvents(batchId);
    }
}
