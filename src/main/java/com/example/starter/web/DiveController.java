package com.example.starter.web;

import com.example.starter.service.ControlledDiveService;
import com.example.starter.service.DiveService;
import com.example.starter.service.IdempotencyService;
import com.example.starter.web.dto.ApplyRequest;
import com.example.starter.web.dto.ApplyResult;
import com.example.starter.web.dto.CloseRequest;
import com.example.starter.web.dto.ControlledPlanRequest;
import com.example.starter.web.dto.ControlledPlanResult;
import com.example.starter.web.dto.CorrectRequest;
import com.example.starter.web.dto.CorrectionResult;
import com.example.starter.web.dto.PlanRequest;
import com.example.starter.web.dto.RecordRequest;
import com.example.starter.web.dto.SurfaceRequest;
import com.example.starter.web.dto.VitalsRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 潜水作业 REST 入口：申请→方案→执行→出水观察→结案，并提供复算视图。
 */
@RestController
@RequestMapping("/api/dives")
public class DiveController {

    private final DiveService service;
    private final ControlledDiveService controlledService;

    public DiveController(DiveService service, ControlledDiveService controlledService) {
        this.service = service;
        this.controlledService = controlledService;
    }

    /** 作业申请 */
    @PostMapping("/apply")
    public ResponseEntity<?> apply(@Valid @RequestBody ApplyRequest req) {
        return toResponse(service.apply(req));
    }

    /** 减压方案生成/变更 */
    @PostMapping("/plans")
    public ResponseEntity<?> plan(@Valid @RequestBody PlanRequest req) {
        return toResponse(service.plan(req));
    }

    /** 水下记录（单人不生效，两人同阶段成对生效） */
    @PostMapping("/records")
    public ResponseEntity<?> record(@Valid @RequestBody RecordRequest req) {
        return toResponse(service.record(req));
    }

    /** 出水登记，进入观察期 */
    @PostMapping("/surface")
    public ResponseEntity<?> surface(@Valid @RequestBody SurfaceRequest req) {
        return toResponse(service.surface(req));
    }

    /** 观察期体征上报 */
    @PostMapping("/vitals")
    public ResponseEntity<?> vitals(@Valid @RequestBody VitalsRequest req) {
        return toResponse(service.vitals(req));
    }

    /** 结案（终态） */
    @PostMapping("/close")
    public ResponseEntity<?> close(@Valid @RequestBody CloseRequest req) {
        return toResponse(service.close(req));
    }

    /** 减压剖面、速率告警与累计时长台账复算（含反复潜水积分冻结项/结算/更正差异） */
    @GetMapping("/{requestId}/recompute")
    public Map<String, Object> recompute(@PathVariable String requestId) {
        return service.recompute(requestId);
    }

    /** 反复潜水积分受控方案（虚构积分模型，不用于真实潜水减压） */
    @PostMapping("/controlled-plans")
    public ResponseEntity<?> controlledPlan(@Valid @RequestBody ControlledPlanRequest req) {
        return toResponse(controlledService.controlledPlan(req));
    }

    /** 历史水下记录更正：已关闭潜次另记独立差异，不追改原记录 */
    @PostMapping("/corrections")
    public ResponseEntity<?> correct(@Valid @RequestBody CorrectRequest req) {
        return toResponse(controlledService.correct(req));
    }

    /** 查询潜水员反复潜水积分账户（当前积分按服务时钟派生） */
    @GetMapping("/scores/{diverCode}")
    public Map<String, Object> score(@PathVariable String diverCode) {
        return controlledService.scoreView(diverCode);
    }

    private static ResponseEntity<?> toResponse(IdempotencyService.Outcome<?> outcome) {
        return ResponseEntity.status(outcome.statusCode()).body(outcome.body());
    }
}
