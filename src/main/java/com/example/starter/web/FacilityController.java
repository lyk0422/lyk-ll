package com.example.starter.web;

import java.time.Instant;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.example.starter.service.FacilityService;

/**
 * 处理设施目录维护入口：可接收品类与 UTC 停收区间的版本化修改及查询。
 */
@RestController
@RequestMapping("/api/facilities")
public class FacilityController {

    private final FacilityService facilityService;

    public FacilityController(FacilityService facilityService) {
        this.facilityService = facilityService;
    }

    /**
     * 停收区间：UTC [startAt, endAt)，首端含、末端不含。
     */
    public record SuspensionRequest(
            @NotNull Instant startAt,
            @NotNull Instant endAt) {
    }

    /**
     * 设施修改请求体：全量替换可接收品类与停收区间；expectedVersion 过期返回 409。
     */
    public record FacilityUpdateRequest(
            @NotBlank String requestId,
            @NotNull @Positive Integer expectedVersion,
            @NotNull List<@NotBlank String> categories,
            @NotNull List<@Valid SuspensionRequest> suspensions) {
    }

    @PutMapping("/{code}")
    @ResponseStatus(HttpStatus.OK)
    public FacilityService.FacilityUpdateResult update(@PathVariable String code,
                                                       @Valid @RequestBody FacilityUpdateRequest request) {
        return facilityService.update(code, request.requestId(), request.expectedVersion(),
                request.categories(),
                request.suspensions().stream()
                        .map(s -> new FacilityService.SuspensionView(s.startAt(), s.endAt()))
                        .toList());
    }

    @GetMapping("/{code}")
    public FacilityService.FacilityView detail(@PathVariable String code) {
        return facilityService.detail(code);
    }
}
