package com.example.starter.api;

import com.example.starter.api.dto.CommandDtos;
import com.example.starter.service.EnergyService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 每日能耗预算与环控指令。
 */
@RestController
@RequestMapping("/api/energy")
public class EnergyController {

    private final EnergyService energyService;

    public EnergyController(EnergyService energyService) {
        this.energyService = energyService;
    }

    @PostMapping("/budgets")
    @ResponseStatus(HttpStatus.CREATED)
    public CommandDtos.BudgetResponse allocateBudget(@Valid @RequestBody CommandDtos.BudgetRequest request) {
        return energyService.allocateBudget(request);
    }

    @GetMapping("/budgets/{zoneCode}")
    public CommandDtos.BudgetResponse getBudget(
            @PathVariable String zoneCode,
            @RequestParam java.time.LocalDate date) {
        return energyService.getBudget(zoneCode, date);
    }

    @PostMapping("/commands")
    public CommandDtos.CommandResponse issueCommand(@Valid @RequestBody CommandDtos.CommandRequest request) {
        return energyService.issueCommand(request);
    }
}
