package com.example.starter.telemetry;

import jakarta.validation.Valid;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.starter.telemetry.TelemetryDtos.TelemetryRequest;
import com.example.starter.telemetry.TelemetryDtos.TelemetryResponse;

/**
 * 遥信回报接口。
 */
@RestController
@RequestMapping("/api/telemetry")
public class TelemetryController {

    private final TelemetryService service;

    public TelemetryController(TelemetryService service) {
        this.service = service;
    }

    /** 登记一条遥信回报。 */
    @PostMapping
    public TelemetryResponse record(@Valid @RequestBody TelemetryRequest req) {
        return service.record(req);
    }
}
