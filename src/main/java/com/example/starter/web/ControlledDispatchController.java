package com.example.starter.web;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.starter.dto.ControlledCancelRequest;
import com.example.starter.dto.ControlledCancelResponse;
import com.example.starter.dto.ControlledOrderReceiveRequest;
import com.example.starter.dto.ControlledOrderReceiveResponse;
import com.example.starter.dto.ControlledPlanViewResponse;
import com.example.starter.dto.ControlledReplanRequest;
import com.example.starter.dto.ControlledReplanResponse;
import com.example.starter.dto.MinuteConfirmRequest;
import com.example.starter.dto.MinuteConfirmResponse;
import com.example.starter.service.ControlledPlanService;

import jakarta.validation.Valid;

/**
 * 受控分段执行模式 REST 入口（可选模式；旧入口行为不变）。
 */
@RestController
@RequestMapping("/api/curtail/controlled-orders")
public class ControlledDispatchController {

    private final ControlledPlanService controlledPlanService;

    public ControlledDispatchController(ControlledPlanService controlledPlanService) {
        this.controlledPlanService = controlledPlanService;
    }

    /** 登记受控分段指令：冻结基数、最终分配与斜率，生成完整阶梯；同 requestId 幂等。 */
    @PostMapping
    public ControlledOrderReceiveResponse register(
            @Valid @RequestBody ControlledOrderReceiveRequest request) {
        return controlledPlanService.register(request);
    }

    /** 整分钟回执：给齐全部参与机组且等于冻结阶梯才确认，缺一零更新。 */
    @PostMapping("/{orderNo}/minutes/{minuteNo}/confirm")
    public MinuteConfirmResponse confirmMinute(@PathVariable String orderNo,
                                               @PathVariable int minuteNo,
                                               @Valid @RequestBody MinuteConfirmRequest request) {
        return controlledPlanService.confirmMinute(orderNo, minuteNo, request);
    }

    /** 修改（重规划）：带 expectedVersion，成功版本加 1，旧版 409。 */
    @PostMapping("/{orderNo}/replans")
    public ControlledReplanResponse replan(@PathVariable String orderNo,
                                           @Valid @RequestBody ControlledReplanRequest request) {
        return controlledPlanService.replan(orderNo, request);
    }

    /** 取消暂停中的分段计划：释放机组占用，不自动恢复原功率。 */
    @PostMapping("/{orderNo}/cancel")
    public ControlledCancelResponse cancel(@PathVariable String orderNo,
                                           @Valid @RequestBody ControlledCancelRequest request) {
        return controlledPlanService.cancel(orderNo, request);
    }

    /** 查询当前分段计划：版本、状态、进度与完整冻结阶梯。 */
    @GetMapping("/{orderNo}/plan")
    public ControlledPlanViewResponse planView(@PathVariable String orderNo) {
        return controlledPlanService.planView(orderNo);
    }
}
