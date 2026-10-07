package com.example.starter.damage.api;

import com.example.starter.damage.dto.ChangeOrderRequests.CreateChangeOrderRequest;
import com.example.starter.damage.dto.ChangeOrderRequests.ReviewChangeOrderRequest;
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
 * 修理变更单 API：中标批复后改工法/分报价的候选、双重复核生效换版与撤回。
 */
@RestController
public class ChangeOrderController {

    private final ChangeOrderService service;

    public ChangeOrderController(ChangeOrderService service) {
        this.service = service;
    }

    /** 对已批复未完工修箱案创建变更单候选（1 至 10 个本案残损项的新工法与分报价） */
    @PostMapping("/api/cases/{caseId}/change-orders")
    @ResponseStatus(HttpStatus.CREATED)
    public ChangeOrderView create(@PathVariable long caseId,
                                  @Valid @RequestBody CreateChangeOrderRequest request) {
        return service.create(caseId, request);
    }

    /** 案件下变更单列表（每项前后金额与工法及前后总额） */
    @GetMapping("/api/cases/{caseId}/change-orders")
    public List<ChangeOrderView> listForCase(@PathVariable long caseId) {
        return service.listForCase(caseId);
    }

    /** 变更单详情 */
    @GetMapping("/api/change-orders/{changeOrderId}")
    public ChangeOrderView get(@PathVariable long changeOrderId) {
        return service.get(changeOrderId);
    }

    /** 复核：两名不同复核人均 AGREE 生效换版，任一 REJECT 驳回 */
    @PostMapping("/api/change-orders/{changeOrderId}/reviews")
    public ChangeOrderView review(@PathVariable long changeOrderId,
                                  @Valid @RequestBody ReviewChangeOrderRequest request) {
        return service.review(changeOrderId, request);
    }

    /** 撤回待复核候选；生效后不可撤回 */
    @PostMapping("/api/change-orders/{changeOrderId}/withdraw")
    public ChangeOrderView withdraw(@PathVariable long changeOrderId,
                                    @Valid @RequestBody WithdrawChangeOrderRequest request) {
        return service.withdraw(changeOrderId, request);
    }
}
