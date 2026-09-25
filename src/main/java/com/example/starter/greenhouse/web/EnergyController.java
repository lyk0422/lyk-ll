package com.example.starter.greenhouse.web;

import com.example.starter.greenhouse.dto.BudgetAllocateRequest;
import com.example.starter.greenhouse.dto.BudgetResponse;
import com.example.starter.greenhouse.dto.CommandIssueRequest;
import com.example.starter.greenhouse.dto.CommandResponse;
import com.example.starter.greenhouse.service.EnergyService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 每日能耗预算分配与环控指令下发接口。
 */
@RestController
@RequestMapping("/api/greenhouse")
public class EnergyController {

    private final EnergyService service;

    public EnergyController(EnergyService service) {
        this.service = service;
    }

    /** 按分区分配某日（UTC）能耗预算。 */
    @PostMapping("/energy/budgets")
    @ResponseStatus(HttpStatus.CREATED)
    public BudgetResponse allocate(@Valid @RequestBody BudgetAllocateRequest req) {
        return service.allocate(req);
    }

    /** 指令下发：耗能指令同事务校验扣减预算，恰尽后仅通风；requestId 幂等。 */
    @PostMapping("/energy/commands")
    @ResponseStatus(HttpStatus.CREATED)
    public CommandResponse issue(@Valid @RequestBody CommandIssueRequest req) {
        return service.issue(req);
    }
}
