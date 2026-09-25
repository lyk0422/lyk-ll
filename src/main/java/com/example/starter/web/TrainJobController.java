package com.example.starter.web;

import com.example.starter.service.TrainJobService;
import com.example.starter.web.dto.AcceptJobRequest;
import com.example.starter.web.dto.BrakeTestRequest;
import com.example.starter.web.dto.BrakeTestResponse;
import com.example.starter.web.dto.ClosedCarListResponse;
import com.example.starter.web.dto.ClosureResponse;
import com.example.starter.web.dto.ConsistChangeRequest;
import com.example.starter.web.dto.ConsistChangeResponse;
import com.example.starter.web.dto.ConsistCheckRequest;
import com.example.starter.web.dto.ConsistCheckResponse;
import com.example.starter.web.dto.ConsistResponse;
import com.example.starter.web.dto.DepartureResponse;
import com.example.starter.web.dto.JobResponse;
import com.example.starter.web.dto.PressureReadingRequest;
import com.example.starter.web.dto.PressureReadingResponse;
import com.example.starter.web.dto.RequestIdOnly;
import com.example.starter.web.dto.TestCurveResponse;
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
 * 列车制动机试验与摘挂核对 API。
 */
@RestController
@RequestMapping("/api/jobs")
public class TrainJobController {

    private final TrainJobService service;

    public TrainJobController(TrainJobService service) {
        this.service = service;
    }

    /**
     * 接入列车作业。
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public JobResponse accept(@Valid @RequestBody AcceptJobRequest request) {
        return service.accept(request);
    }

    /**
     * 摘挂变更。
     */
    @PostMapping("/{jobId}/consist-changes")
    public ConsistChangeResponse changeConsist(@PathVariable long jobId,
                                               @Valid @RequestBody ConsistChangeRequest request) {
        return service.changeConsist(jobId, request);
    }

    /**
     * 编组核对（与合成运统1比对）。
     */
    @PostMapping("/{jobId}/consist-checks")
    public ConsistCheckResponse checkConsist(@PathVariable long jobId,
                                             @Valid @RequestBody ConsistCheckRequest request) {
        return service.checkConsist(jobId, request);
    }

    /**
     * 制动试验（感度/安定）。
     */
    @PostMapping("/{jobId}/brake-tests")
    public BrakeTestResponse brakeTest(@PathVariable long jobId,
                                       @Valid @RequestBody BrakeTestRequest request) {
        return service.brakeTest(jobId, request);
    }

    /**
     * 发车确认。
     */
    @PostMapping("/{jobId}/departure-confirmations")
    public DepartureResponse confirmDeparture(@PathVariable long jobId,
                                              @Valid @RequestBody RequestIdOnly request) {
        return service.confirmDeparture(jobId, request);
    }

    /**
     * 销记（终态，重复销记幂等成功）。
     */
    @PostMapping("/{jobId}/closures")
    public ClosureResponse close(@PathVariable long jobId,
                                 @Valid @RequestBody RequestIdOnly request) {
        return service.close(jobId, request);
    }

    /**
     * 风压读数上报（销记后迟到数据只记迟到事件）。
     */
    @PostMapping("/{jobId}/pressure-readings")
    public PressureReadingResponse reportPressure(@PathVariable long jobId,
                                                  @Valid @RequestBody PressureReadingRequest request) {
        return service.reportPressure(jobId, request);
    }

    /**
     * 查询作业。
     */
    @GetMapping("/{jobId}")
    public JobResponse getJob(@PathVariable long jobId) {
        return service.getJob(jobId);
    }

    /**
     * 复算试验曲线。
     */
    @GetMapping("/{jobId}/test-curve")
    public TestCurveResponse getTestCurve(@PathVariable long jobId) {
        return service.getTestCurve(jobId);
    }

    /**
     * 复算关门车清单。
     */
    @GetMapping("/{jobId}/closed-cars")
    public ClosedCarListResponse getClosedCars(@PathVariable long jobId) {
        return service.getClosedCars(jobId);
    }

    /**
     * 复算编组版本。
     */
    @GetMapping("/{jobId}/consist")
    public ConsistResponse getConsist(@PathVariable long jobId) {
        return service.getConsist(jobId);
    }
}
