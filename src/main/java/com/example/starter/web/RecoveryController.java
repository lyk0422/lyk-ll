package com.example.starter.web;

import com.example.starter.service.IdempotencyService;
import com.example.starter.service.RecoveryService;
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
 * 复测恢复接口。
 */
@RestController
@RequestMapping("/api/zones")
public class RecoveryController {

    /**
     * 复测恢复请求；testedAt 缺省取服务器当前时间。
     */
    public record RecoveryRequest(
            @NotBlank String requestId,
            @NotNull BigDecimal dissolvedOxygen,
            @NotNull BigDecimal ammoniaNitrogen,
            @NotNull BigDecimal temperature,
            LocalDateTime testedAt
    ) {
    }

    private final RecoveryService recoveryService;
    private final IdempotencyService idempotencyService;
    private final JsonSupport jsonSupport;
    private final Clock clock;

    public RecoveryController(RecoveryService recoveryService, IdempotencyService idempotencyService,
                              JsonSupport jsonSupport, Clock clock) {
        this.recoveryService = recoveryService;
        this.idempotencyService = idempotencyService;
        this.jsonSupport = jsonSupport;
        this.clock = clock;
    }

    @PostMapping("/{zoneCode}/recovery")
    public ResponseEntity<String> recover(@PathVariable String zoneCode,
                                          @Valid @RequestBody RecoveryRequest request) {
        LocalDateTime testedAt = request.testedAt() != null ? request.testedAt()
                : LocalDateTime.now(clock);
        String canonical = "zoneCode=" + zoneCode + "&dissolvedOxygen=" + request.dissolvedOxygen()
                + "&ammoniaNitrogen=" + request.ammoniaNitrogen() + "&temperature=" + request.temperature()
                + "&testedAt=" + testedAt;
        String body = idempotencyService.execute(request.requestId(), "recovery", canonical,
                () -> jsonSupport.toJson(recoveryService.recover(zoneCode, request.requestId(),
                        request.dissolvedOxygen(), request.ammoniaNitrogen(), request.temperature(),
                        testedAt)));
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(body);
    }
}
