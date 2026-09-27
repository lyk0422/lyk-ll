package com.example.starter.web;

import com.example.starter.service.IdempotencyService;
import com.example.starter.service.ResidualService;
import com.example.starter.web.dto.ControlledPlanRequest;
import com.example.starter.web.dto.ControlledSurfaceRequest;
import com.example.starter.web.dto.RecordCorrectionRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Map;

/**
 * 反复潜水余量积分联锁入口（虚构模型，仅供软件验证）。与旧潜水流程并存：
 * 受控方案、受控出水、历史记录更正及积分查询。
 */
@RestController
@RequestMapping("/api/residual")
public class ResidualController {

    private final ResidualService service;

    public ResidualController(ResidualService service) {
        this.service = service;
    }

    /** 受控方案：逐人计算当前积分、冻结来源、校验间隔/观察/24 小时门禁。 */
    @PostMapping("/plans")
    public ResponseEntity<?> plan(@Valid @RequestBody ControlledPlanRequest req) {
        return toResponse(service.controlledPlan(req));
    }

    /** 受控出水：原子更新两人积分账、本潜次流水并释放占用槽。 */
    @PostMapping("/surface")
    public ResponseEntity<?> surface(@Valid @RequestBody ControlledSurfaceRequest req) {
        return toResponse(service.controlledSurface(req));
    }

    /** 历史水下记录独立更正：只记差异，不追改已关闭潜次。 */
    @PostMapping("/corrections")
    public ResponseEntity<?> correct(@Valid @RequestBody RecordCorrectionRequest req) {
        return toResponse(service.correct(req));
    }

    /** 查询潜水员积分账户（出水残留积分与按时刻推导的当前积分）。 */
    @GetMapping("/divers/{diverCode}/account")
    public Map<String, Object> account(@PathVariable String diverCode,
                                       @RequestParam(required = false) Instant at) {
        return service.accountView(diverCode, at);
    }

    /** 查询潜次级积分视图：冻结快照、积分流水、占用槽与更正差异。 */
    @GetMapping("/dives/{requestId}")
    public Map<String, Object> diveResidual(@PathVariable String requestId) {
        return service.diveResidualView(requestId);
    }

    private static ResponseEntity<?> toResponse(IdempotencyService.Outcome<?> outcome) {
        return ResponseEntity.status(outcome.statusCode()).body(outcome.body());
    }
}
