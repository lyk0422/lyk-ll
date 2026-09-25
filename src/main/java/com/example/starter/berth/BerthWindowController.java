package com.example.starter.berth;

import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 靠泊窗口 REST API。
 */
@RestController
@RequestMapping("/api/berth-windows")
public class BerthWindowController {

    private final BerthWindowService service;

    public BerthWindowController(BerthWindowService service) {
        this.service = service;
    }

    /**
     * 提交靠泊窗口申请（requestId 幂等；改期通过 replacesRequestId 视为新申请）。
     */
    @PostMapping
    public WindowResponse apply(@Valid @RequestBody ApplyWindowRequest request) {
        return service.apply(request);
    }

    /**
     * 查询窗口。
     */
    @GetMapping("/{requestId}")
    public WindowResponse get(@PathVariable String requestId) {
        return service.get(requestId);
    }

    /**
     * 审批通过。
     */
    @PostMapping("/{requestId}/approve")
    public WindowResponse approve(@PathVariable String requestId) {
        return service.approve(requestId);
    }

    /**
     * 审批驳回（终态）。
     */
    @PostMapping("/{requestId}/reject")
    public WindowResponse reject(@PathVariable String requestId) {
        return service.reject(requestId);
    }

    /**
     * 申请人取消（终态）。
     */
    @PostMapping("/{requestId}/cancel")
    public WindowResponse cancel(@PathVariable String requestId) {
        return service.cancel(requestId);
    }

    /**
     * 登记离泊（终态）。
     */
    @PostMapping("/{requestId}/departure")
    public WindowResponse depart(@PathVariable String requestId) {
        return service.depart(requestId);
    }
}
