package com.example.starter.metro;

import com.example.starter.metro.dto.refund.AdjustmentIncludeRequest;
import com.example.starter.metro.dto.refund.AdjustmentIncludeResponse;
import com.example.starter.metro.dto.refund.AdjustmentSealRequest;
import com.example.starter.metro.dto.refund.AdjustmentSealResponse;
import com.example.starter.metro.dto.refund.CardCloseRequest;
import com.example.starter.metro.dto.refund.CardCloseResponse;
import com.example.starter.metro.dto.refund.RefundApplyRequest;
import com.example.starter.metro.dto.refund.RefundApplyResponse;
import com.example.starter.metro.dto.refund.RefundBalanceResponse;
import com.example.starter.metro.dto.refund.RefundConfirmRequest;
import com.example.starter.metro.dto.refund.RefundConfirmResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 已封账行程退款冲正 API：退款申请、双人确认、调账单纳入开放批次、
 * 调账批次封存、逐线路可退余额查询及票卡关闭（验证历史重放）。
 */
@RestController
@RequestMapping("/api/metro/refunds")
public class RefundController {

    private final RefundService refundService;

    public RefundController(RefundService refundService) {
        this.refundService = refundService;
    }

    /** 退款申请（创建 PENDING 退款单）。 */
    @PostMapping("/apply")
    public RefundApplyResponse apply(@Valid @RequestBody RefundApplyRequest request) {
        return refundService.apply(request);
    }

    /** 双人最终确认：生成独立负向调整明细与调账单。 */
    @PostMapping("/confirm")
    public RefundConfirmResponse confirm(@Valid @RequestBody RefundConfirmRequest request) {
        return refundService.confirm(request);
    }

    /** 调账单纳入唯一开放调账批次。 */
    @PostMapping("/adjustments/include")
    public AdjustmentIncludeResponse include(@Valid @RequestBody AdjustmentIncludeRequest request) {
        return refundService.include(request);
    }

    /** 调账批次封存（封存后只读）。 */
    @PostMapping("/adjustment-batches/seal")
    public AdjustmentSealResponse sealBatch(@Valid @RequestBody AdjustmentSealRequest request) {
        return refundService.sealBatch(request);
    }

    /** 逐线路可退余额查询。 */
    @GetMapping("/txns/{txnNo}/balance")
    public RefundBalanceResponse balance(@PathVariable String txnNo) {
        return refundService.balance(txnNo);
    }

    /** 关闭票卡（关闭不影响成功退款的历史重放）。 */
    @PostMapping("/cards/close")
    public CardCloseResponse closeCard(@Valid @RequestBody CardCloseRequest request) {
        return refundService.closeCard(request);
    }
}
