package com.example.starter.dye.web;

import com.example.starter.dye.service.ColorMatchService;
import com.example.starter.dye.service.DischargeJudgeService;
import com.example.starter.dye.service.FeedService;
import com.example.starter.dye.service.IdempotencyService;
import com.example.starter.dye.service.MasterDataService;
import com.example.starter.dye.service.RecipeService;
import com.example.starter.dye.service.RunScheduleService;
import com.example.starter.dye.web.dto.ArchiveRequest;
import com.example.starter.dye.web.dto.ColorMatchRequest;
import com.example.starter.dye.web.dto.ColorMatchResponse;
import com.example.starter.dye.web.dto.DischargeMeasureRequest;
import com.example.starter.dye.web.dto.DyestuffResponse;
import com.example.starter.dye.web.dto.DyestuffUpsertRequest;
import com.example.starter.dye.web.dto.FeedRequest;
import com.example.starter.dye.web.dto.FeedResponse;
import com.example.starter.dye.web.dto.JudgeRequest;
import com.example.starter.dye.web.dto.JudgeResponse;
import com.example.starter.dye.web.dto.RecipeCreateRequest;
import com.example.starter.dye.web.dto.RecipeNewVersionRequest;
import com.example.starter.dye.web.dto.RecipeResponse;
import com.example.starter.dye.web.dto.ScheduleRunRequest;
import com.example.starter.dye.web.dto.VatResponse;
import com.example.starter.dye.web.dto.VatRunResponse;
import com.example.starter.dye.web.dto.VatUpsertRequest;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 印染配方放大与缸差控制服务 REST 入口。
 */
@RestController
@RequestMapping("/api")
public class DyeController {

    private final MasterDataService masterDataService;
    private final RecipeService recipeService;
    private final ColorMatchService colorMatchService;
    private final RunScheduleService runScheduleService;
    private final FeedService feedService;
    private final DischargeJudgeService dischargeJudgeService;

    public DyeController(MasterDataService masterDataService, RecipeService recipeService,
                         ColorMatchService colorMatchService, RunScheduleService runScheduleService,
                         FeedService feedService, DischargeJudgeService dischargeJudgeService) {
        this.masterDataService = masterDataService;
        this.recipeService = recipeService;
        this.colorMatchService = colorMatchService;
        this.runScheduleService = runScheduleService;
        this.feedService = feedService;
        this.dischargeJudgeService = dischargeJudgeService;
    }

    // ---------------- 合成主数据 ----------------

    @PostMapping("/dyestuffs")
    public DyestuffResponse upsertDyestuff(@Valid @RequestBody DyestuffUpsertRequest request) {
        return masterDataService.upsertDyestuff(request);
    }

    @GetMapping("/dyestuffs/{code}")
    public DyestuffResponse getDyestuff(@PathVariable String code) {
        return masterDataService.getDyestuff(code);
    }

    @GetMapping("/dyestuffs")
    public List<DyestuffResponse> listDyestuffs() {
        return masterDataService.listDyestuffs();
    }

    @PostMapping("/vats")
    public VatResponse upsertVat(@Valid @RequestBody VatUpsertRequest request) {
        return masterDataService.upsertVat(request);
    }

    @GetMapping("/vats/{code}")
    public VatResponse getVat(@PathVariable String code) {
        return masterDataService.getVat(code);
    }

    // ---------------- 配方版本 ----------------

    @PostMapping("/recipes")
    public RecipeResponse createRecipe(@Valid @RequestBody RecipeCreateRequest request) {
        return recipeService.createDraft(request);
    }

    @PostMapping("/recipes/{recipeCode}/versions/{version}/publish")
    public RecipeResponse publishRecipe(@PathVariable String recipeCode, @PathVariable Integer version) {
        return recipeService.publish(recipeCode, version);
    }

    @PostMapping("/recipes/{recipeCode}/versions")
    public RecipeResponse newVersion(@PathVariable String recipeCode,
                                     @Valid @RequestBody RecipeNewVersionRequest request) {
        return recipeService.newVersion(recipeCode, request);
    }

    @GetMapping("/recipes/{recipeCode}/versions/{version}")
    public RecipeResponse getRecipe(@PathVariable String recipeCode, @PathVariable Integer version) {
        return recipeService.get(recipeCode, version);
    }

    @GetMapping("/recipes")
    public List<RecipeResponse> listRecipes() {
        return recipeService.list();
    }

    // ---------------- 流转 ----------------

    @PostMapping("/color-match")
    public ResponseEntity<?> colorMatch(@Valid @RequestBody ColorMatchRequest request) {
        return replayOrBody(colorMatchService.match(request));
    }

    @PostMapping("/runs")
    public VatRunResponse scheduleRun(@Valid @RequestBody ScheduleRunRequest request) {
        return runScheduleService.schedule(request);
    }

    @GetMapping("/runs/{runNo}")
    public VatRunResponse getRun(@PathVariable String runNo) {
        return runScheduleService.get(runNo);
    }

    @PostMapping("/feed")
    public ResponseEntity<?> feed(@Valid @RequestBody FeedRequest request) {
        return replayOrBody(feedService.feed(request));
    }

    @PostMapping("/discharge-measure")
    public DischargeJudgeService.VatRunMeasureView measure(@Valid @RequestBody DischargeMeasureRequest request) {
        return dischargeJudgeService.measure(request);
    }

    @PostMapping("/judge")
    public ResponseEntity<?> judge(@Valid @RequestBody JudgeRequest request) {
        return replayOrBody(dischargeJudgeService.judge(request));
    }

    @PostMapping("/archive")
    public ResponseEntity<?> archive(@Valid @RequestBody ArchiveRequest request) {
        return replayOrBody(dischargeJudgeService.archive(request));
    }

    /**
     * 幂等重放时原样输出首次结果 JSON 与其状态码（如投料超差首次为 422），首次执行正常序列化业务对象。
     */
    private ResponseEntity<?> replayOrBody(IdempotencyService.Outcome<?> outcome) {
        if (outcome.replay()) {
            return ResponseEntity.status(outcome.statusCode())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(outcome.cachedJson());
        }
        return ResponseEntity.status(outcome.statusCode()).body(outcome.body());
    }
}
