package com.example.starter.web;

import java.util.List;

import com.example.starter.service.MaintenancePlanService;
import com.example.starter.web.dto.ApiDtos.AttemptDispatchRequest;
import com.example.starter.web.dto.ApiDtos.AttemptView;
import com.example.starter.web.dto.ApiDtos.ObligationView;
import com.example.starter.web.dto.ApiDtos.ScanRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 维保履约义务接口：扫描派发义务（requestId 幂等）、义务与尝试轨迹查询、派发履约尝试。
 */
@RestController
@RequestMapping("/api/maintenance-obligations")
public class MaintenancePlanController {

    private final MaintenancePlanService planService;

    public MaintenancePlanController(MaintenancePlanService planService) {
        this.planService = planService;
    }

    /** 扫描派发履约义务：到期前24小时可派，同梯同周期唯一，重复扫描不多派 */
    @PostMapping("/scan")
    public ResponseEntity<String> scan(@Valid @RequestBody ScanRequest req) {
        return planService.scan(req);
    }

    /** 义务查询，可按电梯过滤 */
    @GetMapping
    public List<ObligationView> list(@RequestParam(required = false) Long elevatorId) {
        return planService.listObligations(elevatorId);
    }

    @GetMapping("/{id}")
    public ObligationView get(@PathVariable long id) {
        return planService.getObligation(id);
    }

    /** 义务的尝试轨迹 */
    @GetMapping("/{id}/attempts")
    public List<AttemptView> attempts(@PathVariable long id) {
        return planService.listAttempts(id);
    }

    /** 派发履约尝试（requestId 幂等 + expectedVersion 乐观锁） */
    @PostMapping("/{id}/dispatch")
    public ResponseEntity<String> dispatch(@PathVariable long id,
                                           @Valid @RequestBody AttemptDispatchRequest req) {
        return planService.dispatchAttempt(id, req);
    }
}
