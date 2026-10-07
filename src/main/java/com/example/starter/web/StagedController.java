package com.example.starter.web;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.starter.dto.StagedOrderReceiveRequest;
import com.example.starter.dto.StagedOrderReceiveResponse;
import com.example.starter.dto.StagedPlanCancelRequest;
import com.example.starter.dto.StagedPlanCancelResponse;
import com.example.starter.dto.StagedPlanDetailResponse;
import com.example.starter.dto.StagedPlanModifyRequest;
import com.example.starter.dto.StagedPlanModifyResponse;
import com.example.starter.dto.StagedReceiptRequest;
import com.example.starter.dto.StagedReceiptResponse;
import com.example.starter.service.DispatchService;
import com.example.starter.service.StagedPlanService;

import jakarta.validation.Valid;

/**
 * 受控分段执行模式 REST 入口（可选模式；旧入口行为不变）。
 */
@RestController
@RequestMapping("/api/curtail")
public class StagedController {

    private final DispatchService dispatchService;
    private final StagedPlanService stagedPlanService;

    public StagedController(DispatchService dispatchService, StagedPlanService stagedPlanService) {
        this.dispatchService = dispatchService;
        this.stagedPlanService = stagedPlanService;
    }

    /** 受控分段指令登记：冻结基数/最终分配/斜率并生成完整阶梯；任一机组期限内不可达全单拒绝。 */
    @PostMapping("/staged-orders")
    public StagedOrderReceiveResponse receiveStagedOrder(
            @Valid @RequestBody StagedOrderReceiveRequest request) {
        return dispatchService.receiveStagedOrder(request);
    }

    /** 分钟回执：给齐全部参与机组且等于冻结阶梯，整分钟一起确认；缺一机组零更新。 */
    @PostMapping("/staged-receipts")
    public StagedReceiptResponse confirmMinute(@Valid @RequestBody StagedReceiptRequest request) {
        return stagedPlanService.confirmMinute(request);
    }

    /** 修改分段计划：带 expectedVersion，成功版本加 1，旧版 409。 */
    @PostMapping("/staged-orders/{orderNo}/modify")
    public StagedPlanModifyResponse modify(@PathVariable String orderNo,
                                           @Valid @RequestBody StagedPlanModifyRequest request) {
        return stagedPlanService.modify(orderNo, request);
    }

    /** 取消暂停中的分段计划：释放机组占用，不自动恢复原功率。 */
    @PostMapping("/staged-orders/{orderNo}/cancel")
    public StagedPlanCancelResponse cancel(@PathVariable String orderNo,
                                           @Valid @RequestBody StagedPlanCancelRequest request) {
        return stagedPlanService.cancel(orderNo, request);
    }

    /** 分段计划详情：状态/进度/版本与逐分钟冻结阶梯，可复算。 */
    @GetMapping("/staged-orders/{orderNo}/plan")
    public StagedPlanDetailResponse detail(@PathVariable String orderNo) {
        return stagedPlanService.detail(orderNo);
    }
}
