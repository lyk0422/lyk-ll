package com.example.starter.web;

import com.example.starter.service.MaintenancePlanService;
import com.example.starter.web.dto.ApiDtos.AttemptCompleteRequest;
import com.example.starter.web.dto.ApiDtos.AttemptItemsRequest;
import com.example.starter.web.dto.ApiDtos.AttemptStartRequest;
import com.example.starter.web.dto.ApiDtos.AttemptView;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 履约尝试接口：开始作业、清单回报、双人共同确认完成（均 requestId 幂等 + expectedVersion 乐观锁）。
 */
@RestController
@RequestMapping("/api/maintenance-attempts")
public class MaintenanceAttemptController {

    private final MaintenancePlanService planService;

    public MaintenanceAttemptController(MaintenancePlanService planService) {
        this.planService = planService;
    }

    @GetMapping("/{id}")
    public AttemptView get(@PathVariable long id) {
        return planService.getAttempt(id);
    }

    /** 开始作业：重查证书有效且无进行中救援 */
    @PostMapping("/{id}/start")
    public ResponseEntity<String> start(@PathVariable long id, @Valid @RequestBody AttemptStartRequest req) {
        return planService.startAttempt(id, req);
    }

    /** 清单回报：已做清单挂起后保留 */
    @PostMapping("/{id}/items")
    public ResponseEntity<String> items(@PathVariable long id, @Valid @RequestBody AttemptItemsRequest req) {
        return planService.reportItems(id, req);
    }

    /** 完成确认：全部清单完成并双人共同确认才登记有效维保记录；挂起旧尝试只记迟到 */
    @PostMapping("/{id}/complete")
    public ResponseEntity<String> complete(@PathVariable long id, @Valid @RequestBody AttemptCompleteRequest req) {
        return planService.completeAttempt(id, req);
    }
}
