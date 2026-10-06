package com.example.starter.web;

import com.example.starter.service.IdempotencyService;
import com.example.starter.service.SensorService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Map;

/**
 * 传感器心跳与复连确认接口。
 */
@RestController
@RequestMapping("/api/zones")
public class SensorController {

    /**
     * 心跳/复连确认请求；at 缺省取服务器当前时间。
     */
    public record SensorRequest(
            @NotBlank String requestId,
            LocalDateTime at
    ) {
    }

    private final SensorService sensorService;
    private final IdempotencyService idempotencyService;
    private final JsonSupport jsonSupport;
    private final Clock clock;

    public SensorController(SensorService sensorService, IdempotencyService idempotencyService,
                            JsonSupport jsonSupport, Clock clock) {
        this.sensorService = sensorService;
        this.idempotencyService = idempotencyService;
        this.jsonSupport = jsonSupport;
        this.clock = clock;
    }

    @PostMapping("/{zoneCode}/sensor/heartbeat")
    public ResponseEntity<String> heartbeat(@PathVariable String zoneCode,
                                            @Valid @RequestBody SensorRequest request) {
        LocalDateTime at = request.at() != null ? request.at() : LocalDateTime.now(clock);
        String canonical = "zoneCode=" + zoneCode + "&at=" + at;
        String body = idempotencyService.execute(request.requestId(), "heartbeat", canonical,
                () -> jsonSupport.toJson(Map.of("zoneCode", zoneCode, "sensorState",
                        sensorService.heartbeat(zoneCode, at).name())));
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(body);
    }

    @PostMapping("/{zoneCode}/sensor/reconnect-confirm")
    public ResponseEntity<String> confirmReconnect(@PathVariable String zoneCode,
                                                   @Valid @RequestBody SensorRequest request) {
        LocalDateTime at = request.at() != null ? request.at() : LocalDateTime.now(clock);
        String canonical = "zoneCode=" + zoneCode + "&at=" + at;
        String body = idempotencyService.execute(request.requestId(), "reconnect-confirm", canonical,
                () -> jsonSupport.toJson(sensorService.confirmReconnect(zoneCode, at)));
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(body);
    }
}
