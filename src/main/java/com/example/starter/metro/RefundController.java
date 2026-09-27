package com.example.starter.metro;

import com.example.starter.metro.dto.AdjustSealRequest;
import com.example.starter.metro.dto.AdjustSealResponse;
import com.example.starter.metro.dto.RefundApplyRequest;
import com.example.starter.metro.dto.RefundApplyResponse;
import com.example.starter.metro.dto.RefundBalanceResponse;
import com.example.starter.metro.dto.RefundConfirmRequest;
import com.example.starter.metro.dto.RefundConfirmResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 封账后退款冲正 API：申请、双人确认、调账封存与逐线路余额查询。
 * 原流水与封账合计只读，冲正以独立负向调整明细体现。
 */
@RestController
@RequestMapping("/api/metro/refunds")
public class RefundController {

    private final RefundService refundService;

    public RefundController(RefundService refundService) {
        this.refundService = refundService;
    }

    /** 退款申请（引用已封账且按实结算行程）。 */
    @PostMapping("/apply")
    public RefundApplyResponse apply(@Valid @RequestBody RefundApplyRequest request) {
        return refundService.apply(request);
    }

    /** 双人复核确认（两名不同复核人，携带 expectedVersion）。 */
    @PostMapping("/confirm")
    public RefundConfirmResponse confirm(@Valid @RequestBody RefundConfirmRequest request) {
        return refundService.confirm(request);
    }

    /** 调账批次封存（封存后只读）。 */
    @PostMapping("/adjustments/seal")
    public AdjustSealResponse seal(@Valid @RequestBody AdjustSealRequest request) {
        return refundService.sealAdjustBatch(request);
    }

    /** 逐线路退款余额查询。 */
    @GetMapping("/balance/{txnNo}")
    public RefundBalanceResponse balance(@PathVariable String txnNo) {
        return refundService.balance(txnNo);
    }
}
