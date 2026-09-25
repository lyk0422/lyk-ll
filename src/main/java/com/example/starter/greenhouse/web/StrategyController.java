package com.example.starter.greenhouse.web;

import com.example.starter.greenhouse.dto.ActivationResponse;
import com.example.starter.greenhouse.dto.StrategyActivateRequest;
import com.example.starter.greenhouse.dto.StrategyDraftRequest;
import com.example.starter.greenhouse.dto.StrategyResponse;
import com.example.starter.greenhouse.service.StrategyService;
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
 * 气候策略“编制→校验→下发”接口。
 */
@RestController
@RequestMapping("/api/greenhouse/strategies")
public class StrategyController {

    private final StrategyService service;

    public StrategyController(StrategyService service) {
        this.service = service;
    }

    /** 编制：创建 DRAFT 策略。 */
    @PostMapping("/draft")
    @ResponseStatus(HttpStatus.CREATED)
    public StrategyResponse draft(@Valid @RequestBody StrategyDraftRequest req) {
        return service.draft(req);
    }

    /** 校验：窗口与昼夜温差规则，通过后变为 VALIDATED。 */
    @PostMapping("/{strategyCode}/validate")
    public StrategyResponse validate(@PathVariable String strategyCode) {
        return service.validate(strategyCode);
    }

    /** 下发：requestId 幂等，同分区唯一生效、原子切换、30 分钟最小间隔。 */
    @PostMapping("/activate")
    public ActivationResponse activate(@Valid @RequestBody StrategyActivateRequest req) {
        return service.activate(req.strategyCode(), req.requestId());
    }

    @GetMapping("/{strategyCode}")
    public StrategyResponse get(@PathVariable String strategyCode) {
        return service.get(strategyCode);
    }
}
