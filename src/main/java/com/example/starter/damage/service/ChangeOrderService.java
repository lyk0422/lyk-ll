package com.example.starter.damage.service;

import com.example.starter.damage.dto.ChangeOrderRequests.ChangeReviewRequest;
import com.example.starter.damage.dto.ChangeOrderRequests.CreateChangeOrderRequest;
import com.example.starter.damage.dto.ChangeOrderRequests.WithdrawChangeOrderRequest;
import com.example.starter.damage.dto.ChangeOrderViews.ChangeOrderView;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 修理变更单幂等门面：写操作均以 requestId 幂等，
 * 指纹结构化覆盖入口、版本与全部业务字段；同键同参重放首次结果，异参 409、失败不占键。
 */
@Service
public class ChangeOrderService {

    private final ChangeOrderTxService txService;
    private final IdempotencyExecutor idempotency;

    public ChangeOrderService(ChangeOrderTxService txService, IdempotencyExecutor idempotency) {
        this.txService = txService;
        this.idempotency = idempotency;
    }

    /** 申请修理变更候选（幂等） */
    public ChangeOrderView create(long caseId, CreateChangeOrderRequest request) {
        return idempotency.execute("CHANGE_ORDER_CREATE#" + caseId, request.requestId(), request,
                ChangeOrderView.class, () -> txService.create(caseId, request));
    }

    /** 复核变更候选（幂等；两名不同复核人均同意才切换） */
    public ChangeOrderView review(long changeOrderId, ChangeReviewRequest request) {
        return idempotency.execute("CHANGE_ORDER_REVIEW#" + changeOrderId, request.requestId(), request,
                ChangeOrderView.class, () -> txService.review(changeOrderId, request));
    }

    /** 撤回待生效候选（幂等；生效后不可撤回） */
    public ChangeOrderView withdraw(long changeOrderId, WithdrawChangeOrderRequest request) {
        return idempotency.execute("CHANGE_ORDER_WITHDRAW#" + changeOrderId, request.requestId(), request,
                ChangeOrderView.class, () -> txService.withdraw(changeOrderId, request));
    }

    /** 变更单详情：每项前后金额与工法及总额 */
    public ChangeOrderView get(long changeOrderId) {
        return txService.get(changeOrderId);
    }

    /** 本案变更单列表 */
    public List<ChangeOrderView> listByCase(long caseId) {
        return txService.listByCase(caseId);
    }
}
