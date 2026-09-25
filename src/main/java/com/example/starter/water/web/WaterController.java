package com.example.starter.water.web;

import com.example.starter.water.service.WaterService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 取水监管服务 REST 入口。
 */
@RestController
@RequestMapping("/api")
public class WaterController {

    private final WaterService service;

    public WaterController(WaterService service) {
        this.service = service;
    }

    @PostMapping("/users")
    public ApiDtos.UserResponse createUser(@Valid @RequestBody ApiDtos.CreateUserRequest request) {
        return service.createUser(request);
    }

    @PostMapping("/meter-points")
    public ApiDtos.MeterPointResponse createMeterPoint(@Valid @RequestBody ApiDtos.CreateMeterPointRequest request) {
        return service.createMeterPoint(request);
    }

    @PostMapping("/declarations")
    public ApiDtos.DeclarationResponse declare(@Valid @RequestBody ApiDtos.DeclarationRequest request) {
        return service.declare(request);
    }

    @PostMapping("/readings")
    public ApiDtos.ReadingResponse reportReading(@Valid @RequestBody ApiDtos.ReadingRequest request) {
        return service.reportReading(request);
    }

    @PostMapping("/restrictions/trigger")
    public ApiDtos.RestrictionResponse triggerRestriction(@Valid @RequestBody ApiDtos.RestrictionRequest request) {
        return service.triggerRestriction(request);
    }

    @PostMapping("/plans/seal")
    public ApiDtos.SealResponse seal(@Valid @RequestBody ApiDtos.SealRequest request) {
        return service.seal(request);
    }

    @GetMapping("/users/{userCode}/quota/{year}")
    public ApiDtos.QuotaResponse quota(@PathVariable String userCode, @PathVariable int year) {
        return service.quota(userCode, year);
    }

    @GetMapping("/users/{userCode}/usage/{month}")
    public ApiDtos.UsageResponse usage(@PathVariable String userCode, @PathVariable String month) {
        return service.usage(userCode, month);
    }
}
