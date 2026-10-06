package com.example.starter.web;

import com.example.starter.service.ExhibitionService;
import com.example.starter.service.IdempotencyService;
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
 * 展示登记接口。
 */
@RestController
@RequestMapping("/api/zones")
public class ExhibitionController {

    /**
     * 展示登记请求；registeredAt 缺省取服务器当前时间。
     */
    public record RegisterRequest(
            @NotBlank String requestId,
            @NotBlank String groupName,
            LocalDateTime registeredAt
    ) {
    }

    private final ExhibitionService exhibitionService;
    private final IdempotencyService idempotencyService;
    private final JsonSupport jsonSupport;
    private final Clock clock;

    public ExhibitionController(ExhibitionService exhibitionService, IdempotencyService idempotencyService,
                                JsonSupport jsonSupport, Clock clock) {
        this.exhibitionService = exhibitionService;
        this.idempotencyService = idempotencyService;
        this.jsonSupport = jsonSupport;
        this.clock = clock;
    }

    @PostMapping("/{zoneCode}/exhibitions")
    public ResponseEntity<String> register(@PathVariable String zoneCode,
                                           @Valid @RequestBody RegisterRequest request) {
        LocalDateTime registeredAt = request.registeredAt() != null ? request.registeredAt()
                : LocalDateTime.now(clock);
        String canonical = "zoneCode=" + zoneCode + "&groupName=" + request.groupName()
                + "&registeredAt=" + registeredAt;
        String body = idempotencyService.execute(request.requestId(), "exhibition", canonical,
                () -> jsonSupport.toJson(Map.of("registrationId",
                        exhibitionService.register(zoneCode, request.requestId(), request.groupName(),
                                registeredAt))));
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(body);
    }
}
