package com.example.starter.flood.web;

import com.example.starter.flood.service.OrderService;
import com.example.starter.flood.web.dto.OrderActionRequest;
import com.example.starter.flood.web.dto.OrderView;
import com.example.starter.flood.web.dto.ReceiptRequest;
import jakarta.validation.Valid;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 泄流指令链：下达（随方案）→签收→执行→回执；支持超时巡检升级。
 */
@RestController
@RequestMapping("/api/orders/{orderNo}")
public class OrderController {

    private final OrderService orderService;

    public OrderController(OrderService orderService) {
        this.orderService = orderService;
    }

    @PostMapping("/sign")
    public OrderView sign(@PathVariable String orderNo,
                          @Valid @RequestBody OrderActionRequest request) {
        return orderService.sign(orderNo, request);
    }

    /**
     * 超时巡检：恰满签收时限未签收则升级值班领导。
     */
    @PostMapping("/timeout-check")
    public Map<String, Object> timeoutCheck(@PathVariable String orderNo) {
        boolean escalated = orderService.escalateIfTimeout(orderNo);
        return Map.of("orderNo", orderNo, "escalated", escalated);
    }

    @PostMapping("/execute")
    public OrderView execute(@PathVariable String orderNo,
                             @Valid @RequestBody OrderActionRequest request) {
        return orderService.execute(orderNo, request);
    }

    @PostMapping("/receipt")
    public OrderView receipt(@PathVariable String orderNo,
                             @Valid @RequestBody ReceiptRequest request) {
        return orderService.receipt(orderNo, request);
    }

    @GetMapping
    public OrderView get(@PathVariable String orderNo) {
        return orderService.get(orderNo);
    }
}
