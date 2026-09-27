package com.example.starter.metro;

import com.example.starter.metro.dto.AdjustmentBatchOpenRequest;
import com.example.starter.metro.dto.AdjustmentBatchOpenResponse;
import com.example.starter.metro.dto.AdjustmentBatchView;
import com.example.starter.metro.dto.AdjustmentIncludeRequest;
import com.example.starter.metro.dto.AdjustmentIncludeResponse;
import com.example.starter.metro.dto.AdjustmentSealRequest;
import com.example.starter.metro.dto.AdjustmentSealResponse;
import com.example.starter.metro.dto.CardCloseRequest;
import com.example.starter.metro.dto.CardCloseResponse;
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
 * 封账后冲正（退款）API：申请、双人确认、调账批次开放/纳入/封存与余额查询。
 */
@RestController
@RequestMapping("/api/metro/refunds")
public class RefundController {

    private final RefundService refundService;

    public RefundController(RefundService refundService) {
        this.refundService = refundService;
    }

    /** 退款申请。 */
    @PostMapping("/apply")
    public RefundApplyResponse apply(@Valid @RequestBody RefundApplyRequest request) {
        return refundService.apply(request);
    }

    /** 双人复核确认（同一退款单调用两次，复核人须不同）。 */
    @PostMapping("/confirm")
    public RefundConfirmResponse confirm(@Valid @RequestBody RefundConfirmRequest request) {
        return refundService.confirm(request);
    }

    /** 行程逐线路退款余额查询。 */
    @GetMapping("/balance/{txnNo}")
    public RefundBalanceResponse balance(@PathVariable String txnNo) {
        return refundService.balance(txnNo);
    }

    /** 开放调账批次。 */
    @PostMapping("/batches/open")
    public AdjustmentBatchOpenResponse openBatch(@Valid @RequestBody AdjustmentBatchOpenRequest request) {
        return refundService.openBatch(request);
    }

    /** 调整单纳入开放调账批次。 */
    @PostMapping("/batches/include")
    public AdjustmentIncludeResponse include(@Valid @RequestBody AdjustmentIncludeRequest request) {
        return refundService.include(request);
    }

    /** 封存调账批次。 */
    @PostMapping("/batches/seal")
    public AdjustmentSealResponse sealBatch(@Valid @RequestBody AdjustmentSealRequest request) {
        return refundService.sealBatch(request);
    }

    /** 查询调账批次。 */
    @GetMapping("/batches/{batchNo}")
    public AdjustmentBatchView batchView(@PathVariable String batchNo) {
        return refundService.batchView(batchNo);
    }

    /** 关闭原票卡。 */
    @PostMapping("/cards/close")
    public CardCloseResponse closeCard(@Valid @RequestBody CardCloseRequest request) {
        return refundService.closeCard(request);
    }
}
