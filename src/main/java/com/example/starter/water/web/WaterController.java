package com.example.starter.water.web;

import com.example.starter.water.dto.CreateMeterRequest;
import com.example.starter.water.dto.CreateUserRequest;
import com.example.starter.water.dto.DeclarationRequest;
import com.example.starter.water.dto.DeclarationResult;
import com.example.starter.water.dto.LedgerView;
import com.example.starter.water.dto.MeterView;
import com.example.starter.water.dto.ReadingRequest;
import com.example.starter.water.dto.ReadingResult;
import com.example.starter.water.dto.RestrictionRequest;
import com.example.starter.water.dto.RestrictionResult;
import com.example.starter.water.dto.SealResult;
import com.example.starter.water.dto.UserView;
import com.example.starter.water.service.WaterService;

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
 * 取水监管 REST 入口。
 */
@RestController
@RequestMapping("/api/water")
public class WaterController {

    private final WaterService waterService;

    public WaterController(WaterService waterService) {
        this.waterService = waterService;
    }

    @PostMapping("/users")
    @ResponseStatus(HttpStatus.CREATED)
    public UserView createUser(@Valid @RequestBody CreateUserRequest request) {
        return waterService.createUser(request);
    }

    @PostMapping("/users/{userId}/meters")
    @ResponseStatus(HttpStatus.CREATED)
    public MeterView createMeter(@PathVariable Long userId, @Valid @RequestBody CreateMeterRequest request) {
        return waterService.createMeter(userId, request);
    }

    @PostMapping("/declarations")
    public DeclarationResult declare(@Valid @RequestBody DeclarationRequest request) {
        return waterService.declare(request);
    }

    @PostMapping("/readings")
    public ReadingResult reportReading(@Valid @RequestBody ReadingRequest request) {
        return waterService.reportReading(request);
    }

    @PostMapping("/restrictions")
    @ResponseStatus(HttpStatus.CREATED)
    public RestrictionResult triggerRestriction(@Valid @RequestBody RestrictionRequest request) {
        return waterService.triggerRestriction(request);
    }

    @GetMapping("/users/{userId}/ledger")
    public LedgerView ledger(@PathVariable Long userId,
                             @RequestParam(required = false) Integer year) {
        return waterService.ledger(userId, year);
    }

    @PostMapping("/users/{userId}/seal")
    public SealResult seal(@PathVariable Long userId, @RequestParam(required = false) Integer year) {
        return waterService.seal(userId, year);
    }
}
