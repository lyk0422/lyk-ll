package com.example.starter.trace.web;

import com.example.starter.trace.dto.CreateHarvestBatchRequest;
import com.example.starter.trace.dto.HarvestBatchResponse;
import com.example.starter.trace.dto.RapidTestRequest;
import com.example.starter.trace.dto.RetestRequest;
import com.example.starter.trace.dto.TestSubmissionResponse;
import com.example.starter.trace.service.HarvestBatchService;
import com.example.starter.trace.service.ResidueTestService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 采收批与药残检测接口。
 */
@RestController
@RequestMapping("/api/harvest-batches")
public class HarvestBatchController {

    private final HarvestBatchService harvestBatchService;
    private final ResidueTestService residueTestService;

    public HarvestBatchController(HarvestBatchService harvestBatchService,
                                  ResidueTestService residueTestService) {
        this.harvestBatchService = harvestBatchService;
        this.residueTestService = residueTestService;
    }

    /**
     * 登记采收批（含蜂场贡献，比例合计恰 100.00）。
     */
    @PostMapping
    public HarvestBatchResponse createBatch(@Valid @RequestBody CreateHarvestBatchRequest request) {
        return harvestBatchService.createBatch(request);
    }

    /**
     * 查询采收批详情，含贡献与检测记录。
     */
    @GetMapping("/{batchId}")
    public HarvestBatchResponse getBatch(@PathVariable long batchId) {
        return harvestBatchService.getBatch(batchId);
    }

    /**
     * 提交药残快检；抑制率达到 50.00 判疑似并冻结。
     */
    @PostMapping("/{batchId}/rapid-tests")
    public TestSubmissionResponse submitRapidTest(@PathVariable long batchId,
                                                  @Valid @RequestBody RapidTestRequest request) {
        return residueTestService.submitRapidTest(batchId, request);
    }

    /**
     * 提交定量复检；须换样，合格解除冻结。
     */
    @PostMapping("/{batchId}/retests")
    public TestSubmissionResponse submitRetest(@PathVariable long batchId,
                                               @Valid @RequestBody RetestRequest request) {
        return residueTestService.submitRetest(batchId, request);
    }
}
