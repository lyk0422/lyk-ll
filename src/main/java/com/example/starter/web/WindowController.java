package com.example.starter.web;

import com.example.starter.service.WindowService;
import com.example.starter.web.dto.CreateWindowRequest;
import com.example.starter.web.dto.RescheduleRequest;
import com.example.starter.web.dto.WindowResponse;
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
 * 靠泊窗口 API：建窗（幂等）、审批、取消、登记离泊、改期、查询。
 */
@RestController
@RequestMapping("/api/windows")
public class WindowController {

    private final WindowService service;

    public WindowController(WindowService service) {
        this.service = service;
    }

    /**
     * 提交靠泊窗口申请。新建返回 201；同键同参重放返回 200 及首次结果。
     */
    @PostMapping
    public ResponseEntity<WindowResponse> create(@Valid @RequestBody CreateWindowRequest req) {
        var result = service.create(new WindowService.CreateCommand(
                req.requestId(), req.vesselName(), req.berthId(),
                req.startAt(), req.endAt(), req.draftM(), req.pilotsRequired()));
        HttpStatus status = result.created() ? HttpStatus.CREATED : HttpStatus.OK;
        return ResponseEntity.status(status).body(WindowResponse.from(result.window()));
    }

    /**
     * 审批通过。
     */
    @PostMapping("/{id}/approve")
    public WindowResponse approve(@PathVariable long id) {
        return WindowResponse.from(service.approve(id));
    }

    /**
     * 审批驳回。
     */
    @PostMapping("/{id}/reject")
    public WindowResponse reject(@PathVariable long id) {
        return WindowResponse.from(service.reject(id));
    }

    /**
     * 申请人取消。
     */
    @PostMapping("/{id}/cancel")
    public WindowResponse cancel(@PathVariable long id) {
        return WindowResponse.from(service.cancel(id));
    }

    /**
     * 登记离泊，窗口转为已完成。
     */
    @PostMapping("/{id}/complete")
    public WindowResponse complete(@PathVariable long id) {
        return WindowResponse.from(service.complete(id));
    }

    /**
     * 已批准窗口改期（重新走完整冲突校验）。
     */
    @PostMapping("/{id}/reschedule")
    public WindowResponse reschedule(@PathVariable long id,
            @Valid @RequestBody RescheduleRequest req) {
        return WindowResponse.from(service.reschedule(id, req.startAt(), req.endAt()));
    }

    /**
     * 查询窗口。
     */
    @GetMapping("/{id}")
    public WindowResponse get(@PathVariable long id) {
        return WindowResponse.from(service.get(id));
    }
}
