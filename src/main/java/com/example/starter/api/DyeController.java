package com.example.starter.api;

import java.util.Map;

import jakarta.validation.Valid;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.starter.api.dto.ApproveCompensationRequest;
import com.example.starter.api.dto.ColorCheckRequest;
import com.example.starter.api.dto.ControlledFeedRequest;
import com.example.starter.api.dto.CreateRecipeRequest;
import com.example.starter.api.dto.FeedRequest;
import com.example.starter.api.dto.JudgeRequest;
import com.example.starter.api.dto.LateColorRequest;
import com.example.starter.api.dto.MergeLotsRequest;
import com.example.starter.api.dto.RegisterBatchRequest;
import com.example.starter.api.dto.RevisePotencyRequest;
import com.example.starter.api.dto.ScheduleLotRequest;
import com.example.starter.service.CompensationService;
import com.example.starter.service.DyeService;
import com.example.starter.service.ServiceResult;

/**
 * 印染配方服务 REST 入口。
 */
@RestController
@RequestMapping("/api")
public class DyeController {

    private final DyeService dyeService;
    private final CompensationService compensationService;

    public DyeController(DyeService dyeService, CompensationService compensationService) {
        this.dyeService = dyeService;
        this.compensationService = compensationService;
    }

    /** 创建配方草稿（v1）。 */
    @PostMapping("/recipes")
    public ResponseEntity<Map<String, Object>> createRecipe(@Valid @RequestBody CreateRecipeRequest req) {
        return toResponse(dyeService.createRecipe(req));
    }

    /** 发布冻结当前最新版本。 */
    @PostMapping("/recipes/{recipeCode}/publish")
    public ResponseEntity<Map<String, Object>> publishRecipe(@PathVariable String recipeCode) {
        return toResponse(dyeService.publishRecipe(recipeCode));
    }

    /** 修改已发布配方：生成新版本草稿，旧版本保留。 */
    @PostMapping("/recipes/{recipeCode}/revise")
    public ResponseEntity<Map<String, Object>> reviseRecipe(@PathVariable String recipeCode,
                                                            @Valid @RequestBody CreateRecipeRequest req) {
        return toResponse(dyeService.reviseRecipe(recipeCode, req));
    }

    /** 打小样对色（requestId 幂等）。 */
    @PostMapping("/color-checks")
    public ResponseEntity<Map<String, Object>> colorCheck(@Valid @RequestBody ColorCheckRequest req) {
        return toResponse(dyeService.colorCheck(req));
    }

    /** 排缸：占用染缸、绑定冻结配方版本（requestId 幂等，并发唯一受理）。 */
    @PostMapping("/lots")
    public ResponseEntity<Map<String, Object>> scheduleLot(@Valid @RequestBody ScheduleLotRequest req) {
        return toResponse(dyeService.scheduleLot(req));
    }

    /** 并缸：同配方同浴比才允许（requestId 幂等）。 */
    @PostMapping("/lots/merge")
    public ResponseEntity<Map<String, Object>> mergeLots(@Valid @RequestBody MergeLotsRequest req) {
        return toResponse(dyeService.mergeLots(req));
    }

    /** 投料：称量偏差校验与库存扣减同事务原子完成（requestId 幂等）。 */
    @PostMapping("/feedings")
    public ResponseEntity<Map<String, Object>> feed(@Valid @RequestBody FeedRequest req) {
        return toResponse(dyeService.feed(req));
    }

    /** 出缸判定：缸差台账、回修一次、降级（requestId 幂等）。 */
    @PostMapping("/judgments")
    public ResponseEntity<Map<String, Object>> judge(@Valid @RequestBody JudgeRequest req) {
        return toResponse(dyeService.judge(req));
    }

    /** 判定归档：终态，重复归档幂等成功。 */
    @PostMapping("/lots/{lotNo}/archive")
    public ResponseEntity<Map<String, Object>> archiveLot(@PathVariable String lotNo) {
        return toResponse(dyeService.archiveLot(lotNo));
    }

    /** 归档后迟到测色：只登记迟到事件。 */
    @PostMapping("/lots/{lotNo}/late-color")
    public ResponseEntity<Map<String, Object>> receiveLateColor(@PathVariable String lotNo,
                                                                @Valid @RequestBody LateColorRequest req) {
        return toResponse(dyeService.receiveLateColor(lotNo, req));
    }

    @GetMapping("/lots/{lotNo}")
    public ResponseEntity<Map<String, Object>> getLot(@PathVariable String lotNo) {
        return toResponse(dyeService.getLot(lotNo));
    }

    /** 复算配方版本、称量链与缸差台账。 */
    @GetMapping("/recipes/{recipeCode}/recalculate")
    public ResponseEntity<Map<String, Object>> recalculate(@PathVariable String recipeCode) {
        return toResponse(dyeService.recalculate(recipeCode));
    }

    /** 登记染料批次：效价系数/可用克重/有效截止，系数版本从 1 开始（requestId 幂等）。 */
    @PostMapping("/dye-batches")
    public ResponseEntity<Map<String, Object>> registerBatch(@Valid @RequestBody RegisterBatchRequest req) {
        return toResponse(compensationService.registerBatch(req));
    }

    /** 修订批次系数：expectedVersion 乐观锁，成功版本加 1，未投料补偿批准失效（requestId 幂等）。 */
    @PostMapping("/dye-batches/{batchNo}/potency")
    public ResponseEntity<Map<String, Object>> revisePotency(@PathVariable String batchNo,
                                                             @Valid @RequestBody RevisePotencyRequest req) {
        return toResponse(compensationService.revisePotency(batchNo, req));
    }

    @GetMapping("/dye-batches/{batchNo}")
    public ResponseEntity<Map<String, Object>> getBatch(@PathVariable String batchNo) {
        return toResponse(compensationService.getBatch(batchNo));
    }

    /** 补偿批准：冻结每种染料的批次、系数与补偿目标（requestId 幂等）。 */
    @PostMapping("/compensations")
    public ResponseEntity<Map<String, Object>> approveCompensation(
            @Valid @RequestBody ApproveCompensationRequest req) {
        return toResponse(compensationService.approveCompensation(req));
    }

    /** 补偿审批及证据查询：冻结明细与受控称量链。 */
    @GetMapping("/compensations/{approvalNo}")
    public ResponseEntity<Map<String, Object>> getCompensation(@PathVariable String approvalNo) {
        return toResponse(compensationService.getCompensation(approvalNo));
    }

    /** 受控投料：验证补偿版本/称量/批次有效期，原子扣减批次余量并写称量链（requestId 幂等）。 */
    @PostMapping("/controlled-feedings")
    public ResponseEntity<Map<String, Object>> controlledFeed(@Valid @RequestBody ControlledFeedRequest req) {
        return toResponse(compensationService.controlledFeed(req));
    }

    private ResponseEntity<Map<String, Object>> toResponse(ServiceResult result) {
        return ResponseEntity.status(result.status())
                .header("X-Idempotent-Replayed", String.valueOf(result.replayed()))
                .body(result.body());
    }
}
