package com.example.starter.food.web;

import com.example.starter.food.service.FoodService;
import com.example.starter.food.service.IdempotencyService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 食品配方合规服务接口：
 * 提交 -> 审查 -> 批准 -> 备案 -> 量产上报；标准发布 -> 复审观察/逾期暂停；注销终态。
 */
@RestController
@RequestMapping("/api/food")
public class FoodController {

    private final FoodService service;
    private final ObjectMapper mapper;

    public FoodController(FoodService service, ObjectMapper mapper) {
        this.service = service;
        this.mapper = mapper;
    }

    /** 提交配方（requestId 幂等）。 */
    @PostMapping("/recipes")
    public ResponseEntity<Object> submit(@Valid @RequestBody Dtos.SubmitRecipeRequest req) {
        return toEntity(service.submitRecipe(req));
    }

    /** 对配方做合规审查，返回逐项判定明细；任一违例整单拒绝。 */
    @PostMapping("/recipes/{id}/review")
    public ResponseEntity<Object> review(@PathVariable("id") Long id,
                                         @Valid @RequestBody Dtos.ReviewRecipeRequest req) {
        return toEntity(service.reviewRecipe(req, id));
    }

    /** 批准配方并冻结版本快照。 */
    @PostMapping("/recipes/{id}/approve")
    public ResponseEntity<Object> approve(@PathVariable("id") Long id,
                                          @Valid @RequestBody Dtos.ActionRequest req) {
        return toEntity(service.approveRecipe(req.requestId(), id));
    }

    @GetMapping("/recipes/{id}")
    public Dtos.RecipeResponse getRecipe(@PathVariable("id") Long id) {
        return service.getRecipe(id);
    }

    /** 按已批准配方发起量产备案（同一产品同时刻仅一个有效版本，并发唯一成功）。 */
    @PostMapping("/filings")
    public ResponseEntity<Object> file(@Valid @RequestBody Dtos.FileFilingRequest req) {
        return toEntity(service.fileFiling(req));
    }

    /** 备案复审：恰第 90 个自然日（UTC 日界）受理，逾期自动暂停。 */
    @PostMapping("/filings/{id}/review")
    public ResponseEntity<Object> reviewFiling(@PathVariable("id") Long id,
                                               @Valid @RequestBody Dtos.ReviewFilingRequest req) {
        return toEntity(service.reviewFiling(req, id));
    }

    /** 注销备案（终态），重复注销幂等成功。 */
    @PostMapping("/filings/{id}/cancel")
    public ResponseEntity<Object> cancel(@PathVariable("id") Long id,
                                         @Valid @RequestBody Dtos.ActionRequest req) {
        return toEntity(service.cancelFiling(req.requestId(), id));
    }

    /** 主动触发逾期未复审暂停扫描。 */
    @PostMapping("/filings/sweep-overdue")
    public Dtos.SweepResponse sweep() {
        return service.sweepOverdue();
    }

    @GetMapping("/filings/{id}")
    public Dtos.FilingResponse getFiling(@PathVariable("id") Long id) {
        return service.getFiling(id);
    }

    /** 量产记录上报；备案注销/暂停等非 ACTIVE 状态一律拒绝。 */
    @PostMapping("/filings/{id}/production-reports")
    public ResponseEntity<Object> report(@PathVariable("id") Long id,
                                         @Valid @RequestBody Dtos.ProductionReportRequest req) {
        return toEntity(service.reportProduction(req, id));
    }

    /** 发布新标准版本；已备案的旧版本配方进入复审观察（90 个自然日窗口）。 */
    @PostMapping("/standards/publish")
    public ResponseEntity<Object> publish(@Valid @RequestBody Dtos.PublishStandardRequest req) {
        return toEntity(service.publishStandard(req, null));
    }

    private ResponseEntity<Object> toEntity(IdempotencyService.Result result) {
        Object body = result.body();
        if (body instanceof String json) {
            try {
                body = mapper.readTree(json);
            } catch (Exception e) {
                // 保持原始字符串
            }
        }
        return ResponseEntity.status(result.statusCode()).body(body);
    }
}
