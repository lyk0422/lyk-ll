package com.example.starter.web;

import java.time.Instant;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.example.starter.service.FacilityService;

/**
 * 处理设施目录管理入口：停收区间与可接收品类改版（expectedVersion 乐观并发）。
 */
@RestController
@RequestMapping("/api/facilities")
public class FacilityController {

    private final FacilityService facilityService;

    public FacilityController(FacilityService facilityService) {
        this.facilityService = facilityService;
    }

    /**
     * 停收区间。UTC 精确时刻，半开区间 [startAt, endAt)：首端停收、恰 endAt 恢复。
     */
    public record SuspensionBody(
            @NotNull Instant startAt,
            @NotNull Instant endAt) {
    }

    /**
     * 设施改版请求体。字段缺省表示该项不变；clearSuspension 为 true 时清除停收配置。
     */
    public record RevisionRequest(
            @NotNull String requestId,
            @NotNull @Positive Integer expectedVersion,
            Boolean active,
            List<String> categories,
            @Valid SuspensionBody suspension,
            Boolean clearSuspension) {
    }

    @PostMapping("/{facilityCode}/revisions")
    @ResponseStatus(HttpStatus.CREATED)
    public FacilityService.RevisionResult revise(@PathVariable String facilityCode,
                                                 @Valid @RequestBody RevisionRequest request) {
        return facilityService.revise(new FacilityService.RevisionCommand(
                request.requestId(),
                facilityCode,
                request.expectedVersion(),
                request.active(),
                request.categories(),
                request.suspension() == null ? null
                        : new FacilityService.SuspensionWindow(
                                request.suspension().startAt(), request.suspension().endAt()),
                Boolean.TRUE.equals(request.clearSuspension())));
    }
}
