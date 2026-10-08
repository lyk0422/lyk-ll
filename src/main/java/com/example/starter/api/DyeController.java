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
import com.example.starter.api.dto.RegisterBatchRequest;
import com.example.starter.api.dto.ReviseBatchRequest;
import com.example.starter.api.dto.ScheduleControlledRequest;
import com.example.starter.api.dto.ScheduleLotRequest;
import com.example.starter.api.dto.SplitLotRequest;
import com.example.starter.service.BatchCompensationService;
import com.example.starter.service.DyeService;
import com.example.starter.service.ServiceResult;

/**
 * 印染配方服务 REST 入口。
 */
@RestController
@RequestMapping("/api")
public class DyeController {

    private final DyeService dyeService;
    private final BatchCompensationService batchCompensationService;

    public DyeController(DyeService dyeService, BatchCompensationService batchCompensationService) {
        this.dyeService = dyeService;
        this.batchCompensationService = batchCompensationService;
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

    /** 登记染料批次（染料代码、正数效价系数 v1、可用克重、有效截止；requestId 幂等，expectedVersion=1）。 */
    @PostMapping("/batches")
    public ResponseEntity<Map<String, Object>> registerBatch(@Valid @RequestBody RegisterBatchRequest req) {
        return toResponse(batchCompensationService.registerBatch(req));
    }

    /** 修订批次系数：发布下一系数版本并使未投料补偿批准失效（requestId 幂等 + expectedVersion）。 */
    @PostMapping("/batches/{batchNo}/revise")
    public ResponseEntity<Map<String, Object>> reviseBatch(@PathVariable String batchNo,
                                                           @Valid @RequestBody ReviseBatchRequest req) {
        return toResponse(batchCompensationService.reviseBatch(req));
    }

    /** 染料批次证据查询：当前系数版本、余量、有效期及系数版本历史。 */
    @GetMapping("/batches/{batchNo}")
    public ResponseEntity<Map<String, Object>> getBatch(@PathVariable String batchNo) {
        return toResponse(batchCompensationService.getBatch(batchNo));
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

    /** 受控排缸：绑定冻结配方版本且投料前必须办理效价补偿批准（requestId 幂等）。 */
    @PostMapping("/lots/controlled")
    public ResponseEntity<Map<String, Object>> scheduleControlledLot(
            @Valid @RequestBody ScheduleControlledRequest req) {
        return toResponse(dyeService.scheduleControlledLot(req));
    }

    /** 受控缸次效价补偿审批：冻结批次、系数版本与补偿目标（requestId 幂等 + expectedVersion）。 */
    @PostMapping("/compensations")
    public ResponseEntity<Map<String, Object>> approveCompensation(@Valid @RequestBody CompensationRequest req) {
        return toResponse(batchCompensationService.approveCompensation(req));
    }

    /** 补偿批准证据查询：冻结批次/系数/目标、状态与当前有效性。 */
    @GetMapping("/lots/{lotNo}/compensation")
    public ResponseEntity<Map<String, Object>> getCompensation(@PathVariable String lotNo) {
        return toResponse(batchCompensationService.getCompensation(lotNo));
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

    /** 分缸：已投料未判未归档未分缸的缸次拆到 2~4 个不同空闲缸（requestId 幂等 + expectedVersion）。 */
    @PostMapping("/lots/split")
    public ResponseEntity<Map<String, Object>> splitLot(@Valid @RequestBody SplitLotRequest req) {
        return toResponse(dyeService.splitLot(req));
    }

    /** 分缸谱系查询：来源只读，返回子缸编号、布重与逐染料守恒分摊耗用。 */
    @GetMapping("/lots/{lotNo}/lineage")
    public ResponseEntity<Map<String, Object>> getLineage(@PathVariable String lotNo) {
        return toResponse(dyeService.getLineage(lotNo));
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

    private ResponseEntity<Map<String, Object>> toResponse(ServiceResult result) {
        return ResponseEntity.status(result.status())
                .header("X-Idempotent-Replayed", String.valueOf(result.replayed()))
                .body(result.body());
    }
}
