package com.example.starter.telemetry;

import com.example.starter.telemetry.TelemetryService.TelemetryRequest;
import com.example.starter.telemetry.TelemetryService.TelemetryView;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 遥信回报 REST 入口。
 */
@RestController
@RequestMapping("/api/telemetry")
public class TelemetryController {

    private final TelemetryService telemetryService;

    public TelemetryController(TelemetryService telemetryService) {
        this.telemetryService = telemetryService;
    }

    /** 遥信回报：按间隔+时标去重，状态不符中止操作票。 */
    @PostMapping
    public TelemetryView report(@RequestBody TelemetryRequest req) {
        return telemetryService.report(req);
    }
}
