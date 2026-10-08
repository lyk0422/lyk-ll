package com.example.starter.web;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.example.starter.service.RerouteService;

/**
 * 在途改投入口：仅允许还未取得有效回执的在途联单，生成新的去向版本。
 */
@RestController
@RequestMapping("/api/manifests")
public class RerouteController {

    private final RerouteService rerouteService;

    public RerouteController(RerouteService rerouteService) {
        this.rerouteService = rerouteService;
    }

    /**
     * 改投请求体。expectedVersion 为当前去向版本，过期返回 409。
     */
    public record RerouteRequest(
            @NotBlank String requestId,
            @NotBlank String newFacilityCode,
            @NotNull @Positive Integer expectedVersion) {
    }

    @PostMapping("/{manifestId}/reroutes")
    @ResponseStatus(HttpStatus.CREATED)
    public RerouteService.RerouteResult reroute(@PathVariable long manifestId,
                                                @Valid @RequestBody RerouteRequest request) {
        return rerouteService.reroute(new RerouteService.RerouteCommand(
                request.requestId(), manifestId, request.newFacilityCode(), request.expectedVersion()));
    }
}
