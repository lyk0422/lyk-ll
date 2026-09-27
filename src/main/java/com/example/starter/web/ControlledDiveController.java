package com.example.starter.web;

import com.example.starter.service.ControlledDiveService;
import com.example.starter.service.IdempotencyService;
import com.example.starter.web.dto.ControlledApplyRequest;
import com.example.starter.web.dto.ControlledPlanRequest;
import com.example.starter.web.dto.ControlledStartRequest;
import com.example.starter.web.dto.ControlledSurfaceRequest;
import com.example.starter.web.dto.RecordCorrectionRequest;
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
 * 受控反复潜水 REST 入口（虚构积分模型，不用于实际减压）：
 * 受控申请 → 受控方案 → 实际下潜 → 受控出水，并提供积分与更正查询。
 * 旧入口 {@code /api/dives} 语义保持不变。
 */
@RestController
@RequestMapping("/api/controlled-dives")
public class ControlledDiveController {

    private final ControlledDiveService service;

    public ControlledDiveController(ControlledDiveService service) {
        this.service = service;
    }

    /** 受控作业申请（启用积分控制）。 */
    @PostMapping("/apply")
    public ResponseEntity<?> apply(@Valid @RequestBody ControlledApplyRequest req) {
        return toResponse(service.controlledApply(req));
    }

    /** 受控方案：积分/间隔/观察/24h 门禁并冻结每人来源。 */
    @PostMapping("/plans")
    public ResponseEntity<?> plan(@Valid @RequestBody ControlledPlanRequest req) {
        return toResponse(service.controlledPlan(req));
    }

    /** 实际下潜：重查来源未变、占潜水员级槽。 */
    @PostMapping("/start")
    public ResponseEntity<?> start(@Valid @RequestBody ControlledStartRequest req) {
        return toResponse(service.controlledStart(req));
    }

    /** 受控出水：原子更新两人积分账与潜次记录。 */
    @PostMapping("/surface")
    public ResponseEntity<?> surface(@Valid @RequestBody ControlledSurfaceRequest req) {
        return toResponse(service.controlledSurface(req));
    }

    /** 更正已关闭历史潜次的水下记录：另记独立差异，不追改。 */
    @PostMapping("/corrections")
    public ResponseEntity<?> correct(@Valid @RequestBody RecordCorrectionRequest req) {
        return toResponse(service.correct(req));
    }

    /** 受控潜次积分冻结视图。 */
    @GetMapping("/{requestId}/points")
    public Map<String, Object> points(@PathVariable String requestId) {
        return service.controlledPointsView(requestId);
    }

    /** 潜次历史记录更正差异列表（受控/旧语义潜次均可查）。 */
    @GetMapping("/{requestId}/corrections")
    public java.util.List<Map<String, Object>> corrections(@PathVariable String requestId) {
        return service.listCorrections(requestId);
    }

    /** 潜水员积分账查询。 */
    @GetMapping("/divers/{diverCode}/points")
    public Map<String, Object> diverPoints(@PathVariable String diverCode) {
        return service.diverPointsView(diverCode);
    }

    private static ResponseEntity<?> toResponse(IdempotencyService.Outcome<?> outcome) {
        return ResponseEntity.status(outcome.statusCode()).body(outcome.body());
    }
}
