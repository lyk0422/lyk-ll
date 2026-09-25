package com.example.starter.damage.api;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.example.starter.damage.api.dto.ApproveRepairRequest;
import com.example.starter.damage.api.dto.CaseViews.CaseResponse;
import com.example.starter.damage.api.dto.CaseViews.RegisterResponse;
import com.example.starter.damage.api.dto.CompleteRepairRequest;
import com.example.starter.damage.api.dto.EstimateRequest;
import com.example.starter.damage.api.dto.RegisterRequest;
import com.example.starter.damage.api.dto.ReinspectRequest;
import com.example.starter.damage.api.dto.ReleaseRequest;
import com.example.starter.damage.api.dto.SurveyRequest;
import com.example.starter.damage.service.DamageCaseService;

import jakarta.validation.Valid;

/** 集装箱残损检验与修理放行 API。 */
@RestController
@RequestMapping("/api/damage-cases")
public class DamageCaseController {

    private final DamageCaseService service;

    public DamageCaseController(DamageCaseService service) {
        this.service = service;
    }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public RegisterResponse register(@Valid @RequestBody RegisterRequest request) {
        return service.register(request);
    }

    @PostMapping("/survey")
    public CaseResponse survey(@Valid @RequestBody SurveyRequest request) {
        return service.survey(request);
    }

    @PostMapping("/estimate")
    public CaseResponse estimate(@Valid @RequestBody EstimateRequest request) {
        return service.estimate(request);
    }

    @PostMapping("/approve-repair")
    public CaseResponse approveRepair(@Valid @RequestBody ApproveRepairRequest request) {
        return service.approveRepair(request);
    }

    @PostMapping("/complete-repair")
    public CaseResponse completeRepair(@Valid @RequestBody CompleteRepairRequest request) {
        return service.completeRepair(request);
    }

    @PostMapping("/reinspect")
    public CaseResponse reinspect(@Valid @RequestBody ReinspectRequest request) {
        return service.reinspect(request);
    }

    @PostMapping("/release")
    public CaseResponse release(@Valid @RequestBody ReleaseRequest request) {
        return service.release(request);
    }

    @GetMapping("/{caseNo}")
    public CaseResponse getCase(@PathVariable String caseNo) {
        return service.getCase(caseNo);
    }
}
