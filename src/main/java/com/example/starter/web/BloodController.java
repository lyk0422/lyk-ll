package com.example.starter.web;

import com.example.starter.service.BloodService;
import com.example.starter.web.dto.IntakeRequest;
import com.example.starter.web.dto.ReleaseRequest;
import com.example.starter.web.dto.ReleaseResponse;
import com.example.starter.web.dto.TestResultRequest;
import com.example.starter.web.dto.UnitResponse;
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
 * 血液成分放行服务 HTTP 入口。
 */
@RestController
@RequestMapping("/api/blood-units")
public class BloodController {

    private final BloodService bloodService;

    public BloodController(BloodService bloodService) {
        this.bloodService = bloodService;
    }

    @PostMapping("/intake")
    @ResponseStatus(HttpStatus.CREATED)
    public UnitResponse intake(@Valid @RequestBody IntakeRequest request) {
        return bloodService.intake(request);
    }

    @PostMapping("/test-results")
    public UnitResponse reportTestResult(@Valid @RequestBody TestResultRequest request) {
        return bloodService.reportTestResult(request);
    }

    @PostMapping("/{barcode}/scrap")
    public UnitResponse scrap(@PathVariable String barcode) {
        return bloodService.scrap(barcode);
    }

    @PostMapping("/releases")
    public ReleaseResponse release(@Valid @RequestBody ReleaseRequest request) {
        return bloodService.release(request);
    }

    @GetMapping("/{barcode}")
    public UnitResponse getUnit(@PathVariable String barcode) {
        return UnitResponse.from(bloodService.getUnit(barcode));
    }
}
