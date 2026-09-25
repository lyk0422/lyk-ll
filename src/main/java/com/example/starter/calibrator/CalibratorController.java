package com.example.starter.calibrator;

import java.time.Instant;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 活度计与日检参考源登记接口。
 */
@RestController
@RequestMapping("/api")
public class CalibratorController {

    private final CalibratorService service;

    public CalibratorController(CalibratorService service) {
        this.service = service;
    }

    /**
     * 登记活度计请求。
     */
    public record CalibratorRequest(@NotBlank String code, @NotBlank String name) {
    }

    /**
     * 登记参考源请求。
     *
     * @param calibratorId 所属活度计
     * @param nuclideId    核素
     * @param activityMbq  标定活度，单位 MBq
     * @param measuredAt   标定时刻（UTC）
     */
    public record ReferenceSourceRequest(@NotNull Long calibratorId, @NotNull Long nuclideId,
                                         @Positive double activityMbq, @NotNull Instant measuredAt) {
    }

    /**
     * 登记活度计。
     */
    @PostMapping("/calibrators")
    @ResponseStatus(HttpStatus.CREATED)
    public CalibratorService.CalibratorView registerCalibrator(@Valid @RequestBody CalibratorRequest request) {
        return service.registerCalibrator(request.code(), request.name());
    }

    /**
     * 登记日检参考源。
     */
    @PostMapping("/reference-sources")
    @ResponseStatus(HttpStatus.CREATED)
    public CalibratorService.ReferenceSourceView registerReferenceSource(
            @Valid @RequestBody ReferenceSourceRequest request) {
        return service.registerReferenceSource(request.calibratorId(), request.nuclideId(),
                request.activityMbq(), request.measuredAt());
    }
}
