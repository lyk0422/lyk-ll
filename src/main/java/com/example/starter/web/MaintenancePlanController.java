package com.example.starter.web;

import java.util.List;

import com.example.starter.service.MaintenancePlanService;
import com.example.starter.web.dto.ApiDtos.AttemptCompleteRequest;
import com.example.starter.web.dto.ApiDtos.AttemptEventView;
import com.example.starter.web.dto.ApiDtos.AttemptItemRequest;
import com.example.starter.web.dto.ApiDtos.AttemptView;
import com.example.starter.web.dto.ApiDtos.ObligationView;
import com.example.starter.web.dto.ApiDtos.PlanAttemptRequest;
import com.example.starter.web.dto.ApiDtos.PlanScanRequest;
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
 * 周期维保计划编排与救援抢占后续派接口。
 * 写操作携带 requestId 幂等与 expectedVersion 乐观版本；查询提供义务与尝试轨迹。
 */
@RestController
@RequestMapping("/api/maintenance-plans")
public class MaintenancePlanController {

    private final MaintenancePlanService planService;

    public MaintenancePlanController(MaintenancePlanService planService) {
        this.planService = planService;
    }

    /** 扫描生成到期履约义务（可带 requestId 重放；重复扫描不多派） */
    @PostMapping("/scan")
    public ResponseEntity<String> scan(@RequestBody(required = false) PlanScanRequest req) {
        return planService.scan(req != null ? req : new PlanScanRequest(null, null));
    }

    /** 派发一次履约尝试 */
    @PostMapping("/attempts")
    public ResponseEntity<String> dispatch(@Valid @RequestBody PlanAttemptRequest req) {
        return planService.dispatch(req);
    }

    /** 清单完成回报（携带 expectedVersion） */
    @PostMapping("/attempts/{id}/items")
    public ResponseEntity<String> reportItem(@PathVariable long id, @Valid @RequestBody AttemptItemRequest req) {
        return planService.reportItem(id, req);
    }

    /** 双人共同确认完成 */
    @PostMapping("/attempts/{id}/complete")
    public ResponseEntity<String> complete(@PathVariable long id, @Valid @RequestBody AttemptCompleteRequest req) {
        return planService.complete(id, req);
    }

    /** 查询电梯的履约义务 */
    @GetMapping
    public List<ObligationView> obligations(@RequestParam long elevatorId) {
        return planService.obligations(elevatorId);
    }

    @GetMapping("/obligations/{id}")
    public ObligationView obligation(@PathVariable long id) {
        return planService.obligation(id);
    }

    @GetMapping("/attempts/{id}")
    public AttemptView attempt(@PathVariable long id) {
        return planService.attempt(id);
    }

    @GetMapping("/obligations/{id}/attempts")
    public List<AttemptView> attempts(@PathVariable long id) {
        return planService.attempts(id);
    }

    @GetMapping("/attempts/{id}/timeline")
    public List<AttemptEventView> timeline(@PathVariable long id) {
        return planService.attemptTimeline(id);
    }
}
