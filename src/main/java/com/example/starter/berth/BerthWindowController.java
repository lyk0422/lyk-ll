package com.example.starter.berth;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 靠泊窗口 REST 接口。
 */
@RestController
@RequestMapping("/api/berth-windows")
public class BerthWindowController {

    private final BerthWindowService service;

    public BerthWindowController(BerthWindowService service) {
        this.service = service;
    }

    /**
     * 创建靠泊窗口申请（按 requestId 幂等）。
     */
    @PostMapping
    public ResponseEntity<BerthWindowResponse> create(@Valid @RequestBody CreateBerthWindowRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(request));
    }

    @GetMapping("/{id}")
    public BerthWindowResponse get(@PathVariable long id) {
        return service.get(id);
    }

    /**
     * 审批通过。
     */
    @PostMapping("/{id}/approve")
    public BerthWindowResponse approve(@PathVariable long id) {
        return service.approve(id);
    }

    /**
     * 审批驳回。
     */
    @PostMapping("/{id}/reject")
    public BerthWindowResponse reject(@PathVariable long id) {
        return service.reject(id);
    }

    /**
     * 申请人取消。
     */
    @PostMapping("/{id}/cancel")
    public BerthWindowResponse cancel(@PathVariable long id) {
        return service.cancel(id);
    }

    /**
     * 登记离泊，窗口完成。
     */
    @PostMapping("/{id}/departure")
    public BerthWindowResponse departure(@PathVariable long id) {
        return service.complete(id);
    }

    /**
     * 已批准窗口改期：视为新申请，重新走完整冲突校验。
     */
    @PostMapping("/{id}/reschedule")
    public ResponseEntity<BerthWindowResponse> reschedule(@PathVariable long id,
            @Valid @RequestBody RescheduleRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.reschedule(id, request));
    }
}
