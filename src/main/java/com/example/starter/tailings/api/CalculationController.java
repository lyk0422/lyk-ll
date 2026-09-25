package com.example.starter.tailings.api;

import java.math.BigDecimal;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.starter.tailings.repo.CalculationRepository;
import com.example.starter.tailings.service.FloodRoutingService;

/**
 * 调洪演算：requestId 幂等；快照与判定口径可复算。
 */
@RestController
@RequestMapping("/api/calculations")
public class CalculationController {

    private final FloodRoutingService floodRoutingService;

    public CalculationController(FloodRoutingService floodRoutingService) {
        this.floodRoutingService = floodRoutingService;
    }

    /**
     * 调洪演算请求。
     */
    public record CalcBody(@NotBlank String requestId,
                           @NotNull Long pondId,
                           @NotNull BigDecimal currentLevel,
                           @NotNull BigDecimal designFloodVolume,
                           @NotNull BigDecimal dryBeachLength,
                           @NotNull BigDecimal phreaticDepth) {
    }

    @PostMapping
    public FloodRoutingService.CalcResult calculate(@Valid @RequestBody CalcBody body) {
        return floodRoutingService.calculate(new FloodRoutingService.CalcRequest(
                body.requestId(), body.pondId(), body.currentLevel(), body.designFloodVolume(),
                body.dryBeachLength(), body.phreaticDepth()));
    }

    @GetMapping("/{id}")
    public CalculationRepository.CalcRow get(@PathVariable long id) {
        return floodRoutingService.get(id);
    }

    @GetMapping("/{id}/recheck")
    public FloodRoutingService.RecheckResult recheck(@PathVariable long id) {
        return floodRoutingService.recheck(id);
    }
}
