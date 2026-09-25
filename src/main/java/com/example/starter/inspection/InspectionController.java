package com.example.starter.inspection;

import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 抽样与快检接口。
 */
@RestController
@RequestMapping("/api/batches/{batchId}")
public class InspectionController {

    private final InspectionService inspectionService;

    public InspectionController(InspectionService inspectionService) {
        this.inspectionService = inspectionService;
    }

    /**
     * 抽样登记（幂等，按批次＋项目去重）。
     */
    @PostMapping("/samples")
    public SampleResponse sample(@PathVariable long batchId, @Valid @RequestBody SampleRequest request) {
        return inspectionService.sample(batchId, request);
    }

    /**
     * 快检判定（幂等）：抑制率 &gt;= 50% 判疑似并冻结，合格即放行（同事务）。
     */
    @PostMapping("/rapid-tests")
    public RapidTestResponse rapidTest(@PathVariable long batchId, @Valid @RequestBody RapidTestRequest request) {
        return inspectionService.rapidTest(batchId, request);
    }
}
