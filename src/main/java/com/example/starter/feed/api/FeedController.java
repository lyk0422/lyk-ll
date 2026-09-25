package com.example.starter.feed.api;

import com.example.starter.feed.service.FeedComplianceService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 饲料合规 HTTP 入口。
 */
@RestController
@RequestMapping("/api/feed")
public class FeedController {

    private final FeedComplianceService service;

    public FeedController(FeedComplianceService service) {
        this.service = service;
    }

    /** 提交配方 → 合规校验 → 新版本。 */
    @PostMapping("/formulas/submit")
    public FormulaView submit(@Valid @RequestBody SubmitFormulaRequest request) {
        return service.submit(request);
    }

    /** 批准配方版本（批准后冻结）。 */
    @PostMapping("/formulas/approve")
    public FormulaView approve(@Valid @RequestBody ApproveFormulaRequest request) {
        return service.approve(request);
    }

    /** 查询配方版本轨迹（可复算版本冻结与校验结果）。 */
    @GetMapping("/formulas/{formulaCode}/versions")
    public List<FormulaView> versions(@PathVariable String formulaCode) {
        return service.listVersions(formulaCode);
    }

    /** 含药配方投产，批次进入休药期跟踪。 */
    @PostMapping("/production")
    public ProductionView produce(@Valid @RequestBody ProductionRequest request) {
        return service.produce(request);
    }

    /** 出栏申请。 */
    @PostMapping("/slaughter")
    public SlaughterView slaughter(@Valid @RequestBody SlaughterRequest request) {
        return service.slaughter(request);
    }

    /** 上报含药饲喂记录（出栏后迟到记录触发合规告警）。 */
    @PostMapping("/feeding-records")
    public FeedingRecordView recordFeeding(@Valid @RequestBody FeedingRecordRequest request) {
        return service.recordFeeding(request);
    }

    /** 建立合成养殖批次。 */
    @PostMapping("/batches")
    public BatchView createBatch(@Valid @RequestBody CreateBatchRequest request) {
        return service.createBatch(request);
    }

    /** 查询批次休药期跟踪与告警（可复算休药期窗口）。 */
    @GetMapping("/batches/{batchNo}")
    public BatchView batch(@PathVariable String batchNo) {
        return service.getBatch(batchNo);
    }
}
