package com.example.starter.web;

import com.example.starter.service.IdempotencyService;
import com.example.starter.service.MonitoringService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;

/**
 * 水质采样接入接口。
 */
@RestController
@RequestMapping("/api/zones")
public class MonitoringController {

    /**
     * 采样请求；sampledAt 缺省取服务器当前时间。
     */
    public record SampleRequest(
            @NotBlank String requestId,
            @NotNull BigDecimal dissolvedOxygen,
            @NotNull BigDecimal ammoniaNitrogen,
            @NotNull BigDecimal temperature,
            LocalDateTime sampledAt
    ) {
    }

    private final MonitoringService monitoringService;
    private final IdempotencyService idempotencyService;
    private final JsonSupport jsonSupport;
    private final Clock clock;

    public MonitoringController(MonitoringService monitoringService, IdempotencyService idempotencyService,
                                JsonSupport jsonSupport, Clock clock) {
        this.monitoringService = monitoringService;
        this.idempotencyService = idempotencyService;
        this.jsonSupport = jsonSupport;
        this.clock = clock;
    }

    @PostMapping("/{zoneCode}/samples")
    public ResponseEntity<String> ingest(@PathVariable String zoneCode,
                                         @Valid @RequestBody SampleRequest request) {
        LocalDateTime sampledAt = request.sampledAt() != null ? request.sampledAt()
                : LocalDateTime.now(clock);
        String canonical = "zoneCode=" + zoneCode + "&dissolvedOxygen=" + request.dissolvedOxygen()
                + "&ammoniaNitrogen=" + request.ammoniaNitrogen() + "&temperature=" + request.temperature()
                + "&sampledAt=" + sampledAt;
        String body = idempotencyService.execute(request.requestId(), "sample", canonical,
                () -> jsonSupport.toJson(monitoringService.ingest(zoneCode, request.requestId(),
                        request.dissolvedOxygen(), request.ammoniaNitrogen(), request.temperature(),
                        sampledAt)));
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(body);
    }
}
