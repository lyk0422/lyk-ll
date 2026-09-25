package com.example.starter.tailings.api;

import java.math.BigDecimal;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.starter.tailings.service.PondService;

/**
 * 尾矿库注册与查询。
 */
@RestController
@RequestMapping("/api/ponds")
public class PondController {

    private final PondService pondService;

    public PondController(PondService pondService) {
        this.pondService = pondService;
    }

    /**
     * 库容曲线点入参：水位(米)、库容(立方米)。
     */
    public record CurvePointBody(@NotNull BigDecimal waterLevel, @NotNull BigDecimal capacity) {
    }

    /**
     * 注册尾矿库请求。
     */
    public record CreatePondBody(@NotBlank String name,
                                 @NotNull BigDecimal maxFloodLevel,
                                 @NotNull BigDecimal downstreamSafeDischarge,
                                 @NotNull BigDecimal dryBeachMin,
                                 @NotNull BigDecimal phreaticLineMin,
                                 @NotEmpty List<@Valid CurvePointBody> curve) {
    }

    @PostMapping
    public PondService.PondView create(@Valid @RequestBody CreatePondBody body) {
        List<PondService.CurveInput> curve = body.curve().stream()
                .map(p -> new PondService.CurveInput(p.waterLevel(), p.capacity()))
                .toList();
        return pondService.create(body.name(), body.maxFloodLevel(), body.downstreamSafeDischarge(),
                body.dryBeachMin(), body.phreaticLineMin(), curve);
    }

    @GetMapping("/{id}")
    public PondService.PondView get(@PathVariable long id) {
        return pondService.get(id);
    }
}
