package com.example.starter.wb.web;

import com.example.starter.wb.dto.CargoPlanResponse;
import com.example.starter.wb.dto.CargoPlanSubmitRequest;
import com.example.starter.wb.service.CargoPlanService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 集装器装配图接口：提交改图、查询当前图与历史图。
 */
@RestController
@RequestMapping("/api/flights/{flightId}/cargo-plan")
public class CargoPlanController {

    private final CargoPlanService service;

    public CargoPlanController(CargoPlanService service) {
        this.service = service;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public CargoPlanResponse submit(@PathVariable Long flightId,
                                    @Valid @RequestBody CargoPlanSubmitRequest request) {
        return service.submitPlan(flightId, request);
    }

    @GetMapping
    public CargoPlanResponse active(@PathVariable Long flightId) {
        return service.getActivePlan(flightId);
    }

    @GetMapping("/history")
    public List<CargoPlanResponse> history(@PathVariable Long flightId) {
        return service.listPlanHistory(flightId);
    }
}
