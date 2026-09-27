package com.example.starter.web;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.starter.dto.OrderBasisResponse;
import com.example.starter.dto.OrderDetailResponse;
import com.example.starter.dto.OrderReceiveRequest;
import com.example.starter.dto.OrderReceiveResponse;
import com.example.starter.dto.PlanOrderReceiveRequest;
import com.example.starter.dto.PlanOrderReceiveResponse;
import com.example.starter.dto.ReportRequest;
import com.example.starter.dto.ReportResponse;
import com.example.starter.dto.SettleResponse;
import com.example.starter.service.DispatchService;

import jakarta.validation.Valid;

/**
 * 限电分配与执行回报 REST 入口。
 */
@RestController
@RequestMapping("/api/curtail")
public class DispatchController {

    private final DispatchService dispatchService;

    public DispatchController(DispatchService dispatchService) {
        this.dispatchService = dispatchService;
    }

    /** 接收限电指令：接收即分摊下发，同 requestId 幂等。 */
    @PostMapping("/orders")
    public OrderReceiveResponse receive(@Valid @RequestBody OrderReceiveRequest request) {
        return dispatchService.receive(request);
    }

    /** 机组执行回报：按机组＋时刻去重，满 10 分钟未达标触发告警重分摊。 */
    @PostMapping("/reports")
    public ReportResponse report(@Valid @RequestBody ReportRequest request) {
        return dispatchService.report(request);
    }

    /** 日结封存：终态，重复封存幂等成功。 */
    @PostMapping("/orders/{orderNo}/settle")
    public SettleResponse settle(@PathVariable String orderNo) {
        return dispatchService.settle(orderNo);
    }

    /** 查询指令详情：分摊明细、执行曲线、告警链、迟到事件，可复算。 */
    @GetMapping("/orders/{orderNo}")
    public OrderDetailResponse detail(@PathVariable String orderNo) {
        return dispatchService.detail(orderNo);
    }

    /** 计划绑定模式受理：指定 UTC 日期、时段与已发布当前计划版本，冻结基数分摊下发，同 requestId 幂等。 */
    @PostMapping("/plan-orders")
    public PlanOrderReceiveResponse receivePlanOrder(@Valid @RequestBody PlanOrderReceiveRequest request) {
        return dispatchService.receivePlanOrder(request);
    }

    /** 指令基数证据：绑定版本、UTC 时段及各轮核定计划/实时可用/参与出力/分摊冻结值。 */
    @GetMapping("/orders/{orderNo}/basis")
    public OrderBasisResponse basis(@PathVariable String orderNo) {
        return dispatchService.basis(orderNo);
    }
}
