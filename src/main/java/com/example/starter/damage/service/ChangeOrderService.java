package com.example.starter.damage.service;

import com.example.starter.damage.dto.ChangeOrderRequests.CreateChangeOrderRequest;
import com.example.starter.damage.dto.ChangeOrderRequests.ReviewChangeOrderRequest;
import com.example.starter.damage.dto.ChangeOrderRequests.WithdrawChangeOrderRequest;
import com.example.starter.damage.dto.ChangeOrderViews.ChangeOrderView;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 修理变更单幂等门面：写操作均以 requestId 幂等，
 * 指纹结构化覆盖入口、版本与变更行集合；同键同参重放首次结果，异参 409、失败不占键。
 * 候选、批复换版、资格快照与幂等记录同事务提交。
 */
@Service
public class ChangeOrderService {

    private final ChangeOrderTxService txService;
    private final IdempotencyExecutor idempotency;

    public ChangeOrderService(ChangeOrderTxService txService, IdempotencyExecutor idempotency) {
        this.txService = txService;
        this.idempotency = idempotency;
    }

    /** 创建变更单候选（幂等） */
    public ChangeOrderView create(long caseId, CreateChangeOrderRequest request) {
        return idempotency.execute("CREATE_CHANGE#" + caseId, request.requestId(), request,
                ChangeOrderView.class, () -> txService.create(caseId, request));
    }

    /** 复核变更单候选（幂等）；两名不同复核人均 AGREE 生效，任一 REJECT 驳回 */
    public ChangeOrderView review(long changeOrderId, ReviewChangeOrderRequest request) {
        return idempotency.execute("REVIEW_CHANGE#" + changeOrderId, request.requestId(), request,
                ChangeOrderView.class, () -> txService.review(changeOrderId, request));
    }

    /** 撤回待复核候选（幂等）；生效后不可撤回 */
    public ChangeOrderView withdraw(long changeOrderId, WithdrawChangeOrderRequest request) {
        return idempotency.execute("WITHDRAW_CHANGE#" + changeOrderId, request.requestId(), request,
                ChangeOrderView.class, () -> txService.withdraw(changeOrderId, request));
    }

    /** 变更单详情：每项前后金额与工法及前后总额 */
    public ChangeOrderView get(long changeOrderId) {
        return txService.get(changeOrderId);
    }

    /** 案件下变更单列表 */
    public List<ChangeOrderView> listForCase(long caseId) {
        return txService.listForCase(caseId);
    }
}
