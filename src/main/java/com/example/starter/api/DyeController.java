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

import com.example.starter.api.dto.ColorCheckRequest;
import com.example.starter.api.dto.CompensationRequest;
import com.example.starter.api.dto.CreateRecipeRequest;
import com.example.starter.api.dto.FeedRequest;
import com.example.starter.api.dto.JudgeRequest;
import com.example.starter.api.dto.LateColorRequest;
import com.example.starter.api.dto.MergeLotsRequest;
import com.example.starter.api.dto.RegisterDyeLotRequest;
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

    /** 染料批次登记：染料代码、正数效价系数、可用克重、有效截止（requestId 幂等）。 */
    @PostMapping("/dye-lots")
    public ResponseEntity<Map<String, Object>> registerDyeLot(@Valid @RequestBody RegisterDyeLotRequest req) {
        return toResponse(compensationService.registerDyeLot(req));
    }

    /** 批次系数修订：expectedVersion 乐观锁，未投料批准失效（requestId 幂等）。 */
    @PostMapping("/dye-lots/{lotCode}/revise-potency")
    public ResponseEntity<Map<String, Object>> revisePotency(@PathVariable String lotCode,
                                                             @Valid @RequestBody RevisePotencyRequest req) {
        return toResponse(compensationService.revisePotency(lotCode, req));
    }

    /** 染料批次详情查询。 */
    @GetMapping("/dye-lots/{lotCode}")
    public ResponseEntity<Map<String, Object>> getDyeLot(@PathVariable String lotCode) {
        return toResponse(compensationService.getDyeLot(lotCode));
    }

    /** 补偿审批：受控缸次投料前冻结批次、系数与补偿目标（requestId 幂等）。 */
    @PostMapping("/compensations")
    public ResponseEntity<Map<String, Object>> createApproval(@Valid @RequestBody CompensationRequest req) {
        return toResponse(compensationService.createApproval(req));
    }

    /** 补偿证据查询：缸次最近一次补偿批准及冻结明细。 */
    @GetMapping("/lots/{lotNo}/compensation")
    public ResponseEntity<Map<String, Object>> getCompensation(@PathVariable String lotNo) {
        return toResponse(compensationService.getCompensation(lotNo));
    }

    private ResponseEntity<Map<String, Object>> toResponse(ServiceResult result) {
        return ResponseEntity.status(result.status())
                .header("X-Idempotent-Replayed", String.valueOf(result.replayed()))
                .body(result.body());
    }
}
