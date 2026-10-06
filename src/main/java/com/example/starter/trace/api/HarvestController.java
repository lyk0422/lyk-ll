package com.example.starter.trace.api;

import com.example.starter.trace.api.TraceDtos.HarvestBatchDetailResponse;
import com.example.starter.trace.api.TraceDtos.HarvestBatchResponse;
import com.example.starter.trace.api.TraceDtos.RapidTestRequest;
import com.example.starter.trace.api.TraceDtos.RapidTestResponse;
import com.example.starter.trace.api.TraceDtos.RegisterHarvestBatchRequest;
import com.example.starter.trace.api.TraceDtos.RetestRequest;
import com.example.starter.trace.api.TraceDtos.RetestResponse;
import com.example.starter.trace.service.HarvestService;
import com.example.starter.trace.service.TestService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 采收批与药残检测链接口。
 */
@RestController
@RequestMapping("/api/harvest-batches")
public class HarvestController {

    private final HarvestService harvestService;
    private final TestService testService;

    public HarvestController(HarvestService harvestService, TestService testService) {
        this.harvestService = harvestService;
        this.testService = testService;
    }

    /**
     * 登记采收批（按蜂场与花期，含贡献比例）。
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public HarvestBatchResponse register(@Valid @RequestBody RegisterHarvestBatchRequest request) {
        return harvestService.register(request);
    }

    /**
     * 查询采收批详情（含快检 / 复检历史）。
     */
    @GetMapping("/{batchId}")
    public HarvestBatchDetailResponse detail(@PathVariable Long batchId) {
        return harvestService.detail(batchId);
    }

    /**
     * 登记药残快检。
     */
    @PostMapping("/{batchId}/rapid-tests")
    public RapidTestResponse rapidTest(@PathVariable Long batchId,
                                       @Valid @RequestBody RapidTestRequest request) {
        return testService.rapidTest(batchId, request);
    }

    /**
     * 登记定量复检。
     */
    @PostMapping("/{batchId}/retests")
    public RetestResponse retest(@PathVariable Long batchId,
                                 @Valid @RequestBody RetestRequest request) {
        return testService.retest(batchId, request);
    }
}
