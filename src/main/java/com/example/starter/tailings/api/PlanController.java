package com.example.starter.tailings.api;

import java.math.BigDecimal;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.starter.tailings.service.PlanService;

/**
 * 泄流方案：孔口组合与泄量，总泄量不得超过下游安全泄量（恰等合法）。
 */
@RestController
@RequestMapping("/api/plans")
public class PlanController {

    private final PlanService planService;

    public PlanController(PlanService planService) {
        this.planService = planService;
    }

    /**
     * 孔口入参。
     */
    public record OrificeBody(@NotBlank String name, @NotNull BigDecimal discharge) {
    }

    /**
     * 泄流方案请求。
     */
    public record PlanBody(@NotBlank String requestId,
                           @NotNull Long calcId,
                           @NotEmpty List<@Valid OrificeBody> orifices) {
    }

    @PostMapping
    public PlanService.PlanResult create(@Valid @RequestBody PlanBody body) {
        List<PlanService.OrificeInput> orifices = body.orifices().stream()
                .map(o -> new PlanService.OrificeInput(o.name(), o.discharge()))
                .toList();
        return planService.create(new PlanService.PlanRequest(body.requestId(), body.calcId(), orifices));
    }
}
