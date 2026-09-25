package com.example.starter.tailings.api;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.starter.tailings.service.WarningService;

/**
 * 降雨预警与险情报送。
 */
@RestController
@RequestMapping("/api")
public class WarningController {

    private final WarningService warningService;

    public WarningController(WarningService warningService) {
        this.warningService = warningService;
    }

    /**
     * 降雨预警请求。
     */
    public record WarningBody(@NotBlank String requestId,
                              @NotNull Long pondId,
                              @NotNull BigDecimal rainfallMm,
                              @NotNull @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime warnTime) {
    }

    /**
     * 险情报送请求。
     */
    public record DangerReportBody(@NotBlank String requestId,
                                   @NotNull Long pondId,
                                   @NotBlank String description) {
    }

    @PostMapping("/warnings")
    public WarningService.WarningResult warn(@Valid @RequestBody WarningBody body) {
        return warningService.warn(body.requestId(), body.pondId(), body.rainfallMm(), body.warnTime());
    }

    @PostMapping("/danger-reports")
    public WarningService.DangerReportResult reportDanger(@Valid @RequestBody DangerReportBody body) {
        return warningService.reportDanger(body.requestId(), body.pondId(), body.description());
    }
}
