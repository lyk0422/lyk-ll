package com.example.starter.web;

import com.example.starter.service.MaintenanceService;
import com.example.starter.web.dto.ApiDtos.MaintenanceRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 维保登记接口（requestId 幂等）。
 */
@RestController
@RequestMapping("/api/maintenance")
public class MaintenanceController {

    private final MaintenanceService maintenanceService;

    public MaintenanceController(MaintenanceService maintenanceService) {
        this.maintenanceService = maintenanceService;
    }

    @PostMapping
    public ResponseEntity<String> register(@Valid @RequestBody MaintenanceRequest req) {
        return maintenanceService.register(req);
    }
}
