package com.example.starter.web;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.example.starter.service.ManifestService;

/**
 * 联单在途改投与去向/迟到证据链查询入口。
 */
@RestController
@RequestMapping("/api/manifests")
public class ManifestController {

    private final ManifestService manifestService;

    public ManifestController(ManifestService manifestService) {
        this.manifestService = manifestService;
    }

    /**
     * 改投请求体。expectedVersion 为当前去向版本，过期返回 409。
     */
    public record RerouteRequest(
            @NotBlank String requestId,
            @NotBlank String facilityCode,
            @NotNull @Positive Integer expectedVersion) {
    }

    @PostMapping("/{manifestId}/reroutes")
    @ResponseStatus(HttpStatus.CREATED)
    public ManifestService.RerouteResult reroute(@PathVariable long manifestId,
                                                 @Valid @RequestBody RerouteRequest request) {
        return manifestService.reroute(manifestId, request.requestId(),
                request.facilityCode(), request.expectedVersion());
    }

    @GetMapping("/{manifestId}")
    public ManifestService.ManifestDetail detail(@PathVariable long manifestId) {
        return manifestService.detail(manifestId);
    }
}
