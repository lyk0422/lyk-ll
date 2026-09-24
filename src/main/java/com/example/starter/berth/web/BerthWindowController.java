package com.example.starter.berth.web;

import com.example.starter.berth.dto.CreateWindowRequest;
import com.example.starter.berth.dto.RescheduleRequest;
import com.example.starter.berth.dto.WindowResponse;
import com.example.starter.berth.service.BerthWindowService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 靠泊窗口 REST 接口。创建与改期按 requestId 幂等，故成功一律返回 200。
 */
@RestController
@RequestMapping("/api/berth-windows")
public class BerthWindowController {

    private final BerthWindowService service;

    public BerthWindowController(BerthWindowService service) {
        this.service = service;
    }

    /**
     * 创建靠泊窗口申请（幂等）。
     */
    @PostMapping
    public WindowResponse create(@Valid @RequestBody CreateWindowRequest request) {
        return service.create(request);
    }

    /**
     * 按 id 查询窗口。
     */
    @GetMapping("/{id}")
    public WindowResponse get(@PathVariable long id) {
        return service.get(id);
    }

    /**
     * 审批通过。
     */
    @PostMapping("/{id}/approve")
    public WindowResponse approve(@PathVariable long id) {
        return service.approve(id);
    }

    /**
     * 审批驳回。
     */
    @PostMapping("/{id}/reject")
    public WindowResponse reject(@PathVariable long id) {
        return service.reject(id);
    }

    /**
     * 申请人取消。
     */
    @PostMapping("/{id}/cancel")
    public WindowResponse cancel(@PathVariable long id) {
        return service.cancel(id);
    }

    /**
     * 登记离泊，窗口转为已完成。
     */
    @PostMapping("/{id}/depart")
    public WindowResponse depart(@PathVariable long id) {
        return service.depart(id);
    }

    /**
     * 已批准/已生效窗口改期：以新起止时刻发起新申请并取代原窗口。
     */
    @PostMapping("/{id}/reschedule")
    public WindowResponse reschedule(@PathVariable long id, @Valid @RequestBody RescheduleRequest request) {
        return service.reschedule(id, request);
    }
}
