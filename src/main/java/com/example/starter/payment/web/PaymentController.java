package com.example.starter.payment.web;

import com.example.starter.payment.PayTransaction;
import com.example.starter.payment.ReconDifference;
import com.example.starter.payment.SettlementBill;
import com.example.starter.payment.service.AdjustmentService;
import com.example.starter.payment.service.PaymentService;
import com.example.starter.payment.service.ReconService;
import com.example.starter.payment.service.SettlementService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * 支付日切批次与清算对账 API。
 */
@RestController
@RequestMapping("/api")
public class PaymentController {

    private final PaymentService paymentService;
    private final SettlementService settlementService;
    private final AdjustmentService adjustmentService;
    private final ReconService reconService;

    public PaymentController(PaymentService paymentService, SettlementService settlementService,
                             AdjustmentService adjustmentService, ReconService reconService) {
        this.paymentService = paymentService;
        this.settlementService = settlementService;
        this.adjustmentService = adjustmentService;
        this.reconService = reconService;
    }

    /** 入账请求体。金额单位分，txnTime 为 UTC 瞬间（ISO-8601）。 */
    public record PostRequest(@NotBlank String requestId,
                              @NotBlank String serialNo,
                              @NotBlank String merchantNo,
                              @Positive long amount,
                              @NotNull Instant txnTime) {
    }

    /** 入账响应体。 */
    public record PostResponse(long transactionId, String requestId, String serialNo,
                               String merchantNo, long amount, long fee, String status,
                               Long batchId, LocalDate batchDate, boolean replayed) {
    }

    /** 调账请求体。 */
    public record AdjustRequest(@NotBlank String serialNo) {
    }

    /** 对账请求体：渠道对账文件条目列表（合成数据）。 */
    public record ReconcileRequest(@NotNull List<@Valid ChannelEntry> entries) {
    }

    public record ChannelEntry(@NotBlank String serialNo, @Positive long amount) {
    }

    /** 差异处理请求体。 */
    public record ResolveRequest(@NotBlank String resolution, @NotBlank String operator) {
    }

    /** 入账。 */
    @PostMapping("/transactions")
    public PostResponse post(@Valid @RequestBody PostRequest req) {
        PaymentService.PostResult result = paymentService.post(new PaymentService.PostCommand(
                req.requestId(), req.serialNo(), req.merchantNo(), req.amount(), req.txnTime()));
        PayTransaction txn = result.txn();
        return new PostResponse(txn.id(), txn.requestId(), txn.serialNo(), txn.merchantNo(),
                txn.amount(), txn.fee(), txn.status(), txn.batchId(), result.batchDate(),
                result.replayed());
    }

    /** 封账：生成该批次各商户清算单；重复封账返回首次结果。 */
    @PostMapping("/batches/{batchDate}/close")
    public List<SettlementBill> close(
            @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate batchDate) {
        return settlementService.close(batchDate);
    }

    /** 查询批次清算单。 */
    @GetMapping("/batches/{batchDate}/bills")
    public List<SettlementBill> bills(
            @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate batchDate) {
        return settlementService.billsOf(batchDate);
    }

    /** 调账：挂账交易转入下一开放批次。 */
    @PostMapping("/adjustments")
    public PayTransaction adjust(@Valid @RequestBody AdjustRequest req) {
        return adjustmentService.adjust(req.serialNo());
    }

    /** 对账：以渠道对账文件逐笔勾对，返回该批次全部差异。 */
    @PostMapping("/batches/{batchDate}/reconcile")
    public List<ReconDifference> reconcile(
            @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate batchDate,
            @Valid @RequestBody ReconcileRequest req) {
        List<ReconService.ChannelEntry> entries = req.entries().stream()
                .map(e -> new ReconService.ChannelEntry(e.serialNo(), e.amount()))
                .toList();
        return reconService.reconcile(batchDate, entries);
    }

    /** 查询批次差异。 */
    @GetMapping("/batches/{batchDate}/differences")
    public List<ReconDifference> differences(
            @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate batchDate) {
        return reconService.differencesOf(batchDate);
    }

    /** 差异处理（核销/补记/退款），同一差异不允许重复处理。 */
    @PostMapping("/differences/{id}/resolve")
    public ReconDifference resolve(@PathVariable long id,
                                   @Valid @RequestBody ResolveRequest req) {
        return reconService.resolve(id, req.resolution(), req.operator());
    }
}
