package com.example.starter.damage.api;

import com.example.starter.damage.dto.ChangeOrderRequests.ChangeReviewRequest;
import com.example.starter.damage.dto.ChangeOrderRequests.CreateChangeOrderRequest;
import com.example.starter.damage.dto.ChangeOrderRequests.WithdrawChangeOrderRequest;
import com.example.starter.damage.dto.ChangeOrderViews.ChangeOrderView;
import com.example.starter.damage.service.ChangeOrderService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 修理变更单 API：中标批复后改工法/分报价的候选申请、双重复核切换与撤回。
 */
@RestController
public class ChangeOrderController {

    private final ChangeOrderService service;

    public ChangeOrderController(ChangeOrderService service) {
        this.service = service;
    }

    /** 申请修理变更候选（冻结原批复、全清单与资格版本快照） */
    @PostMapping("/api/cases/{caseId}/change-orders")
    @ResponseStatus(HttpStatus.CREATED)
    public ChangeOrderView create(@PathVariable long caseId,
                                  @Valid @RequestBody CreateChangeOrderRequest request) {
        return service.create(caseId, request);
    }

    /** 本案变更单列表（含每项前后金额与工法及总额） */
    @GetMapping("/api/cases/{caseId}/change-orders")
    public List<ChangeOrderView> listByCase(@PathVariable long caseId) {
        return service.listByCase(caseId);
    }

    /** 变更单详情 */
    @GetMapping("/api/change-orders/{changeOrderId}")
    public ChangeOrderView get(@PathVariable long changeOrderId) {
        return service.get(changeOrderId);
    }

    /** 复核变更候选；两名不同复核人均同意才切换新估价与批复 */
    @PostMapping("/api/change-orders/{changeOrderId}/reviews")
    public ChangeOrderView review(@PathVariable long changeOrderId,
                                  @Valid @RequestBody ChangeReviewRequest request) {
        return service.review(changeOrderId, request);
    }

    /** 撤回待生效候选；生效后不可撤回 */
    @PostMapping("/api/change-orders/{changeOrderId}/withdraw")
    public ChangeOrderView withdraw(@PathVariable long changeOrderId,
                                    @Valid @RequestBody WithdrawChangeOrderRequest request) {
        return service.withdraw(changeOrderId, request);
    }
}
