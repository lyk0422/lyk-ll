package com.example.starter.api;

import com.example.starter.api.dto.StrategyDtos;
import com.example.starter.service.StrategyService;
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
 * 气候策略：编制（含校验）、下发、查询。
 */
@RestController
@RequestMapping("/api/strategies")
public class StrategyController {

    private final StrategyService strategyService;

    public StrategyController(StrategyService strategyService) {
        this.strategyService = strategyService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public StrategyDtos.StrategyResponse draft(@Valid @RequestBody StrategyDtos.DraftRequest request) {
        return strategyService.draft(request);
    }

    @PostMapping("/{strategyCode}/dispatch")
    public StrategyDtos.StrategyResponse dispatch(
            @PathVariable String strategyCode,
            @Valid @RequestBody StrategyDtos.DispatchRequest request) {
        return strategyService.dispatch(strategyCode, request);
    }

    @GetMapping("/{strategyCode}")
    public StrategyDtos.StrategyResponse get(@PathVariable String strategyCode) {
        return strategyService.get(strategyCode);
    }
}
