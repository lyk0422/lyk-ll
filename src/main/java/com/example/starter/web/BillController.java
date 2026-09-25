package com.example.starter.web;

import java.math.BigDecimal;
import java.util.List;

import com.example.starter.domain.Models.BillView;
import com.example.starter.domain.Models.Party;
import com.example.starter.service.BillingService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 分保账单接口：出账、确认、异议、差错调整单、查询复算。
 */
@RestController
@RequestMapping("/api/bills")
public class BillController {

    private final BillingService billingService;

    public BillController(BillingService billingService) {
        this.billingService = billingService;
    }

    /** 出账请求。 */
    public record GenerateBillRequest(@NotBlank String requestId, @NotBlank String treatyNo,
                                      @NotBlank String quarter) {
    }

    /** 确认请求。 */
    public record ConfirmBillRequest(@NotBlank String requestId, @NotNull Party party) {
    }

    /** 异议明细。 */
    public record DisputeItem(long billItemId, @NotBlank String reason) {
    }

    /** 异议请求。 */
    public record DisputeBillRequest(@NotBlank String requestId,
                                     @NotEmpty List<@Valid DisputeItem> items) {
    }

    /** 差错调整单请求。 */
    public record AdjustBillRequest(@NotBlank String requestId, @NotBlank String quarter,
                                    @NotNull BigDecimal premiumAdjustment,
                                    @NotNull BigDecimal claimRecoveryAdjustment,
                                    @NotBlank String reason) {
    }

    @PostMapping("/generate")
    public BillView generate(@RequestBody @Valid GenerateBillRequest request) {
        return billingService.generate(request.requestId(), request.treatyNo(), request.quarter());
    }

    @PostMapping("/{billNo}/confirm")
    public BillView confirm(@PathVariable String billNo,
                            @RequestBody @Valid ConfirmBillRequest request) {
        return billingService.confirm(request.requestId(), billNo, request.party());
    }

    @PostMapping("/{billNo}/disputes")
    public BillingService.DisputeResult dispute(@PathVariable String billNo,
                                                @RequestBody @Valid DisputeBillRequest request) {
        List<BillingService.DisputeItemInput> items = request.items().stream()
                .map(i -> new BillingService.DisputeItemInput(i.billItemId(), i.reason()))
                .toList();
        return billingService.dispute(request.requestId(), billNo, items);
    }

    @PostMapping("/{billNo}/adjustments")
    public BillView adjust(@PathVariable String billNo,
                           @RequestBody @Valid AdjustBillRequest request) {
        return billingService.adjust(request.requestId(), billNo, request.quarter(),
                request.premiumAdjustment(), request.claimRecoveryAdjustment(), request.reason());
    }

    @GetMapping("/{billNo}")
    public BillView getBill(@PathVariable String billNo) {
        return billingService.getBill(billNo);
    }
}
