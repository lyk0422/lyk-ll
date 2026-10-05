package com.example.starter.flood.web;

import com.example.starter.flood.service.PlanService;
import com.example.starter.flood.web.dto.PlanRequest;
import com.example.starter.flood.web.dto.PlanView;
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
 * 泄流方案入口：孔口组合与总泄量校验，方案生效同时下达指令。
 */
@RestController
@RequestMapping("/api/plans")
public class PlanController {

    private final PlanService planService;

    public PlanController(PlanService planService) {
        this.planService = planService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public PlanView create(@Valid @RequestBody PlanRequest request) {
        return planService.create(request);
    }

    /**
     * 按方案编号查询（含版本号与取代时刻）。
     */
    @GetMapping("/{planNo}")
    public PlanView get(@PathVariable String planNo) {
        return planService.getByNo(planNo);
    }
}
